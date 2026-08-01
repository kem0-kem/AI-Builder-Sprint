package com.apptive.slowtalk

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object FeedApi {
    val isConfigured: Boolean
        get() = BuildConfig.API_BASE_URL.isNotBlank()

    suspend fun updateFeed(
        feedId: Int,
        categoryId: Int,
        title: String,
        content: String
    ): Result<Unit> = request(
        method = "PATCH",
        path = "/feeds/$feedId",
        body = JSONObject()
            .put("categoryId", categoryId)
            .put("title", title)
            .put("content", content)
    )

    suspend fun deleteFeed(feedId: Int): Result<Unit> =
        request(method = "DELETE", path = "/feeds/$feedId")

    suspend fun reportFeed(feedId: Int): Result<Unit> =
        request(method = "POST", path = "/feeds/$feedId/report")

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = BuildConfig.API_BASE_URL.trim().trimEnd('/')
            check(baseUrl.isNotEmpty()) { "API_BASE_URL이 설정되지 않았습니다." }

            val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                if (BuildConfig.API_AUTH_TOKEN.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${BuildConfig.API_AUTH_TOKEN}")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(body.toString())
                    }
                }
            }

            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    val errorText = connection.errorStream
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    error("피드 요청 실패 ($status)${if (errorText.isBlank()) "" else ": $errorText"}")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

fun feedCategoryId(category: String): Int = when (category) {
    "일상 이야기" -> 1
    "취미 생활" -> 2
    "마음과 고민" -> 3
    "배움과 성장" -> 4
    "여행과 경험" -> 5
    else -> 6
}
