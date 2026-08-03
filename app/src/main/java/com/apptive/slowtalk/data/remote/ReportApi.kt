package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ReportApi {
    @POST("reports")
    suspend fun createReport(@Body request: ReportCreateRequest): ApiEnvelope<ReportCreateResponse>

    @Multipart
    @POST("reports/ocr")
    suspend fun performOcr(@Part image: MultipartBody.Part): ApiEnvelope<OcrTextDto>

    @POST("reports/feedback")
    suspend fun getReportFeedback(@Body request: ReportFeedbackRequest): ApiEnvelope<ReportAnalysisDto>
}

@Serializable
data class ReportCreateRequest(val content: String, val analysisId: String)

@Serializable
data class ReportCreateResponse(val id: String)

@Serializable
data class ReportFeedbackRequest(val content: String)

@Serializable
data class ReportAnalysisDto(
    val analysisId: String,
    val summary: String,
    val feedback: List<ReportCardDto>
)

@Serializable
data class ReportCardDto(val type: String, val content: String)

@Serializable
data class ReportFeedbackResponse(
    val summary: String,
    val feedback: List<FeedbackItemDto>
)

@Serializable
data class FeedbackItemDto(
    val type: String,
    val title: String,
    val description: String
)
