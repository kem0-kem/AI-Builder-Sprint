package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface FeedApiService {
    @GET("feed-categories")
    suspend fun getCategories(): ApiEnvelope<List<FeedCategoryDto>>

    @GET("feeds")
    suspend fun getFeeds(@Query("scope") scope: String): ApiEnvelope<List<FeedDto>>

    @POST("feeds")
    suspend fun createFeed(@Body request: FeedCreateRequest): ApiEnvelope<FeedDto>

    @PATCH("feeds/{feedId}")
    suspend fun updateFeed(
        @Path("feedId") feedId: String,
        @Body request: FeedUpdateRequest
    ): ApiEnvelope<FeedDto>

    @DELETE("feeds/{feedId}")
    suspend fun deleteFeed(@Path("feedId") feedId: String): Response<Unit>

    @POST("feeds/{feedId}/comments")
    suspend fun createComment(
        @Path("feedId") feedId: String,
        @Body request: CommentContentRequest
    ): ApiEnvelope<CommentDto>

    @GET("feeds/{feedId}/comments")
    suspend fun getComments(@Path("feedId") feedId: String): ApiEnvelope<List<CommentDto>>

    @PATCH("comments/{commentId}")
    suspend fun updateComment(
        @Path("commentId") commentId: String,
        @Body request: CommentContentRequest
    ): ApiEnvelope<CommentDto>

    @DELETE("comments/{commentId}")
    suspend fun deleteComment(@Path("commentId") commentId: String): Response<Unit>
}

@Serializable
data class FeedDto(
    val id: String,
    val categoryId: String,
    val title: String,
    val content: String,
    val isMine: Boolean = false,
    val liked: Boolean = false,
    val commentCount: Int = 0
)

@Serializable
data class FeedCategoryDto(val id: String, val name: String)

@Serializable
data class FeedCreateRequest(val categoryId: String, val title: String, val content: String)

@Serializable
data class FeedUpdateRequest(val categoryId: String, val title: String, val content: String)

@Serializable
data class CommentContentRequest(val content: String, val parentCommentId: String? = null)

@Serializable
data class CommentDto(
    val id: String,
    val feedId: String,
    val parentCommentId: String? = null,
    val content: String,
    val isMine: Boolean = false,
    val createdAt: String
)
