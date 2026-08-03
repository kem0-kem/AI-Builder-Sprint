package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.LetterApiService
import com.apptive.slowtalk.data.remote.LetterCreateRequest
import com.apptive.slowtalk.data.remote.LetterFeedbackRequest
import com.apptive.slowtalk.data.remote.LetterFeedbackResponse
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.RegionDto
import com.apptive.slowtalk.data.remote.LetterCreateResponse
import com.apptive.slowtalk.data.remote.LetterOcrResponse
import com.apptive.slowtalk.data.remote.apiData
import com.apptive.slowtalk.data.remote.apiModerated
import com.apptive.slowtalk.data.remote.requireResource
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID

class LetterRepository(private val api: LetterApiService = RetrofitClient.letterApi) {

    suspend fun getLetterFeedback(content: String): Result<LetterFeedbackResponse> {
        return runCatching {
            apiData { api.getLetterFeedback(LetterFeedbackRequest(content)) }
        }
    }

    suspend fun createLetter(content: String, match: Boolean, region: RegionDto): Result<LetterCreateResponse> {
        return runCatching {
            apiModerated(LetterCreateResponse.serializer()) {
                api.createLetter(
                    idempotencyKey = UUID.randomUUID().toString(),
                    request = LetterCreateRequest(content, match),
                )
            }.requireResource()
        }
    }

    suspend fun performLetterOcr(imageFile: File): Result<String> {
        return runCatching {
            val requestFile = imageFile.asRequestBody(imageFile.ocrMediaType())
            val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)
            apiModerated(LetterOcrResponse.serializer()) {
                api.performLetterOcr(body)
            }.requireResource().text
        }
    }
}

private fun File.ocrMediaType() = when (extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    else -> error("지원하지 않는 이미지 형식입니다: .$extension")
}.toMediaTypeOrNull()
