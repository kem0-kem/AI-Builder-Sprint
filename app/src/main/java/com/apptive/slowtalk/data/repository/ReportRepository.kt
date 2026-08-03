package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ReportApi
import com.apptive.slowtalk.data.remote.ReportCreateRequest
import com.apptive.slowtalk.data.remote.ReportFeedbackRequest
import com.apptive.slowtalk.data.remote.ReportFeedbackResponse
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.requireData
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class ReportRepository(private val api: ReportApi = RetrofitClient.reportApi) {

    private val MOCK_MODE = true
    private var latestAnalysisId: String? = null

    suspend fun createReport(content: String): Result<String> {
        if (MOCK_MODE) return Result.success("00000000-0000-0000-0000-000000000100")
        return runCatching {
            val analysisId = checkNotNull(latestAnalysisId) { "보고서를 저장하기 전에 피드백 분석이 필요합니다." }
            api.createReport(ReportCreateRequest(analysisId, content)).requireData().id
        }
    }

    suspend fun performOcr(imageFile: File): Result<String> {
        if (MOCK_MODE) return Result.success("OCR 인식 결과 샘플입니다.\n오늘 하루도 정말 고생 많으셨어요.")
        return runCatching {
            val requestFile = imageFile.asRequestBody("image/*".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)
            api.performOcr(body).requireData().text
        }
    }

    suspend fun getReportFeedback(content: String): Result<ReportFeedbackResponse> {
        if (MOCK_MODE) {
            return Result.success(
                ReportFeedbackResponse(
                    analysisId = "00000000-0000-0000-0000-000000000200",
                    summary = "오늘 하루를 긍정적으로 되돌아보았습니다.",
                    feedback = listOf(
                        com.apptive.slowtalk.data.remote.FeedbackItemDto(
                            type = "오늘의 배움",
                            content = "천천히 걸을 때 더 많은 것을 볼 수 있다는 것. 속도보다 마음의 여유가 더 중요하다는 걸 느꼈어요."
                        ),
                        com.apptive.slowtalk.data.remote.FeedbackItemDto(
                            type = "내일의 다짐",
                            content = "잠시 멈춰 주변을 바라보는 시간을 가지기. 작은 행복을 놓치지 않고 감사하는 하루 보내기."
                        ),
                        com.apptive.slowtalk.data.remote.FeedbackItemDto(
                            type = "한 줄 기록",
                            content = "빠르게 가는 것도 좋지만, 천천히 가면 더 오래 기억되는 하루가 된다."
                        )
                    )
                )
            )
        }
        return runCatching {
            api.getReportFeedback(ReportFeedbackRequest(content)).requireData().also {
                latestAnalysisId = it.analysisId
            }
        }
    }
}
