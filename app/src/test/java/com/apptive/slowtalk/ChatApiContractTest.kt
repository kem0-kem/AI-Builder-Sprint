package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.ChatApiService
import com.apptive.slowtalk.data.remote.apiJson
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class ChatApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ChatApiService

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("api/v1/"))
            .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ChatApiService::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `comment chat creation is a bodyless post and decodes the room envelope`() = runBlocking {
        server.enqueue(jsonResponse(ROOM_RESPONSE))

        val response = api.createFromComment(COMMENT_ID)

        assertEquals(ROOM_ID, response.data?.id)
        assertEquals("DIRECT", response.data?.type)
        assertNull(response.data?.name)
        server.takeRequest().let {
            assertEquals("POST", it.method)
            assertEquals("/api/v1/comments/$COMMENT_ID/chat-room", it.path)
            assertEquals(0L, it.bodySize)
        }
    }

    @Test
    fun `comment chat adapter maps a direct room response`() = runBlocking {
        server.enqueue(jsonResponse(ROOM_RESPONSE))

        val result = ChatApi.openFromComment(COMMENT_ID, api)

        assertTrue(result.isSuccess)
        result.getOrThrow().let { room ->
            assertEquals(ROOM_ID, room.id)
            assertFalse(room.isGroup)
            assertNull(room.name)
            assertNull(room.participantCount)
        }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val COMMENT_ID = "33333333-3333-4333-8333-333333333333"
        const val ROOM_ID = "55555555-5555-4555-8555-555555555555"
        const val ROOM_RESPONSE =
            """{"ok":true,"data":{"id":"$ROOM_ID","type":"DIRECT","name":null},"error":null,"meta":null}"""
    }
}
