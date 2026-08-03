package com.apptive.slowtalk.data.auth

import com.apptive.slowtalk.data.remote.createOkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

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

    private fun call(path: String) {
        val request = Request.Builder().url(server.url(path)).build()
        createOkHttpClient(isDebug = false).newCall(request).execute().close()
    }
}
