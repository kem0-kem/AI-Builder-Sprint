package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.LetterDetailDto
import com.apptive.slowtalk.data.remote.LetterSummaryDto
import com.apptive.slowtalk.data.remote.RetrofitClient

object LetterApi {
    suspend fun getLetters(type: String? = null): Result<List<Letter>> = runCatching {
        val direction = if (type.equals("received", ignoreCase = true)) "received" else "sent"
        RetrofitClient.letterApi.getLetters(direction).requireLetterData().map(LetterSummaryDto::toLetter)
    }

    suspend fun getLetter(letterId: String): Result<Letter> = runCatching {
        RetrofitClient.letterApi.getLetter(letterId).requireLetterData().toLetter()
    }
}

private fun LetterSummaryDto.toLetter(): Letter {
    val received = direction.equals("RECEIVED", ignoreCase = true)
    return Letter(
        id = id,
        title = if (received) "받은 편지" else "보낸 편지",
        preview = content.replace('\n', ' ').take(60),
        date = createdAt.toLetterDate(),
        received = received,
        content = ""
    )
}

private fun LetterDetailDto.toLetter(): Letter {
    val received = direction.equals("RECEIVED", ignoreCase = true)
    val normalized = content.trim()
    val firstLine = normalized.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    val title = firstLine.take(24).ifBlank {
        if (received) "받은 편지" else "보낸 편지"
    }
    return Letter(
        id = id,
        title = title,
        preview = normalized.replace('\n', ' ').take(60),
        date = createdAt.toLetterDate(),
        received = received,
        content = normalized
    )
}

private fun <T> com.apptive.slowtalk.data.remote.ApiEnvelope<T>.requireLetterData(): T =
    checkNotNull(data) { error?.message ?: "편지 응답 데이터가 없습니다." }

private fun String.toLetterDate(): String {
    val date = substringBefore('T').replace('-', '.')
    val time = substringAfter('T', "").take(5)
    return if (time.isBlank()) date else "$date · $time"
}
