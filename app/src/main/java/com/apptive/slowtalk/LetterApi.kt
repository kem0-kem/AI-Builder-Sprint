package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.LetterDetailDto
import com.apptive.slowtalk.data.remote.LetterSummaryDto
import com.apptive.slowtalk.data.remote.RetrofitClient

object LetterApi {
    suspend fun getLetters(type: String? = null): Result<List<Letter>> = runCatching {
        RetrofitClient.letterApi.getLetters(type).map(LetterSummaryDto::toLetter)
    }

    suspend fun getLetter(letterId: Int): Result<Letter> = runCatching {
        RetrofitClient.letterApi.getLetter(letterId).toLetter()
    }
}

private fun LetterSummaryDto.toLetter(): Letter {
    val received = type.equals("RECEIVED", ignoreCase = true)
    return Letter(
        id = letterId,
        title = if (received) "받은 편지 #$letterId" else "보낸 편지 #$letterId",
        preview = "편지를 눌러 내용을 확인해보세요.",
        date = createdAt.toLetterDate(),
        received = received,
        content = ""
    )
}

private fun LetterDetailDto.toLetter(): Letter {
    val received = type.equals("RECEIVED", ignoreCase = true)
    val normalized = content.trim()
    val firstLine = normalized.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    val title = firstLine.take(24).ifBlank {
        if (received) "받은 편지 #$letterId" else "보낸 편지 #$letterId"
    }
    return Letter(
        id = letterId,
        title = title,
        preview = normalized.replace('\n', ' ').take(60),
        date = createdAt.toLetterDate(),
        received = received,
        content = normalized
    )
}

private fun String.toLetterDate(): String {
    val date = substringBefore('T').replace('-', '.')
    val time = substringAfter('T', "").take(5)
    return if (time.isBlank()) date else "$date · $time"
}
