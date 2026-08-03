package com.apptive.slowtalk.data.auth

import com.apptive.slowtalk.data.remote.createOkHttpClient
import com.apptive.slowtalk.data.remote.AuthTokenRefresher
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        AuthSession.initialize(FakeAuthTokenStore())
    }

    @After
    fun tearDown() {
        AuthSession.resetForTest()
        server.shutdown()
    }

    @Test
    fun `public auth request has no authorization header`() {
        AuthSession.save("access-value", "refresh-value")
        server.enqueue(MockResponse().setBody("{}"))

        call("auth/login")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `authenticated request gets bearer header from memory`() {
        AuthSession.save("access-value", "refresh-value")
        server.enqueue(MockResponse().setBody("{}"))

        call("users/me")

        assertEquals("Bearer access-value", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `existing authorization header is preserved`() {
        AuthSession.save("session-access", "session-refresh")
        server.enqueue(MockResponse().setBody("{}"))
        val request = Request.Builder()
            .url(server.url("users/me"))
            .header("Authorization", "Custom request-credential")
            .build()

        createOkHttpClient(isDebug = false).newCall(request).execute().close()

        assertEquals("Custom request-credential", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `unauthorized response clears session`() {
        AuthSession.save("access-value", "refresh-value")
        server.enqueue(MockResponse().setResponseCode(401))

        call("users/me")

        assertNull(AuthSession.accessToken)
        assertNull(AuthSession.refreshToken)
    }

    @Test
    fun `public and custom authorization 401 do not clear local session`() {
        AuthSession.save("access-value", "refresh-value")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        call("auth/login/")
        val custom = Request.Builder()
            .url(server.url("users/me"))
            .header("Authorization", "Custom credential")
            .build()
        createOkHttpClient(isDebug = false).newCall(custom).execute().close()

        assertEquals("access-value", AuthSession.accessToken)
    }

    @Test
    fun `successful refresh rotates tokens and retries request once`() {
        AuthSession.save("old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val refreshes = AtomicInteger()
        val client = createOkHttpClient(
            isDebug = false,
            tokenRefresher = AuthTokenRefresher { refreshToken ->
                assertEquals("old-refresh", refreshToken)
                refreshes.incrementAndGet()
                AuthTokens("new-access", "new-refresh")
            },
        )

        client.newCall(Request.Builder().url(server.url("users/me")).build()).execute().close()

        assertEquals(1, refreshes.get())
        assertEquals(AuthTokens("new-access", "new-refresh"), AuthSession.tokens.value)
        server.takeRequest()
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `concurrent old token failures perform one refresh`() {
        AuthSession.save("old-access", "old-refresh")
        repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }
        val refreshes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = createOkHttpClient(
            isDebug = false,
            tokenRefresher = AuthTokenRefresher {
                refreshes.incrementAndGet()
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                AuthTokens("new-access", "new-refresh")
            },
        )
        val done = CountDownLatch(2)
        repeat(2) {
            Thread {
                client.newCall(Request.Builder().url(server.url("users/me")).build()).execute().close()
                done.countDown()
            }.start()
        }

        entered.await(2, TimeUnit.SECONDS)
        release.countDown()
        done.await(3, TimeUnit.SECONDS)

        assertEquals(1, refreshes.get())
        assertEquals("new-access", AuthSession.accessToken)
    }

    @Test
    fun `refresh failure clears only matching old session`() {
        AuthSession.save("old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401))
        val client = createOkHttpClient(
            isDebug = false,
            tokenRefresher = AuthTokenRefresher { null },
        )

        client.newCall(Request.Builder().url(server.url("users/me")).build()).execute().close()

        assertNull(AuthSession.tokens.value)
    }

    @Test
    fun `old token 401 retries a concurrently rotated session without refreshing`() {
        AuthSession.save("old-access", "old-refresh")
        val oldRequestEntered = CountDownLatch(1)
        val releaseOldResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Authorization") == "Bearer old-access") {
                    oldRequestEntered.countDown()
                    releaseOldResponse.await(2, TimeUnit.SECONDS)
                    MockResponse().setResponseCode(401)
                } else {
                    MockResponse().setResponseCode(200).setBody("{}")
                }
        }
        val refreshes = AtomicInteger()
        val client = createOkHttpClient(
            isDebug = false,
            tokenRefresher = AuthTokenRefresher {
                refreshes.incrementAndGet()
                null
            },
        )
        val done = CountDownLatch(1)
        Thread {
            client.newCall(Request.Builder().url(server.url("users/me")).build()).execute().close()
            done.countDown()
        }.start()

        oldRequestEntered.await(2, TimeUnit.SECONDS)
        AuthSession.save("rotated-access", "rotated-refresh")
        releaseOldResponse.countDown()
        done.await(3, TimeUnit.SECONDS)

        assertEquals(0, refreshes.get())
        assertEquals("rotated-access", AuthSession.accessToken)
    }

    @Test
    fun `refresh failure does not clear a session rotated during refresh`() {
        AuthSession.save("old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401))
        val client = createOkHttpClient(
            isDebug = false,
            tokenRefresher = AuthTokenRefresher {
                AuthSession.save("external-access", "external-refresh")
                null
            },
        )

        client.newCall(Request.Builder().url(server.url("users/me")).build()).execute().close()

        assertEquals(AuthTokens("external-access", "external-refresh"), AuthSession.tokens.value)
    }

    @Test
    fun `second unauthorized response clears the retried token`() {
        AuthSession.save("old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val client = createOkHttpClient(
            isDebug = false,
            tokenRefresher = AuthTokenRefresher {
                AuthTokens("new-access", "new-refresh")
            },
        )

        client.newCall(Request.Builder().url(server.url("users/me")).build()).execute().close()

        assertNull(AuthSession.tokens.value)
    }

    private fun call(path: String) {
        val request = Request.Builder().url(server.url(path)).build()
        createOkHttpClient(isDebug = false).newCall(request).execute().close()
    }
}
