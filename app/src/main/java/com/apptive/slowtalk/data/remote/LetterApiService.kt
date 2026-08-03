package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Response

interface LetterApiService {
    @GET("letters")
    suspend fun getLetters(@Query("direction") direction: String? = null): ApiEnvelope<List<LetterSummaryDto>>

    @GET("letters/{letterId}")
    suspend fun getLetter(@Path("letterId") letterId: String): ApiEnvelope<LetterDetailDto>

    @POST("letters/feedback")
    suspend fun getLetterFeedback(@Body request: LetterFeedbackRequest): ApiEnvelope<LetterFeedbackResponse>

    @POST("letters")
    suspend fun createLetter(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: LetterCreateRequest,
    ): Response<ApiEnvelope<JsonElement>>

    @Multipart
    @POST("letters/ocr")
    suspend fun performLetterOcr(@Part image: MultipartBody.Part): Response<ApiEnvelope<JsonElement>>
}

@Serializable
data class LetterFeedbackRequest(val content: String)

@Serializable
data class LetterFeedbackResponse(
    val summary: String? = null,
    val suggestions: List<String> = emptyList(),
    val warning: AiWarningDto? = null,
    val tips: List<String> = emptyList()
) {
    val displayTips: List<String>
        get() = tips.ifEmpty { suggestions }
}

@Serializable
data class AiWarningDto(
    val exists: Boolean,
    val message: String? = null
)

@Serializable
data class LetterCreateRequest(
    val content: String,
    val match: Boolean,
)

@Serializable
data class LetterOcrResponse(val text: String)

@Serializable
data class LetterSummaryDto(
    val id: String,
    val direction: String,
    val content: String,
    val createdAt: String
)

@Serializable
data class LetterDetailDto(
    val id: String,
    val direction: String,
    val content: String,
    val createdAt: String
)

@Serializable
data class LetterCreateResponse(
    val letter: LetterDetailDto,
    val matching: LetterMatchingDto,
    val chatRoom: LetterChatRoomDto? = null,
    val firstMessage: LetterFirstMessageDto? = null,
)

@Serializable
data class LetterMatchingDto(
    val matched: Boolean,
    val strategy: String? = null,
    val fallbackReason: String? = null,
)

@Serializable
data class LetterChatRoomDto(val id: String, val type: String)

@Serializable
data class LetterFirstMessageDto(val id: String, val type: String)
