package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface ChatApiService {
    @GET("chat-rooms")
    suspend fun getChatRooms(): ApiEnvelope<List<ChatRoomSummaryDto>>

    @GET("chat-rooms/{roomId}")
    suspend fun getChatRoom(@Path("roomId") roomId: String): ApiEnvelope<ChatRoomInfoDto>

    @GET("chat-rooms/{roomId}/messages")
    suspend fun getMessages(@Path("roomId") roomId: String): ApiEnvelope<List<ChatMessageDto>>

    @POST("chat-rooms/{roomId}/messages")
    suspend fun sendMessage(
        @Path("roomId") roomId: String,
        @Body request: ChatMessageRequest
    ): ApiEnvelope<ChatMessageDto>

    @PATCH("chat-rooms/{roomId}/read")
    suspend fun markAsRead(
        @Path("roomId") roomId: String,
        @Body request: ChatReadRequest
    ): ApiEnvelope<ChatReadResponse>

    @POST("comments/{commentId}/chat-room")
    suspend fun openCommentAuthorChat(
        @Path("commentId") commentId: String
    ): ApiEnvelope<ChatRoomInfoDto>
}

@Serializable
data class ChatRoomSummaryDto(
    val id: String,
    val type: String,
    val name: String? = null,
    val roomName: String? = null,
    val lastMessage: String? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int = 0,
    val participantCount: Int? = null,
    val createdAt: String = ""
)

@Serializable
data class ChatRoomInfoDto(
    val id: String,
    val type: String,
    val name: String? = null
)

@Serializable
data class ChatSenderDto(
    val displayName: String,
    val isMe: Boolean
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val type: String = "CHAT",
    val sender: ChatSenderDto,
    val content: String,
    val createdAt: String
)

@Serializable
data class ChatMessageRequest(
    val clientMessageId: String,
    val content: String
)

@Serializable
data class ChatReadRequest(val lastReadMessageId: String)

@Serializable
data class ChatReadResponse(
    val lastReadMessageId: String,
    val unreadCount: Int
)
