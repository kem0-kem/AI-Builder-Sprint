package com.apptive.slowtalk

import androidx.compose.ui.graphics.Color
import com.apptive.slowtalk.data.remote.FeedUpdateRequest
import com.apptive.slowtalk.data.remote.CommentContentRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import retrofit2.Response

data class MyFeedResult(
    val post: FeedPost,
    val liked: Boolean
)

object FeedApi {
    val isConfigured: Boolean = true

    suspend fun getMyFeeds(): Result<List<MyFeedResult>> = runCatching {
        RetrofitClient.feedApi.getMyFeeds().map { item ->
            val category = appCategoryName(item.category.name)
            MyFeedResult(
                post = FeedPost(
                    id = item.feedId,
                    category = category,
                    title = item.title,
                    body = item.content,
                    accent = categoryAccent(category),
                    isMine = true
                ),
                liked = item.liked
            )
        }
    }

    suspend fun updateFeed(
        feedId: Int,
        categoryId: Int,
        title: String,
        content: String
    ): Result<Unit> = runCatching {
        RetrofitClient.feedApi.updateFeed(
            feedId = feedId,
            request = FeedUpdateRequest(categoryId, title, content)
        ).requireSuccess()
    }

    suspend fun deleteFeed(feedId: Int): Result<Unit> = runCatching {
        RetrofitClient.feedApi.deleteFeed(feedId).requireSuccess()
    }

    suspend fun reportFeed(feedId: Int): Result<Unit> = runCatching {
        RetrofitClient.feedApi.reportFeed(feedId).requireSuccess()
    }

    suspend fun createComment(feedId: Int, content: String): Result<Int> = runCatching {
        RetrofitClient.feedApi.createComment(
            feedId,
            CommentContentRequest(content)
        ).commentId
    }

    suspend fun updateComment(feedId: Int, commentId: Int, content: String): Result<Unit> =
        runCatching {
            RetrofitClient.feedApi.updateComment(
                feedId,
                commentId,
                CommentContentRequest(content)
            ).requireSuccess()
        }

    suspend fun deleteComment(feedId: Int, commentId: Int): Result<Unit> = runCatching {
        RetrofitClient.feedApi.deleteComment(feedId, commentId).requireSuccess()
    }

    suspend fun reportComment(feedId: Int, commentId: Int): Result<Unit> = runCatching {
        RetrofitClient.feedApi.reportComment(feedId, commentId).requireSuccess()
    }
}

private fun Response<Unit>.requireSuccess() {
    check(isSuccessful) { "피드 요청 실패 (${code()})" }
}

private fun appCategoryName(serverName: String): String = when (serverName.trim()) {
    "일상", "일상 이야기" -> "일상 이야기"
    "취미", "취미 생활" -> "취미 생활"
    "고민", "마음과 고민" -> "마음과 고민"
    "성장", "배움과 성장" -> "배움과 성장"
    "여행", "여행과 경험" -> "여행과 경험"
    else -> serverName.ifBlank { "기타" }
}

private fun categoryAccent(category: String): Color = when (category) {
    "마음과 고민" -> Color(0xFFEC7168)
    "취미 생활" -> Color(0xFF8A70D8)
    "배움과 성장" -> Color(0xFF5C95E8)
    "여행과 경험" -> Color(0xFF3DBCC1)
    else -> Purple
}

fun feedCategoryId(category: String): Int = when (category) {
    "일상 이야기" -> 2
    "취미 생활" -> 3
    "마음과 고민" -> 4
    "배움과 성장" -> 5
    "여행과 경험" -> 6
    else -> 7
}
