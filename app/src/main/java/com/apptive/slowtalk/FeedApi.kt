package com.apptive.slowtalk

import androidx.compose.ui.graphics.Color
import com.apptive.slowtalk.data.remote.FeedUpdateRequest
import com.apptive.slowtalk.data.remote.CommentContentRequest
import com.apptive.slowtalk.data.remote.CommentCreateRequest
import com.apptive.slowtalk.data.remote.FeedCreateRequest
import com.apptive.slowtalk.data.remote.FeedFeedbackRequest
import com.apptive.slowtalk.data.remote.FeedReportCreateRequest
import com.apptive.slowtalk.data.remote.FeedTimelineDto
import com.apptive.slowtalk.data.remote.FeedDetailCommentDto
import com.apptive.slowtalk.data.remote.CommentCreateResponse
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.apiData
import com.apptive.slowtalk.data.remote.apiModerated
import com.apptive.slowtalk.data.remote.apiUnit
import com.apptive.slowtalk.data.remote.requireResource
import com.apptive.slowtalk.data.remote.requireData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val summary: String,
    val hasWarning: Boolean,
    val warningMessage: String?,
    val tips: List<String>
)

data class FeedPageResult(
    val items: List<MyFeedResult>,
    val nextCursor: String?,
)

internal class FeedCategoryCatalog(
    private val loader: suspend () -> List<FeedCategoryResult>,
) {
    private val loadMutex = Mutex()
    private var cachedSnapshot: FeedCategorySnapshot? = null

    private suspend fun load(): FeedCategorySnapshot = loadMutex.withLock {
        cachedSnapshot?.let { return@withLock it }
        FeedCategorySnapshot(loader().associateBy(FeedCategoryResult::id)).also {
            cachedSnapshot = it
        }
    }

    suspend fun categories(): List<FeedCategoryResult> {
        return load().categories
    }

    suspend fun snapshotOrEmpty(): FeedCategorySnapshot = try {
        load()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        FeedCategorySnapshot.EMPTY
    }
}

internal class FeedCategorySnapshot(
    private val categoriesById: Map<String, FeedCategoryResult>,
) {
    val categories: List<FeedCategoryResult>
        get() = categoriesById.values.toList()

    fun toFeedPost(feed: FeedTimelineDto): FeedPost =
        feed.toFeedPost(categoriesById[feed.categoryId]?.name)

    fun categoryName(categoryId: String): String? = categoriesById[categoryId]?.name

    companion object {
        val EMPTY = FeedCategorySnapshot(emptyMap())
    }
}

object FeedApi {
    val isConfigured: Boolean = true
    private val categoryCatalog = FeedCategoryCatalog(::fetchCategories)

    private suspend fun fetchCategories(): List<FeedCategoryResult> =
        apiData { RetrofitClient.feedApi.getFeedCategories() }.map {
            FeedCategoryResult(it.id, appCategoryName(it.name))
        }

    suspend fun getFeedCategories(): Result<List<FeedCategoryResult>> = runCatching {
        categoryCatalog.categories()
    }

    suspend fun createFeed(
        categoryId: String,
        title: String,
        content: String
    ): Result<String> = runCatching {
        apiModerated(FeedTimelineDto.serializer()) {
            RetrofitClient.feedApi.createFeed(FeedCreateRequest(categoryId, title, content))
        }.requireResource().id
    }

    suspend fun getFeedFeedback(title: String, content: String): Result<FeedFeedbackResult> =
        runCatching {
            apiData {
                RetrofitClient.feedApi.getFeedFeedback(FeedFeedbackRequest(title, content))
            }.let {
                FeedFeedbackResult(
                    summary = it.summary,
                    hasWarning = false,
                    warningMessage = null,
                    tips = it.suggestions
                )
            }
        }

    suspend fun getMyFeeds(
        cursor: String? = null,
        limit: Int = 20,
        categoryId: String? = null,
    ): Result<FeedPageResult> = getFeeds("mine", cursor, limit, categoryId)

