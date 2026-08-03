package com.apptive.slowtalk.data.remote

import okhttp3.MultipartBody
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ReportApi {
    @POST("reports")
    suspend fun createReport(@Body request: ReportCreateRequest): Response<ApiEnvelope<JsonElement>>

    @Multipart
    @POST("reports/ocr")
    suspend fun performOcr(@Part image: MultipartBody.Part): Response<ApiEnvelope<JsonElement>>

    @POST("reports/feedback")
    suspend fun getReportFeedback(@Body request: ReportFeedbackRequest): ApiEnvelope<ReportFeedbackResponse>
}

@kotlinx.serialization.Serializable
data class ReportCreateRequest(val analysisId: String, val content: String)

@kotlinx.serialization.Serializable
data class ReportCreateResponse(
    val id: String,
    val content: String,
    val summary: String,
    val feedback: List<FeedbackItemDto>,
    val createdAt: String,
)

@kotlinx.serialization.Serializable
data class OcrResponse(val text: String)

@kotlinx.serialization.Serializable
data class ReportFeedbackRequest(val content: String)

@kotlinx.serialization.Serializable
data class ReportFeedbackResponse(
    val analysisId: String,
    val summary: String,
    val feedback: List<FeedbackItemDto>
)

@kotlinx.serialization.Serializable
data class FeedbackItemDto(
    val type: String,
    val content: String,
)
