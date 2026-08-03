package com.apptive.slowtalk

import androidx.compose.ui.graphics.Color
import com.apptive.slowtalk.data.remote.FeedUpdateRequest
import com.apptive.slowtalk.data.remote.CommentContentRequest
import com.apptive.slowtalk.data.remote.FeedCreateRequest
import com.apptive.slowtalk.data.remote.FeedFeedbackRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.requireData
import retrofit2.Response

data class MyFeedResult(
    val post: FeedPost,
    val liked: Boolean
)

data class FeedDetailResult(
    val post: FeedPost,
    val liked: Boolean
)

data class FeedCategoryResult(val id: String, val name: String)

data class FeedFeedbackResult(
    val hasWarning: Boolean,
    val warningMessage: String?,
    val tips: List<String>
)

object FeedApi {
    val isConfigured: Boolean = true

    suspend fun getFeedCategories(): Result<List<FeedCategoryResult>> = runCatching {
        RetrofitClient.feedApi.getFeedCategories().requireData().map {
            FeedCategoryResult(it.id, appCategoryName(it.name))
        }
    }

    suspend fun createFeed(
        categoryId: String,
        title: String,
        content: String
    ): Result<String> = runCatching {
        RetrofitClient.feedApi.createFeed(
            FeedCreateRequest(categoryId, title, content)
        ).requireData().id
    }

    suspend fun getFeedFeedback(title: String, content: String): Result<FeedFeedbackResult> =
        runCatching {
            RetrofitClient.feedApi.getFeedFeedback(
                FeedFeedbackRequest(title, content)
            ).requireData().let {
                FeedFeedbackResult(
                    hasWarning = false,
                    warningMessage = null,
                    tips = it.suggestions
                )
            }
        }

    suspend fun getMyFeeds(): Result<List<MyFeedResult>> = runCatching {
        RetrofitClient.feedApi.getMyFeeds().requireData().map { item ->
            val category = item.categoryId
            MyFeedResult(
                post = FeedPost(
                    id = item.id,
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

    suspend fun getFeeds(): Result<List<MyFeedResult>> = runCatching {
        RetrofitClient.feedApi.getFeeds().requireData().map { item ->
            val category = item.categoryId
            MyFeedResult(
                post = FeedPost(
                    id = item.id,
                    category = category,
                    title = item.title,
                    body = item.content,
                    accent = categoryAccent(category),
                    isMine = item.isMine
                ),
                liked = item.liked
            )
        }
    }

    suspend fun getFeedDetail(feedId: String): Result<FeedDetailResult> = runCatching {
        RetrofitClient.feedApi.getFeed(feedId).requireData().let { item ->
            val category = item.categoryId
            FeedDetailResult(
                post = FeedPost(
                    id = item.id,
                    category = category,
                    title = item.title,
                    body = item.content,
                    comments = item.comments.map { comment ->
                        Comment(
                            author = if (comment.isMine) "글쓴이" else "익명의 이웃",
                            message = comment.content,
                            time = "",
                            isMine = comment.isMine,
                            id = comment.id
                        )
                    }.toMutableList(),
                    accent = categoryAccent(category),
                    isMine = item.isMine
                ),
                liked = item.liked
            )
        }
    }

    suspend fun updateFeed(
        feedId: String,
        categoryId: String,
        title: String,
        content: String
    ): Result<Unit> = runCatching {
        RetrofitClient.feedApi.updateFeed(
            feedId = feedId,
            request = FeedUpdateRequest(categoryId, title, content)
        ).requireData()
    }

    suspend fun deleteFeed(feedId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.deleteFeed(feedId).requireSuccess()
    }

    suspend fun reportFeed(feedId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.reportFeed(feedId).requireData()
    }

    suspend fun setFeedLiked(feedId: String, liked: Boolean): Result<Boolean> = runCatching {
        if (liked) {
            RetrofitClient.feedApi.likeFeed(feedId).requireData().liked
        } else {
            RetrofitClient.feedApi.unlikeFeed(feedId).requireData().liked
        }
    }

    suspend fun createComment(feedId: String, content: String): Result<String> = runCatching {
        RetrofitClient.feedApi.createComment(
            feedId,
            CommentContentRequest(content)
        ).requireData().id
    }

    suspend fun updateComment(feedId: String, commentId: String, content: String): Result<Unit> =
        runCatching {
            RetrofitClient.feedApi.updateComment(
                feedId,
                commentId,
                CommentContentRequest(content)
            ).requireData()
        }

    suspend fun deleteComment(feedId: String, commentId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.deleteComment(feedId, commentId).requireSuccess()
    }

    suspend fun reportComment(feedId: String, commentId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.reportComment(feedId, commentId).requireData()
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

fun feedCategoryId(category: String): String = category
