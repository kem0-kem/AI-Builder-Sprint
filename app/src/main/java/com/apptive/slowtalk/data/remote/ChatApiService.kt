package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface ChatApiService {
    @GET("chat-rooms")
    suspend fun getChatRooms(): ApiEnvelope<List<ChatRoomSummaryDto>>

    @POST("comments/{commentId}/chat-room")
    suspend fun createFromComment(
        @Path("commentId") commentId: String,
    ): ApiEnvelope<ChatRoomInfoDto>

    @GET("chat-rooms/{chatRoomId}")
    suspend fun getChatRoom(@Path("chatRoomId") chatRoomId: String): ApiEnvelope<ChatRoomInfoDto>

    @GET("chat-rooms/{chatRoomId}/messages")
    suspend fun getMessages(@Path("chatRoomId") chatRoomId: String): ApiEnvelope<List<ChatMessageDto>>

    @POST("chat-rooms/{chatRoomId}/messages")
    suspend fun sendMessage(
        @Path("chatRoomId") chatRoomId: String,
        @Body request: ChatMessageRequest
    ): Response<ApiEnvelope<JsonElement>>

    @PATCH("chat-rooms/{chatRoomId}/read")
    suspend fun markAsRead(
        @Path("chatRoomId") chatRoomId: String,
        @Body request: ChatReadRequest
    ): ApiEnvelope<ChatReadResponse>
}

@Serializable
data class ChatRoomSummaryDto(
    val id: String,
    val type: String,
    val name: String? = null,
    val createdAt: String,
)

@Serializable
data class ChatRoomInfoDto(
    val id: String,
    val type: String,
    val name: String? = null,
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val clientMessageId: String? = null,
    val type: String,
    val sender: ChatSenderDto,
    val content: String,
    val createdAt: String
)

@Serializable
data class ChatSenderDto(
    val displayName: String,
    val isMe: Boolean,
)

@Serializable
data class ChatMessageRequest(
    val clientMessageId: String,
    val content: String,
)

@Serializable
data class ChatReadRequest(val lastReadMessageId: String)

@Serializable
data class ChatReadResponse(
    val lastReadMessageId: String,
    val unreadCount: Int
)
