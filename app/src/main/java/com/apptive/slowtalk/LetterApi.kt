package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.LetterCreateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import java.util.UUID

object LetterApi {
    suspend fun sendForMatch(content: String): Result<String?> = runCatching {
        val response = RetrofitClient.letterApi.createLetter(
            UUID.randomUUID().toString(),
            LetterCreateRequest(content, match = true)
        )
        check(response.ok && response.data != null) { "편지를 보내지 못했습니다." }
        response.data.chatRoom?.id
    }
}
