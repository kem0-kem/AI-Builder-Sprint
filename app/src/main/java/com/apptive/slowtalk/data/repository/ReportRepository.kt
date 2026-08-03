package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ReportApi
import com.apptive.slowtalk.data.remote.ReportCreateRequest
import com.apptive.slowtalk.data.remote.ReportFeedbackRequest
import com.apptive.slowtalk.data.remote.ReportFeedbackResponse
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.OcrResponse
import com.apptive.slowtalk.data.remote.ReportCreateResponse
import com.apptive.slowtalk.data.remote.apiData
import com.apptive.slowtalk.data.remote.apiModerated
import com.apptive.slowtalk.data.remote.requireResource
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class ReportRepository(private val api: ReportApi = RetrofitClient.reportApi) {

    private var latestAnalysisId: String? = null

    suspend fun createReport(content: String): Result<String> {
        return runCatching {
            val analysisId = checkNotNull(latestAnalysisId) { "보고서를 저장하기 전에 피드백 분석이 필요합니다." }
            apiModerated(ReportCreateResponse.serializer()) {
                api.createReport(ReportCreateRequest(analysisId, content))
            }.requireResource().id
        }
    }

    suspend fun performOcr(imageFile: File): Result<String> {
        return runCatching {
            val requestFile = imageFile.asRequestBody(imageFile.ocrMediaType())
            val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)
            apiModerated(OcrResponse.serializer()) {
                api.performOcr(body)
            }.requireResource().text
        }
    }

    suspend fun getReportFeedback(content: String): Result<ReportFeedbackResponse> {
        return runCatching {
            apiData { api.getReportFeedback(ReportFeedbackRequest(content)) }.also {
                latestAnalysisId = it.analysisId
            }
        }
    }
}

private fun File.ocrMediaType() = when (extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    else -> error("지원하지 않는 이미지 형식입니다: .$extension")
}.toMediaTypeOrNull()
