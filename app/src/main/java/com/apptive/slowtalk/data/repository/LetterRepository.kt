package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.AiWarningDto
import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.LetterApiService
import com.apptive.slowtalk.data.remote.LetterCreateRequest
import com.apptive.slowtalk.data.remote.LetterFeedbackRequest
import com.apptive.slowtalk.data.remote.LetterFeedbackResponse
import com.apptive.slowtalk.data.remote.RegionDto
import com.apptive.slowtalk.data.remote.RetrofitClient
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID

class LetterRepository(private val api: LetterApiService = RetrofitClient.letterApi) {
    suspend fun getLetterFeedback(content: String): Result<LetterFeedbackResponse> = runCatching {
        val feedback = api.getLetterFeedback(LetterFeedbackRequest(content)).requireLetterData()
        LetterFeedbackResponse(
            summary = feedback.summary,
            warning = AiWarningDto(exists = false),
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
        val requestFile = imageFile.asRequestBody(imageFile.detectOcrMediaType())
        val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)
        api.performLetterOcr(body).requireLetterData().text
    }
}

internal fun File.detectOcrMediaType(): MediaType {
    val header = inputStream().use { input ->
        ByteArray(12).also { buffer ->
            if (input.read(buffer) < 3) {
                throw IllegalArgumentException("이미지 파일을 읽을 수 없습니다.")
            }
        }
    }

    val mimeType = when {
        header.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        header.startsWithAscii("RIFF") && header.startsWithAscii("WEBP", offset = 8) -> "image/webp"
        else -> throw IllegalArgumentException("지원하지 않는 이미지 형식입니다. JPG, PNG, WEBP 이미지를 선택해 주세요.")
    }
    return mimeType.toMediaType()
}

private fun ByteArray.startsWith(vararg signature: Int): Boolean =
    signature.indices.all { index -> (this[index].toInt() and 0xFF) == signature[index] }

private fun ByteArray.startsWithAscii(value: String, offset: Int = 0): Boolean =
    value.indices.all { index -> this[offset + index].toInt() == value[index].code }

private fun <T> ApiEnvelope<T>.requireLetterData(): T {
    if (!ok || data == null) {
        throw IllegalStateException(error?.message ?: "편지 요청을 처리하지 못했습니다.")
    }
    return data
}