    suspend fun getFeeds(
        scope: String = "all",
        cursor: String? = null,
        limit: Int = 20,
        categoryId: String? = null,
    ): Result<FeedPageResult> = runCatching {
        val categorySnapshot = categoryCatalog.snapshotOrEmpty()
        val envelope = RetrofitClient.feedApi.getFeeds(scope, cursor, limit, categoryId)
        val items = envelope.requireData().map { item ->
            MyFeedResult(
                post = categorySnapshot.toFeedPost(item),
                liked = item.liked
            )
        }
        FeedPageResult(items, envelope.meta?.nextCursor)
    }

    suspend fun getFeedDetail(feedId: String): Result<FeedDetailResult> = runCatching {
        val categorySnapshot = categoryCatalog.snapshotOrEmpty()
        apiData { RetrofitClient.feedApi.getFeed(feedId) }.let { item ->
            val flatComments = apiData { RetrofitClient.feedApi.getComments(feedId) }
            val category = categorySnapshot.categoryName(item.categoryId)?.let(::appCategoryName) ?: "기타"
            FeedDetailResult(
                post = FeedPost(
                    id = item.id,
                    categoryId = item.categoryId,
                    category = category,
                    title = item.title,
                    body = item.content,
                    comments = flatComments.toUiComments(),
                    commentCount = item.commentCount,
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
        apiModerated(FeedTimelineDto.serializer()) {
            RetrofitClient.feedApi.updateFeed(
                feedId = feedId,
                request = FeedUpdateRequest(categoryId, title, content),
            )
        }.requireResource()
    }

    suspend fun deleteFeed(feedId: String): Result<Unit> = runCatching {
        apiUnit { RetrofitClient.feedApi.deleteFeed(feedId) }
    }

    suspend fun reportFeed(
        feedId: String,
        reason: String = "inappropriate content",
    ): Result<Unit> = runCatching {
        apiData { RetrofitClient.feedApi.reportFeed(feedId, FeedReportCreateRequest(reason)) }
        Unit
    }

    suspend fun setFeedLiked(feedId: String, liked: Boolean): Result<Boolean> = runCatching {
        if (liked) {
            apiData { RetrofitClient.feedApi.likeFeed(feedId) }.liked
        } else {
            apiData { RetrofitClient.feedApi.unlikeFeed(feedId) }.liked
        }
    }

    suspend fun createComment(
        feedId: String,
        content: String,
        parentCommentId: String? = null,
    ): Result<CommentCreateResponse> = runCatching {
        apiModerated(CommentCreateResponse.serializer()) {
            RetrofitClient.feedApi.createComment(feedId, CommentCreateRequest(content, parentCommentId))
        }.requireResource()
    }

    suspend fun updateComment(commentId: String, content: String): Result<CommentCreateResponse> =
        runCatching {
            apiModerated(CommentCreateResponse.serializer()) {
                RetrofitClient.feedApi.updateComment(
                    commentId,
                    CommentContentRequest(content),
                )
            }.requireResource()
        }

    suspend fun deleteComment(commentId: String): Result<Unit> = runCatching {
        apiUnit { RetrofitClient.feedApi.deleteComment(commentId) }
    }

    suspend fun reportComment(
        commentId: String,
        reason: String = "inappropriate content",
    ): Result<Unit> = runCatching {
        apiData { RetrofitClient.feedApi.reportComment(commentId, FeedReportCreateRequest(reason)) }
        Unit
    }
}

internal fun List<FeedDetailCommentDto>.toUiComments(): MutableList<Comment> {
    val modelsById = associate { comment ->
        comment.id to Comment(
            author = if (comment.isMine) "글쓴이" else "익명의 이웃",
            message = comment.content,
            time = "",
            isMine = comment.isMine,
            id = comment.id,
        )
    }
    return filter { it.parentCommentId == null }.map { root ->
        modelsById.getValue(root.id).copy(
            replies = filter { it.parentCommentId == root.id }
                .map { modelsById.getValue(it.id) },
        )
    }.toMutableList()
}

internal fun FeedTimelineDto.toFeedPost(categoryName: String?): FeedPost {
    val displayCategory = categoryName?.let(::appCategoryName) ?: "기타"
    return FeedPost(
        id = id,
        categoryId = categoryId,
        category = displayCategory,
        title = title,
        body = content,
        commentCount = commentCount,
        accent = categoryAccent(displayCategory),
        isMine = isMine,
    )
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
