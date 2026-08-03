package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface FeedApiService {
    @GET("feeds")
    suspend fun getFeeds(): ApiEnvelope<List<FeedTimelineDto>>

    @GET("feed-categories")
    suspend fun getFeedCategories(): ApiEnvelope<List<FeedWriteCategoryDto>>

    @POST("feeds")
    suspend fun createFeed(@Body request: FeedCreateRequest): Response<ApiEnvelope<JsonElement>>

    @POST("feeds/feedback")
    suspend fun getFeedFeedback(@Body request: FeedFeedbackRequest): ApiEnvelope<FeedFeedbackResponse>

    @GET("feeds/mine")
    suspend fun getMyFeeds(): ApiEnvelope<List<FeedTimelineDto>>

    @GET("feeds/{feedId}")
    suspend fun getFeed(@Path("feedId") feedId: String): ApiEnvelope<FeedDetailDto>

    @PATCH("feeds/{feedId}")
    suspend fun updateFeed(
        @Path("feedId") feedId: String,
        @Body request: FeedUpdateRequest
    ): Response<ApiEnvelope<JsonElement>>

    @DELETE("feeds/{feedId}")
    suspend fun deleteFeed(@Path("feedId") feedId: String): Response<Unit>

    @POST("feeds/{feedId}/report")
    suspend fun reportFeed(@Path("feedId") feedId: String): ApiEnvelope<ReportAcceptedDto>

    @POST("feeds/{feedId}/like")
    suspend fun likeFeed(@Path("feedId") feedId: String): ApiEnvelope<FeedLikeResponse>

    @DELETE("feeds/{feedId}/like")
    suspend fun unlikeFeed(@Path("feedId") feedId: String): ApiEnvelope<FeedLikeResponse>

    @POST("feeds/{feedId}/comments")
    suspend fun createComment(
        @Path("feedId") feedId: String,
        @Body request: CommentContentRequest
    ): Response<ApiEnvelope<JsonElement>>

    @PATCH("feeds/{feedId}/comments/{commentId}")
    suspend fun updateComment(
        @Path("feedId") feedId: String,
        @Path("commentId") commentId: String,
        @Body request: CommentContentRequest
    ): Response<ApiEnvelope<JsonElement>>

    @DELETE("feeds/{feedId}/comments/{commentId}")
    suspend fun deleteComment(
        @Path("feedId") feedId: String,
        @Path("commentId") commentId: String
    ): Response<Unit>

    @POST("feeds/{feedId}/comments/{commentId}/report")
    suspend fun reportComment(
        @Path("feedId") feedId: String,
        @Path("commentId") commentId: String
    ): ApiEnvelope<ReportAcceptedDto>
}

@Serializable
data class FeedTimelineDto(
    val id: String,
    val categoryId: String,
    val title: String,
    val content: String,
    val isMine: Boolean = false,
    val liked: Boolean = false,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class FeedCategoryDto(
    val id: String,
    val name: String
)

@Serializable
data class FeedDetailDto(
    val id: String,
    val categoryId: String,
    val title: String,
    val content: String,
    val liked: Boolean = false,
    val isMine: Boolean = false,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val createdAt: String,
    val updatedAt: String,
    val comments: List<FeedDetailCommentDto> = emptyList(),
)

@Serializable
data class FeedDetailCommentDto(
    val id: String,
    val feedId: String,
    val parentCommentId: String? = null,
    val content: String,
    val isMine: Boolean = false,
    val createdAt: String,
)

@Serializable
data class FeedUpdateRequest(
    val categoryId: String,
    val title: String,
    val content: String
)

@Serializable
data class CommentContentRequest(val content: String)

@Serializable
data class CommentCreateResponse(
    val id: String,
    val feedId: String,
    val parentCommentId: String? = null,
    val content: String,
    val isMine: Boolean,
    val createdAt: String,
)

@Serializable
data class FeedLikeResponse(val liked: Boolean)

@Serializable
data class FeedWriteCategoryDto(
    val id: String,
    val name: String
)

@Serializable
data class FeedCreateRequest(
    val categoryId: String,
    val title: String,
    val content: String
)

@Serializable
data class ReportAcceptedDto(val reported: Boolean)

@Serializable
data class FeedFeedbackRequest(
    val title: String,
    val content: String
)

@Serializable
data class FeedFeedbackResponse(
    val summary: String,
    val suggestions: List<String>,
)
