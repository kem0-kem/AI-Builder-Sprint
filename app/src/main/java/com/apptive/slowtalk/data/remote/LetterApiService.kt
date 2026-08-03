package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface LetterApiService {
    @GET("letters")
    suspend fun getLetters(@Query("type") type: String? = null): ApiEnvelope<List<LetterSummaryDto>>

    @GET("letters/{letterId}")
    suspend fun getLetter(@Path("letterId") letterId: String): ApiEnvelope<LetterDetailDto>

    @POST("letters/feedback")
    suspend fun getLetterFeedback(@Body request: LetterFeedbackRequest): ApiEnvelope<LetterFeedbackResponse>

    @POST("letters")
    suspend fun createLetter(@Body request: LetterCreateRequest): ApiEnvelope<LetterCreateResponse>

    @Multipart
    @POST("letters/ocr")
    suspend fun performLetterOcr(@Part image: MultipartBody.Part): ApiEnvelope<LetterOcrResponse>
}

@Serializable
data class LetterFeedbackRequest(val content: String)

@Serializable
data class LetterFeedbackResponse(
    val summary: String,
    val suggestions: List<String>,
) {
    val warning: AiWarningDto
        get() = AiWarningDto(exists = false)
    val tips: List<String>
        get() = suggestions
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
