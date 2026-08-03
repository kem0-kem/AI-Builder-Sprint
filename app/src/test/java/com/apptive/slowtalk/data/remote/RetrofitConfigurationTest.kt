package com.apptive.slowtalk.data.remote

import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrofitConfigurationTest {
    @Test
    fun `base URL gets one trailing slash`() {
        assertEquals(
            "http://10.0.2.2:8000/api/v1/",
            normalizeBaseUrl("http://10.0.2.2:8000/api/v1"),
        )
    }

    @Test
    fun `base URL keeps a single trailing slash`() {
        assertEquals(
            "http://10.0.2.2:8000/api/v1/",
            normalizeBaseUrl("  http://10.0.2.2:8000/api/v1///  "),
        )
    }

    @Test
    fun `blank configuration uses the emulator API URL`() {
        assertEquals(
            "http://10.0.2.2:8000/api/v1/",
            configuredBaseUrl("", isDebug = true),
        )
    }

    @Test
    fun `release configuration rejects a blank URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            configuredBaseUrl("", isDebug = false)
        }
    }

    @Test
    fun `release configuration rejects a local HTTP URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            configuredBaseUrl("http://10.0.2.2:8000/api/v1/", isDebug = false)
        }
    }

    @Test
    fun `HTTP body logger is debug only`() {
        assertTrue(
            createOkHttpClient(isDebug = true).interceptors.any {
                it is HttpLoggingInterceptor
            },
        )
        assertFalse(
            createOkHttpClient(isDebug = false).interceptors.any {
                it is HttpLoggingInterceptor
            },
        )
    }

    @Test
    fun `debug HTTP logger does not print authorization header`() {
        val logs = mutableListOf<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        try {
            val client = createOkHttpClient(
                isDebug = true,
                logger = HttpLoggingInterceptor.Logger(logs::add),
            )
            client.newCall(
                Request.Builder()
                    .url(server.url("/api/v1/health"))
                    .header("Authorization", "Bearer secret-token")
                    .build(),
            ).execute().close()

            assertFalse(logs.any { it.contains("Authorization:") })
            assertFalse(logs.any { it.contains("secret-token") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `debug logger never prints request body credentials or tokens`() {
        val logs = mutableListOf<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        try {
            val body = """{"password":"password-value","accessToken":"access-value","refreshToken":"refresh-value"}"""
                .toRequestBody("application/json".toMediaType())
            createOkHttpClient(
                isDebug = true,
                logger = HttpLoggingInterceptor.Logger(logs::add),
            ).newCall(
                Request.Builder().url(server.url("/api/v1/auth/login")).post(body).build(),
            ).execute().close()

            val output = logs.joinToString("\n")
            assertFalse(output.contains("password-value"))
            assertFalse(output.contains("access-value"))
            assertFalse(output.contains("refresh-value"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `public auth paths match only normalized API endpoints`() {
        assertTrue(isPublicAuthPath("/api/v1/auth/login"))
        assertTrue(isPublicAuthPath("/api/v1/auth/login/"))
        assertFalse(isPublicAuthPath("/api/v1/auth/login/extra"))
        assertFalse(isPublicAuthPath("/other/auth/login"))
    }

    @Test
    fun `backend token refresher posts refresh body and decodes rotated pair`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":true,"data":{"accessToken":"rotated-access","refreshToken":"rotated-refresh","tokenType":"Bearer","expiresIn":3600},"error":null,"meta":null}""",
                ),
        )
        server.start()
        try {
            val tokens = createBackendTokenRefresher(server.url("/api/v1/").toString())
                .refresh("source-refresh")

            assertEquals(
                AuthRefreshOutcome.Rotated(
                    com.apptive.slowtalk.data.auth.AuthTokens("rotated-access", "rotated-refresh"),
                ),
                tokens,
            )
            val request = server.takeRequest()
            assertEquals("/api/v1/auth/token/refresh", request.path)
            assertEquals("{\"refreshToken\":\"source-refresh\"}", request.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }


    @Test
    fun `backend token refresher distinguishes rejected and transient responses`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        server.start()
        try {
            val refresher = createBackendTokenRefresher(server.url("/api/v1/").toString())

            assertEquals(AuthRefreshOutcome.Rejected, refresher.refresh("refresh-one"))
            assertEquals(AuthRefreshOutcome.Rejected, refresher.refresh("refresh-two"))
            assertEquals(AuthRefreshOutcome.TransientFailure, refresher.refresh("refresh-three"))
            assertEquals(AuthRefreshOutcome.TransientFailure, refresher.refresh("refresh-four"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend token refresher treats network failure as transient`() {
        val server = MockWebServer()
        server.start()
        val refresher = createBackendTokenRefresher(server.url("/api/v1/").toString())
        server.shutdown()

        assertEquals(AuthRefreshOutcome.TransientFailure, refresher.refresh("refresh-value"))
    }

    @Test
    fun `websocket base URL uses the normalized API base path`() {
        assertEquals(
            "ws://10.0.2.2:8000/api/v1/",
            webSocketBaseUrl("http://10.0.2.2:8000/api/v1"),
        )
    }
}
