package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface LetterApiService {
    @POST("letters")
    suspend fun createLetter(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: LetterCreateRequest
    ): ApiEnvelope<LetterCreateResponse>
}

@Serializable
data class LetterCreateRequest(val content: String, val match: Boolean)

@Serializable
data class LetterCreateResponse(val chatRoom: LetterChatRoomDto? = null)

@Serializable
data class LetterChatRoomDto(val id: String, val type: String)
