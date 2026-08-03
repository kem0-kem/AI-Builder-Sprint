package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface FeedApiService {
    @GET("feeds")
    suspend fun getFeeds(@Query("scope") scope: String = "all"): ApiEnvelope<List<FeedItemDto>>

    @GET("feed-categories")
    suspend fun getFeedCategories(): ApiEnvelope<List<FeedWriteCategoryDto>>

    @POST("feeds")
    suspend fun createFeed(@Body request: FeedCreateRequest): ApiEnvelope<FeedItemDto>

    @POST("feeds/feedback")
    suspend fun getFeedFeedback(@Body request: FeedFeedbackRequest): ApiEnvelope<FeedFeedbackResponse>

    @GET("feeds/{feedId}")
    suspend fun getFeed(@Path("feedId") feedId: String): ApiEnvelope<FeedItemDto>

    @GET("feeds/{feedId}/comments")
    suspend fun getComments(@Path("feedId") feedId: String): ApiEnvelope<List<FeedCommentDto>>

    @PATCH("feeds/{feedId}")
    suspend fun updateFeed(
        @Path("feedId") feedId: String,
        @Body request: FeedUpdateRequest
    ): ApiEnvelope<FeedItemDto>

    @DELETE("feeds/{feedId}")
    suspend fun deleteFeed(@Path("feedId") feedId: String): Response<Unit>

    @POST("feeds/{feedId}/reports")
    suspend fun reportFeed(
        @Path("feedId") feedId: String,
        @Body request: FeedReportRequest
    ): ApiEnvelope<ReportedResponse>

    @PUT("feeds/{feedId}/like")
    suspend fun likeFeed(@Path("feedId") feedId: String): ApiEnvelope<FeedLikeResponse>

    @DELETE("feeds/{feedId}/like")
    suspend fun unlikeFeed(@Path("feedId") feedId: String): ApiEnvelope<FeedLikeResponse>

    @POST("feeds/{feedId}/comments")
    suspend fun createComment(
        @Path("feedId") feedId: String,
        @Body request: CommentContentRequest
    ): ApiEnvelope<FeedCommentDto>

    @PATCH("comments/{commentId}")
    suspend fun updateComment(
        @Path("commentId") commentId: String,
        @Body request: CommentPatchRequest
    ): ApiEnvelope<FeedCommentDto>

    @DELETE("comments/{commentId}")
    suspend fun deleteComment(@Path("commentId") commentId: String): Response<Unit>

    @POST("comments/{commentId}/reports")
    suspend fun reportComment(
        @Path("commentId") commentId: String,
        @Body request: FeedReportRequest
    ): ApiEnvelope<ReportedResponse>
}

@Serializable
data class FeedItemDto(
    val id: String,
    val categoryId: String,
    val title: String,
    val content: String,
    val liked: Boolean = false,
    val isMine: Boolean = false,
    val likeCount: Int = 0,
    val commentCount: Int = 0
)

@Serializable
data class FeedCommentDto(
    val id: String,
    val feedId: String,
    val parentCommentId: String? = null,
    val content: String,
    val isMine: Boolean = false,
    val createdAt: String = ""
)

@Serializable
data class FeedUpdateRequest(
    val categoryId: String,
    val title: String,
    val content: String
)

@Serializable
data class CommentContentRequest(
    val content: String,
    val parentCommentId: String? = null
)

@Serializable
data class CommentPatchRequest(val content: String)

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
data class FeedFeedbackRequest(
    val title: String,
    val content: String
)

@Serializable
data class FeedFeedbackResponse(
    val summary: String = "",
    val suggestions: List<String> = emptyList()
)

@Serializable
data class FeedReportRequest(val reason: String)

@Serializable
data class ReportedResponse(val reported: Boolean = true)
