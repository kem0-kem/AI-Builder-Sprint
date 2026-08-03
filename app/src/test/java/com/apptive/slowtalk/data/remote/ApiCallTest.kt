package com.apptive.slowtalk.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

class ApiCallTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `non 2xx error envelope keeps backend code and message`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":false,"data":null,"error":{"code":"RESOURCE_CONFLICT","message":"Already exists","details":{}},"meta":null}""",
                ),
        )
        val json = Json { ignoreUnknownKeys = true }
        val service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ErrorService::class.java)

        val exception = runCatching { apiData(json) { service.getValue() } }.exceptionOrNull()

        assertTrue(exception is ApiEnvelopeException)
        assertEquals("RESOURCE_CONFLICT", (exception as ApiEnvelopeException).error.code)
        assertEquals("Already exists", exception.error.message)
    }

    private interface ErrorService {
        @GET("failure")
        suspend fun getValue(): ApiEnvelope<ValueDto>
    }

    @Serializable
    private data class ValueDto(val value: String)
}
