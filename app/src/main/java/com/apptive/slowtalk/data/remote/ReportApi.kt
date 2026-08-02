package com.apptive.slowtalk.data.remote

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ReportApi {
    @POST("reports")
    suspend fun createReport(@Body request: ReportCreateRequest): ReportCreateResponse

    @Multipart
    @POST("reports/ocr")
    suspend fun performOcr(@Part image: MultipartBody.Part): OcrResponse

    @POST("reports/feedback")
    suspend fun getReportFeedback(@Body request: ReportFeedbackRequest): ReportFeedbackResponse
}

@kotlinx.serialization.Serializable
data class ReportCreateRequest(val content: String)

@kotlinx.serialization.Serializable
data class ReportCreateResponse(val reportId: Int)

@kotlinx.serialization.Serializable
data class OcrResponse(val content: String)

@kotlinx.serialization.Serializable
data class ReportFeedbackRequest(val content: String)

@kotlinx.serialization.Serializable
data class ReportFeedbackResponse(
    val summary: String,
    val feedback: List<FeedbackItemDto>
)

@kotlinx.serialization.Serializable
data class FeedbackItemDto(
    val type: String,
    val title: String,
    val description: String
)
