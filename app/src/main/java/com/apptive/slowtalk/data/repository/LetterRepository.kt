package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.LetterApiService
import com.apptive.slowtalk.data.remote.LetterCreateRequest
import com.apptive.slowtalk.data.remote.LetterFeedbackRequest
import com.apptive.slowtalk.data.remote.LetterFeedbackResponse
import com.apptive.slowtalk.data.remote.AiWarningDto
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.RegionDto
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class LetterRepository(private val api: LetterApiService = RetrofitClient.letterApi) {

    private val MOCK_MODE = true

    suspend fun getLetterFeedback(content: String): Result<LetterFeedbackResponse> {
        if (MOCK_MODE) {
            return Result.success(
                LetterFeedbackResponse(
                    warning = AiWarningDto(exists = false),
                    tips = listOf(
                        "'예쁘더라고요'처럼 감정을 표현해주셔서 좋아요.",
                        "마지막 인사도 따뜻해서 기분 좋은 편지가 될 것 같아요."
                    )
                )
            )
        }
        return runCatching {
            api.getLetterFeedback(LetterFeedbackRequest(content))
        }
    }

    suspend fun createLetter(content: String, match: Boolean, region: RegionDto): Result<Unit> {
        if (MOCK_MODE) return Result.success(Unit)
        return runCatching {
            api.createLetter(LetterCreateRequest(content, match, region))
        }
    }

    suspend fun performLetterOcr(imageFile: File): Result<String> {
        if (MOCK_MODE) return Result.success("오늘 하루도 잘 보내셨나요?\n손으로 적은 마음을 천천히 전해봅니다.\n작은 기쁨이 오래 머무는 하루이길 바라요.")
        return runCatching {
            val requestFile = imageFile.asRequestBody("image/*".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)
            api.performLetterOcr(body).content
        }
    }
}
