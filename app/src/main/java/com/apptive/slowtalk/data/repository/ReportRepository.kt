package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ReportApi
import com.apptive.slowtalk.data.remote.ReportCreateRequest
import com.apptive.slowtalk.data.remote.ReportFeedbackRequest
import com.apptive.slowtalk.data.remote.ReportFeedbackResponse
import com.apptive.slowtalk.data.remote.RetrofitClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class ReportRepository(private val api: ReportApi = RetrofitClient.reportApi) {

    private val MOCK_MODE = true

    suspend fun createReport(content: String): Result<Int> {
        if (MOCK_MODE) return Result.success(100)
        return runCatching {
            api.createReport(ReportCreateRequest(content)).reportId
        }
    }

    suspend fun performOcr(imageFile: File): Result<String> {
        if (MOCK_MODE) return Result.success("OCR 인식 결과 샘플입니다.\n오늘 하루도 정말 고생 많으셨어요.")
        return runCatching {
            val requestFile = imageFile.asRequestBody("image/*".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)
            api.performOcr(body).content
        }
    }

    suspend fun getReportFeedback(content: String): Result<ReportFeedbackResponse> {
        if (MOCK_MODE) {
            return Result.success(
                ReportFeedbackResponse(
                    summary = "오늘 하루를 긍정적으로 되돌아보았습니다.",
                    feedback = listOf(
                        com.apptive.slowtalk.data.remote.FeedbackItemDto(
                            type = "오늘의 배움",
                            title = "천천히 걸을 때 더 많은 것을 볼 수 있다는 것.",
                            description = "속도보다 마음의 여유가 더 중요하다는 걸 느꼈어요."
                        ),
                        com.apptive.slowtalk.data.remote.FeedbackItemDto(
                            type = "내일의 다짐",
                            title = "잠시 멈춰 주변을 바라보는 시간을 가지기.",
                            description = "작은 행복을 놓치지 않고 감사하는 하루 보내기."
                        ),
                        com.apptive.slowtalk.data.remote.FeedbackItemDto(
                            type = "한 줄 기록",
                            title = "빠르게 가는 것도 좋지만,",
                            description = "천천히 가면 더 오래 기억되는 하루가 된다."
                        )
                    )
                )
            )
        }
        return runCatching {
            api.getReportFeedback(ReportFeedbackRequest(content))
        }
    }
}
