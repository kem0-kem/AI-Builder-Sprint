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
    suspend fun getLetters(@Query("type") type: String? = null): List<LetterSummaryDto>

    @GET("letters/{letterId}")
    suspend fun getLetter(@Path("letterId") letterId: Int): LetterDetailDto

    @POST("letters/feedback")
    suspend fun getLetterFeedback(@Body request: LetterFeedbackRequest): LetterFeedbackResponse

    @POST("letters")
    suspend fun createLetter(@Body request: LetterCreateRequest)

    @Multipart
    @POST("letters/ocr")
    suspend fun performLetterOcr(@Part image: MultipartBody.Part): LetterOcrResponse
}

@Serializable
data class LetterFeedbackRequest(val content: String)

@Serializable
data class LetterFeedbackResponse(
    val warning: AiWarningDto,
    val tips: List<String>
)

@Serializable
data class AiWarningDto(
    val exists: Boolean,
    val message: String? = null
)

@Serializable
data class LetterCreateRequest(
    val content: String,
    val match: Boolean,
    val region: RegionDto
)

@Serializable
data class LetterOcrResponse(val content: String)

@Serializable
data class LetterSummaryDto(
    val letterId: Int,
    val type: String,
    val createdAt: String
)

@Serializable
data class LetterDetailDto(
    val letterId: Int,
    val type: String,
    val content: String,
    val createdAt: String
)
