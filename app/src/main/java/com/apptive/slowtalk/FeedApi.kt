package com.apptive.slowtalk

import androidx.compose.ui.graphics.Color
import com.apptive.slowtalk.data.remote.FeedUpdateRequest
import com.apptive.slowtalk.data.remote.CommentContentRequest
import com.apptive.slowtalk.data.remote.FeedCreateRequest
import com.apptive.slowtalk.data.remote.FeedFeedbackRequest
import com.apptive.slowtalk.data.remote.FeedTimelineDto
import com.apptive.slowtalk.data.remote.CommentCreateResponse
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.apiData
import com.apptive.slowtalk.data.remote.apiModerated
import com.apptive.slowtalk.data.remote.apiUnit
import com.apptive.slowtalk.data.remote.requireResource
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
    val hasWarning: Boolean,
    val warningMessage: String?,
    val tips: List<String>
)

internal class FeedCategoryCatalog(
    private val loader: suspend () -> List<FeedCategoryResult>,
) {
    private val loadMutex = Mutex()
    private var loadAttempted = false
    private var categoriesById: Map<String, FeedCategoryResult> = emptyMap()

    suspend fun warmUp() {
        if (loadAttempted) return
        loadMutex.withLock {
            if (loadAttempted) return
            categoriesById = try {
                loader().associateBy(FeedCategoryResult::id)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                emptyMap()
            }
            loadAttempted = true
        }
    }

    suspend fun categories(): List<FeedCategoryResult> {
        warmUp()
        return categoriesById.values.toList()
    }

    suspend fun toFeedPost(feed: FeedTimelineDto): FeedPost {
        warmUp()
        return feed.toFeedPost(categoriesById[feed.categoryId]?.name)
    }

    suspend fun categoryName(categoryId: String): String? {
        warmUp()
        return categoriesById[categoryId]?.name
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
                    hasWarning = false,
                    warningMessage = null,
                    tips = it.suggestions
                )
            }
        }

    suspend fun getMyFeeds(): Result<List<MyFeedResult>> = runCatching {
        categoryCatalog.warmUp()
        apiData { RetrofitClient.feedApi.getMyFeeds() }.map { item ->
            MyFeedResult(
                post = categoryCatalog.toFeedPost(item).copy(isMine = true),
                liked = item.liked
            )
        }
    }

    suspend fun getFeeds(): Result<List<MyFeedResult>> = runCatching {
        categoryCatalog.warmUp()
        apiData { RetrofitClient.feedApi.getFeeds() }.map { item ->
            MyFeedResult(
                post = categoryCatalog.toFeedPost(item),
                liked = item.liked
            )
        }
    }

    suspend fun getFeedDetail(feedId: String): Result<FeedDetailResult> = runCatching {
        categoryCatalog.warmUp()
        apiData { RetrofitClient.feedApi.getFeed(feedId) }.let { item ->
            val category = categoryCatalog.categoryName(item.categoryId)?.let(::appCategoryName) ?: "기타"
            FeedDetailResult(
                post = FeedPost(
                    id = item.id,
                    categoryId = item.categoryId,
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

    suspend fun reportFeed(feedId: String): Result<Unit> = runCatching {
        apiData { RetrofitClient.feedApi.reportFeed(feedId) }
        Unit
    }

    suspend fun setFeedLiked(feedId: String, liked: Boolean): Result<Boolean> = runCatching {
        if (liked) {
            apiData { RetrofitClient.feedApi.likeFeed(feedId) }.liked
        } else {
            apiData { RetrofitClient.feedApi.unlikeFeed(feedId) }.liked
        }
    }

    suspend fun createComment(feedId: String, content: String): Result<String> = runCatching {
        apiModerated(CommentCreateResponse.serializer()) {
            RetrofitClient.feedApi.createComment(feedId, CommentContentRequest(content))
        }.requireResource().id
    }

    suspend fun updateComment(feedId: String, commentId: String, content: String): Result<Unit> =
        runCatching {
            apiModerated(CommentCreateResponse.serializer()) {
                RetrofitClient.feedApi.updateComment(
                    feedId,
                    commentId,
                    CommentContentRequest(content),
                )
            }.requireResource()
        }

    suspend fun deleteComment(feedId: String, commentId: String): Result<Unit> = runCatching {
        apiUnit { RetrofitClient.feedApi.deleteComment(feedId, commentId) }
    }

    suspend fun reportComment(feedId: String, commentId: String): Result<Unit> = runCatching {
        apiData { RetrofitClient.feedApi.reportComment(feedId, commentId) }
        Unit
    }
}

internal fun FeedTimelineDto.toFeedPost(categoryName: String?): FeedPost {
    val displayCategory = categoryName?.let(::appCategoryName) ?: "기타"
    return FeedPost(
        id = id,
        categoryId = categoryId,
        category = displayCategory,
        title = title,
        body = content,
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
