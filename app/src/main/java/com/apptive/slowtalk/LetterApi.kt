package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.LetterDetailDto
import com.apptive.slowtalk.data.remote.LetterSummaryDto
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.requireData

object LetterApi {
    suspend fun getLetters(type: String? = null): Result<List<Letter>> = runCatching {
        RetrofitClient.letterApi.getLetters(type).requireData().map(LetterSummaryDto::toLetter)
    }

    suspend fun getLetter(letterId: String): Result<Letter> = runCatching {
        RetrofitClient.letterApi.getLetter(letterId).requireData().toLetter()
    }
}

private fun LetterSummaryDto.toLetter(): Letter {
    val received = direction.equals("RECEIVED", ignoreCase = true)
    return Letter(
        id = id,
        title = if (received) "받은 편지 #$id" else "보낸 편지 #$id",
        preview = content.replace('\n', ' ').take(60),
        date = createdAt.toLetterDate(),
        received = received,
        content = content
    )
}

private fun LetterDetailDto.toLetter(): Letter {
    val received = direction.equals("RECEIVED", ignoreCase = true)
    val normalized = content.trim()
    val firstLine = normalized.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    val title = firstLine.take(24).ifBlank {
        if (received) "받은 편지 #$id" else "보낸 편지 #$id"
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

private fun String.toLetterDate(): String {
    val date = substringBefore('T').replace('-', '.')
    val time = substringAfter('T', "").take(5)
    return if (time.isBlank()) date else "$date · $time"
}
