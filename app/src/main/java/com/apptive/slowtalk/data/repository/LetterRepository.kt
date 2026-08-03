package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.AiWarningDto
import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.LetterApiService
import com.apptive.slowtalk.data.remote.LetterCreateRequest
import com.apptive.slowtalk.data.remote.LetterFeedbackRequest
import com.apptive.slowtalk.data.remote.LetterFeedbackResponse
import com.apptive.slowtalk.data.remote.RegionDto
import com.apptive.slowtalk.data.remote.RetrofitClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID

class LetterRepository(private val api: LetterApiService = RetrofitClient.letterApi) {
    suspend fun getLetterFeedback(content: String): Result<LetterFeedbackResponse> = runCatching {
        val feedback = api.getLetterFeedback(LetterFeedbackRequest(content)).requireLetterData()
        LetterFeedbackResponse(
            warning = AiWarningDto(exists = false, message = feedback.summary),
            tips = feedback.suggestions
        )
    }

    suspend fun createLetter(content: String, match: Boolean, region: RegionDto): Result<Unit> = runCatching {
        api.createLetter(
            idempotencyKey = UUID.randomUUID().toString(),
            request = LetterCreateRequest(content, match)
        ).requireLetterData()
        Unit
    }

    suspend fun performLetterOcr(imageFile: File): Result<String> = runCatching {
        val requestFile = imageFile.asRequestBody("image/*".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)
        api.performLetterOcr(body).requireLetterData().text
    }
}

private fun <T> ApiEnvelope<T>.requireLetterData(): T {
    if (!ok || data == null) {
        throw IllegalStateException(error?.message ?: "편지 요청을 처리하지 못했습니다.")
    }
    return data
}
