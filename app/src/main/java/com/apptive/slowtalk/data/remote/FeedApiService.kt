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
    @GET("feeds")
    suspend fun getMyFeeds(@Query("scope") scope: String = "mine"): ApiEnvelope<List<MyFeedDto>>

    @PATCH("feeds/{feedId}")
    suspend fun updateFeed(
        @Path("feedId") feedId: Int,
        @Body request: FeedUpdateRequest
    ): Response<Unit>

    @DELETE("feeds/{feedId}")
    suspend fun deleteFeed(@Path("feedId") feedId: Int): Response<Unit>

    @POST("feeds/{feedId}/report")
    suspend fun reportFeed(@Path("feedId") feedId: Int): Response<Unit>

    @POST("feeds/{feedId}/comments")
    suspend fun createComment(
        @Path("feedId") feedId: Int,
        @Body request: CommentContentRequest
    ): CommentCreateResponse

    @PATCH("feeds/{feedId}/comments/{commentId}")
    suspend fun updateComment(
        @Path("feedId") feedId: Int,
        @Path("commentId") commentId: Int,
        @Body request: CommentContentRequest
    ): Response<Unit>

    @DELETE("feeds/{feedId}/comments/{commentId}")
    suspend fun deleteComment(
        @Path("feedId") feedId: Int,
        @Path("commentId") commentId: Int
    ): Response<Unit>

    @POST("feeds/{feedId}/comments/{commentId}/report")
    suspend fun reportComment(
        @Path("feedId") feedId: Int,
        @Path("commentId") commentId: Int
    ): Response<Unit>
}

@Serializable
data class MyFeedDto(
    val id: String,
    val categoryId: String,
    val title: String,
    val content: String,
    val liked: Boolean = false
)

@Serializable
data class FeedCategoryDto(
    val id: Int,
    val name: String
)

@Serializable
data class FeedUpdateRequest(
    val categoryId: Int,
    val title: String,
    val content: String
)

@Serializable
data class CommentContentRequest(val content: String)

@Serializable
data class CommentCreateResponse(val commentId: Int)
