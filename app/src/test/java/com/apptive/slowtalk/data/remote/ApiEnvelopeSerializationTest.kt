package com.apptive.slowtalk.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEnvelopeSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `backend feed page envelope decodes UUID resources`() {
        val body = """
            {
              "ok": true,
              "data": [{
                "id": "9a4c3d88-5ac9-4a63-9e77-fbb74e33a610",
                "categoryId": "d5d13bf5-2e15-4923-8762-f2b29937ac37",
                "title": "Slow day",
                "content": "I took a walk.",
                "isMine": true,
                "liked": false,
                "likeCount": 0,
                "commentCount": 0,
                "createdAt": "2026-08-03T10:00:00+00:00",
                "updatedAt": "2026-08-03T10:00:00+00:00"
              }],
              "error": null,
              "meta": {"nextCursor": null, "hasNext": false}
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<List<FeedTimelineDto>>>(body)

        assertTrue(envelope.ok)
        assertEquals("9a4c3d88-5ac9-4a63-9e77-fbb74e33a610", envelope.requireData().single().id)
        assertEquals("d5d13bf5-2e15-4923-8762-f2b29937ac37", envelope.requireData().single().categoryId)
        assertFalse(envelope.meta?.hasNext ?: true)
    }

    @Test
    fun `error envelope throws one consistent exception`() {
        val body = """
            {"ok":false,"data":null,"error":{"code":"AUTHENTICATION_REQUIRED","message":"Login required","details":{}},"meta":null}
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<List<FeedTimelineDto>>>(body)
        val exception = runCatching { envelope.requireData() }.exceptionOrNull()

        assertTrue(exception is ApiEnvelopeException)
        assertEquals("AUTHENTICATION_REQUIRED", (exception as ApiEnvelopeException).error.code)
    }

    @Test
    fun `moderation 202 envelope decodes as pending instead of resource`() {
        val body = """
            {"ok":true,"data":{"moderationStatus":"PENDING_REVIEW","submissionId":"a29efb06-583f-46b0-968a-b2846338f00f"},"error":null,"meta":null}
        """.trimIndent()
        val envelope = json.decodeFromString<ApiEnvelope<JsonElement>>(body)

        val result = decodeModeratedEnvelope(
            statusCode = 202,
            envelope = envelope,
            serializer = FeedTimelineDto.serializer(),
            json = json,
        )

        assertTrue(result is ModeratedApiResult.Pending)
        assertEquals(
            "a29efb06-583f-46b0-968a-b2846338f00f",
            (result as ModeratedApiResult.Pending).moderation.submissionId,
        )
        assertTrue(runCatching { result.requireResource() }.exceptionOrNull() is ModerationPendingException)
    }
}
