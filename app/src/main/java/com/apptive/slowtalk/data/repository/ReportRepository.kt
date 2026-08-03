package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.FeedbackItemDto
import com.apptive.slowtalk.data.remote.ReportApi
import com.apptive.slowtalk.data.remote.ReportCreateRequest
import com.apptive.slowtalk.data.remote.ReportFeedbackRequest
import com.apptive.slowtalk.data.remote.ReportFeedbackResponse
import com.apptive.slowtalk.data.remote.RetrofitClient
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class ReportRepository(private val api: ReportApi = RetrofitClient.reportApi) {
    private var lastAnalysisId: String? = null

    suspend fun createReport(content: String): Result<Int> = runCatching {
        val analysisId = checkNotNull(lastAnalysisId) {
            "회고 분석을 먼저 실행해 주세요."
        }
        val created = api.createReport(ReportCreateRequest(content, analysisId)).requireReportData()
        created.id.hashCode()
    }

    suspend fun performOcr(imageFile: File): Result<String> = runCatching {
        val requestFile = imageFile.asRequestBody(imageFile.detectOcrMediaType())
        val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)
        api.performOcr(body).requireReportData().text
    }

    suspend fun getReportFeedback(content: String): Result<ReportFeedbackResponse> = runCatching {
        val analysis = api.getReportFeedback(ReportFeedbackRequest(content)).requireReportData()
        lastAnalysisId = analysis.analysisId
        ReportFeedbackResponse(
            summary = analysis.summary,
            feedback = analysis.feedback.map { card ->
                FeedbackItemDto(
                    type = card.type,
                    title = card.content,
                    description = ""
                )
            }
        )
    }
}

private fun <T> ApiEnvelope<T>.requireReportData(): T {
    if (!ok || data == null) {
        throw IllegalStateException(error?.message ?: "회고 요청을 처리하지 못했습니다.")
    }
    return data
}
