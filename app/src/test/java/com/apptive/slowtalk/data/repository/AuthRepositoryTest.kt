package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.auth.AuthSession
import com.apptive.slowtalk.data.auth.FakeAuthTokenStore
import com.apptive.slowtalk.data.remote.AuthApi
import com.apptive.slowtalk.data.remote.LoginRequest
import com.apptive.slowtalk.data.remote.RefreshRequest
import com.apptive.slowtalk.data.remote.SignupRequest
import com.apptive.slowtalk.data.remote.apiJson
import com.apptive.slowtalk.data.remote.createOkHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        AuthSession.initialize(FakeAuthTokenStore())
        val api = Retrofit.Builder()
            .baseUrl(server.url("api/v1/"))
            .client(createOkHttpClient(isDebug = false))
            .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)
        repository = AuthRepository(api)
    }

    @After
    fun tearDown() {
        AuthSession.resetForTest()
        server.shutdown()
    }

    @Test
    fun `login decodes envelope and saves token pair`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":true,"data":{"accessToken":"access-value","refreshToken":"refresh-value","tokenType":"Bearer","expiresIn":3600},"error":null,"meta":null}""",
                ),
        )

        val result = repository.login(LoginRequest("person@example.com", "password123"))

        assertTrue(result.isSuccess)
        assertEquals("access-value", AuthSession.accessToken)
        assertEquals("refresh-value", AuthSession.refreshToken)
        assertEquals("/api/v1/auth/login", server.takeRequest().path)
    }

    @Test
    fun `signup sends username and saves returned token pair`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":true,"data":{"userId":"00000000-0000-0000-0000-000000000001","accessToken":"access-value","refreshToken":"refresh-value","tokenType":"Bearer","expiresIn":3600},"error":null,"meta":null}""",
                ),
        )

        val result = repository.signup(
            SignupRequest("person@example.com", "password123", "nickname", "person_name"),
        )

        assertTrue(result.isSuccess)
        assertEquals("access-value", AuthSession.accessToken)
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/signup", request.path)
        assertTrue(request.body.readUtf8().contains("\"username\":\"person_name\""))
    }

    @Test
    fun `email availability uses backend path`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"data":{"available":true},"error":null,"meta":null}"""),
        )

        val result = repository.checkEmail("person@example.com")

        assertEquals(true, result.getOrNull())
        assertEquals(
            "/api/v1/auth/email-availability?email=person%40example.com",
            server.takeRequest().path,
        )
    }

    @Test
    fun `logout sends refresh token with bearer and clears local session on 204`() = runBlocking {
        AuthSession.save("access-value", "refresh-value")
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.logout()

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/logout", request.path)
        assertEquals("Bearer access-value", request.getHeader("Authorization"))
        assertEquals("{\"refreshToken\":\"refresh-value\"}", request.body.readUtf8())
        assertNull(AuthSession.accessToken)
        assertNull(AuthSession.refreshToken)
    }

    @Test
    fun `logout network failure is best effort success and clears local session`() = runBlocking {
        AuthSession.save("access-value", "refresh-value")
        server.shutdown()

        val result = repository.logout()

        assertTrue(result.isSuccess)
        assertNull(AuthSession.tokens.value)
    }
}
