package com.apptive.slowtalk

import androidx.compose.ui.graphics.Color
import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.CommentContentRequest
import com.apptive.slowtalk.data.remote.CommentPatchRequest
import com.apptive.slowtalk.data.remote.FeedCreateRequest
import com.apptive.slowtalk.data.remote.FeedFeedbackRequest
import com.apptive.slowtalk.data.remote.FeedReportRequest
import com.apptive.slowtalk.data.remote.FeedUpdateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import retrofit2.Response

data class MyFeedResult(val post: FeedPost, val liked: Boolean)

data class FeedDetailResult(val post: FeedPost, val liked: Boolean)

data class FeedCategoryResult(val id: String, val name: String)

data class FeedFeedbackResult(
    val hasWarning: Boolean,
    val warningMessage: String?,
    val tips: List<String>
)

object FeedApi {
    val isConfigured: Boolean = true

    suspend fun getFeedCategories(): Result<List<FeedCategoryResult>> = runCatching {
        RetrofitClient.feedApi.getFeedCategories().requireFeedData().map {
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
        ).requireFeedData().id
    }

    suspend fun getFeedFeedback(title: String, content: String): Result<FeedFeedbackResult> =
        runCatching {
            RetrofitClient.feedApi.getFeedFeedback(
                FeedFeedbackRequest(title, content)
            ).requireFeedData().let {
                FeedFeedbackResult(
                    hasWarning = false,
                    warningMessage = it.summary.ifBlank { null },
                    tips = it.suggestions
                )
            }
        }

    suspend fun getMyFeeds(): Result<List<MyFeedResult>> = loadFeeds("mine")

    suspend fun getFeeds(): Result<List<MyFeedResult>> = loadFeeds("all")

    private suspend fun loadFeeds(scope: String): Result<List<MyFeedResult>> = runCatching {
        val categories = getCategoryNameMap()
        RetrofitClient.feedApi.getFeeds(scope).requireFeedData().map { item ->
            val category = appCategoryName(categories[item.categoryId].orEmpty())
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
        val categories = getCategoryNameMap()
        val item = RetrofitClient.feedApi.getFeed(feedId).requireFeedData()
        val flatComments = RetrofitClient.feedApi.getComments(feedId).requireFeedData()
        val commentById = flatComments.associateBy { it.id }
        val repliesByParent = flatComments.filter { it.parentCommentId != null }
            .groupBy { it.parentCommentId }
        val comments = flatComments.filter { it.parentCommentId == null }.map { root ->
            Comment(
                author = if (root.isMine) "글쓴이" else "익명",
                message = root.content,
                time = "",
                isMine = root.isMine,
                id = root.id,
                replies = repliesByParent[root.id].orEmpty().map { reply ->
                    Comment(
                        author = if (reply.isMine) "글쓴이" else "익명",
                        message = reply.content,
                        time = "",
                        isMine = reply.isMine,
                        id = reply.id
                    )
                }
            )
        }.toMutableList()
        check(commentById.size == flatComments.size)
        val category = appCategoryName(categories[item.categoryId].orEmpty())
        FeedDetailResult(
            post = FeedPost(
                id = item.id,
                category = category,
                title = item.title,
                body = item.content,
                comments = comments,
                accent = categoryAccent(category),
                isMine = item.isMine
            ),
            liked = item.liked
        )
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
        ).requireFeedData()
    }

    suspend fun deleteFeed(feedId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.deleteFeed(feedId).requireSuccess()
    }

    suspend fun reportFeed(feedId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.reportFeed(feedId, FeedReportRequest("사용자 신고"))
            .requireFeedData()
    }

    suspend fun setFeedLiked(feedId: String, liked: Boolean): Result<Boolean> = runCatching {
        if (liked) {
            RetrofitClient.feedApi.likeFeed(feedId).requireFeedData().liked
        } else {
            RetrofitClient.feedApi.unlikeFeed(feedId).requireFeedData().liked
        }
    }

    suspend fun createComment(
        feedId: String,
        content: String,
        parentCommentId: String? = null
    ): Result<String> = runCatching {
        RetrofitClient.feedApi.createComment(
            feedId,
            CommentContentRequest(content, parentCommentId)
        ).requireFeedData().id
    }

    suspend fun updateComment(feedId: String, commentId: String, content: String): Result<Unit> =
        runCatching {
            RetrofitClient.feedApi.updateComment(commentId, CommentPatchRequest(content))
                .requireFeedData()
        }

    suspend fun deleteComment(feedId: String, commentId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.deleteComment(commentId).requireSuccess()
    }

    suspend fun reportComment(feedId: String, commentId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.reportComment(commentId, FeedReportRequest("사용자 신고"))
            .requireFeedData()
    }

    private suspend fun getCategoryNameMap(): Map<String, String> =
        RetrofitClient.feedApi.getFeedCategories().requireFeedData().associate { it.id to it.name }
}

private fun <T> ApiEnvelope<T>.requireFeedData(): T {
    check(ok) { error?.message ?: "피드 요청에 실패했습니다." }
    return checkNotNull(data) { "피드 응답 데이터가 없습니다." }
}

private fun Response<Unit>.requireSuccess() {
    check(isSuccessful) { "피드 요청 실패 (${code()})" }
}

private fun appCategoryName(serverName: String): String = when (serverName.trim()) {
    "일상", "일상 이야기" -> "일상 이야기"
    "취미", "취미 생활" -> "취미 생활"
    "고민", "위로", "마음과 고민" -> "마음과 고민"
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
