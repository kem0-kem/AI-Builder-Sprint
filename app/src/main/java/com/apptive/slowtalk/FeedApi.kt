package com.apptive.slowtalk

import androidx.compose.ui.graphics.Color
import com.apptive.slowtalk.data.remote.CommentContentRequest
import com.apptive.slowtalk.data.remote.CommentDto
import com.apptive.slowtalk.data.remote.FeedCreateRequest
import com.apptive.slowtalk.data.remote.FeedDto
import com.apptive.slowtalk.data.remote.FeedUpdateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import retrofit2.Response

data class FeedResult(val post: FeedPost, val liked: Boolean)
typealias MyFeedResult = FeedResult

object FeedApi {
    val isConfigured: Boolean = true
    private val feedIds = mutableMapOf<Int, String>()
    private val commentIds = mutableMapOf<Int, String>()

    suspend fun getMyFeeds(): Result<List<FeedResult>> = getFeeds("mine")
    suspend fun getFeeds(scope: String): Result<List<FeedResult>> = runCatching {
        val response = RetrofitClient.feedApi.getFeeds(scope)
        check(response.ok) { "피드를 불러오지 못했습니다." }
        response.data.orEmpty().map(::toFeedResult)
    }

    suspend fun createFeed(category: String, title: String, content: String): Result<FeedResult> = runCatching {
        val categoryId = RetrofitClient.feedApi.getCategories().data.orEmpty().firstOrNull()?.id
            ?: error("피드 카테고리를 찾을 수 없습니다.")
        val response = RetrofitClient.feedApi.createFeed(FeedCreateRequest(categoryId, title, content))
        check(response.ok && response.data != null) { "피드를 작성하지 못했습니다." }
        toFeedResult(response.data)
    }

    suspend fun updateFeed(feedId: String, categoryId: String, title: String, content: String): Result<FeedResult> = runCatching {
        val response = RetrofitClient.feedApi.updateFeed(feedId, FeedUpdateRequest(categoryId, title, content))
        check(response.ok && response.data != null) { "피드를 수정하지 못했습니다." }
        toFeedResult(response.data)
    }

    suspend fun deleteFeed(feedId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.deleteFeed(feedId).requireSuccess()
    }

    suspend fun getComments(feedId: String): Result<List<Comment>> = runCatching {
        val response = RetrofitClient.feedApi.getComments(feedId)
        check(response.ok) { "댓글을 불러오지 못했습니다." }
        commentsFrom(response.data.orEmpty())
    }

    suspend fun createComment(feedId: String, content: String, parentCommentId: String? = null): Result<Comment> = runCatching {
        val response = RetrofitClient.feedApi.createComment(feedId, CommentContentRequest(content, parentCommentId))
        check(response.ok && response.data != null) { "댓글을 작성하지 못했습니다." }
        commentFrom(response.data)
    }

    suspend fun createCommentByLocalId(feedId: Int, content: String): Result<Int> = runCatching {
        val remoteFeedId = feedIds[feedId] ?: error("피드 UUID가 없습니다.")
        val created = createComment(remoteFeedId, content).getOrThrow()
        val remoteCommentId = created.id ?: error("댓글 UUID가 없습니다.")
        commentIds[remoteCommentId.hashCode()] = remoteCommentId
        remoteCommentId.hashCode()
    }

    fun commentRemoteId(localId: Int): String? = commentIds[localId]

    suspend fun updateComment(commentId: String, content: String): Result<Comment> = runCatching {
        val response = RetrofitClient.feedApi.updateComment(commentId, CommentContentRequest(content))
        check(response.ok && response.data != null) { "댓글을 수정하지 못했습니다." }
        commentFrom(response.data)
    }

    suspend fun deleteComment(commentId: String): Result<Unit> = runCatching {
        RetrofitClient.feedApi.deleteComment(commentId).requireSuccess()
    }

    // Temporary source-compatible overloads for the legacy in-memory UI. Every
    // persisted operation below is migrated to the UUID overloads with its feed.
    suspend fun updateFeed(feedId: Int, categoryId: Int, title: String, content: String): Result<Unit> =
        Result.failure(IllegalStateException("피드 UUID가 없습니다."))

    suspend fun deleteFeed(feedId: Int): Result<Unit> =
        Result.failure(IllegalStateException("피드 UUID가 없습니다."))

    suspend fun reportFeed(feedId: Int): Result<Unit> =
        Result.failure(IllegalStateException("피드 UUID가 없습니다."))

    suspend fun createComment(feedId: Int, content: String): Result<Int> =
        Result.failure(IllegalStateException("피드 UUID가 없습니다."))

    suspend fun updateComment(feedId: Int, commentId: String, content: String): Result<Unit> =
        updateComment(commentId, content).map { }

    suspend fun deleteComment(feedId: Int, commentId: String): Result<Unit> = deleteComment(commentId)

    suspend fun reportComment(feedId: Int, commentId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("댓글 신고는 아직 연결되지 않았습니다."))

    private fun toFeedResult(item: FeedDto): FeedResult {
        feedIds[item.id.hashCode()] = item.id
        val category = "피드"
        return FeedResult(
            FeedPost(
                id = item.id.hashCode(), category = category, title = item.title, body = item.content,
                accent = categoryAccent(category), isMine = item.isMine, remoteId = item.id,
                categoryId = item.categoryId
            ),
            item.liked
        )
    }

    private fun commentsFrom(items: List<CommentDto>): List<Comment> {
        val roots = items.filter { it.parentCommentId == null }
        return roots.map { root ->
            commentFrom(root).copy(replies = items.filter { it.parentCommentId == root.id }.map(::commentFrom))
        }
    }

    private fun commentFrom(item: CommentDto) = Comment(
        author = if (item.isMine) "나" else "익명의 이웃",
        message = item.content,
        time = item.createdAt,
        isMine = item.isMine,
        id = item.id
    )
}

private fun Response<Unit>.requireSuccess() {
    check(isSuccessful) { "서버 요청 실패 (${code()})" }
}

private fun categoryAccent(category: String): Color = when (category) {
    else -> Purple
}

fun feedCategoryId(category: String): Int = 0
