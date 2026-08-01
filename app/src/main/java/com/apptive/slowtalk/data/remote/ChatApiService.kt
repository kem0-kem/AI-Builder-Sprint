package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ChatApiService {
    @GET("chat-rooms")
    suspend fun getChatRooms(): List<ChatRoomSummaryDto>

    @GET("chat-rooms/{chatRoomId}")
    suspend fun getChatRoom(@Path("chatRoomId") chatRoomId: Int): ChatRoomInfoDto

    @GET("chat-rooms/{chatRoomId}/messages")
    suspend fun getMessages(@Path("chatRoomId") chatRoomId: Int): List<ChatMessageDto>

    @POST("chat-rooms/{chatRoomId}/messages")
    suspend fun sendMessage(
        @Path("chatRoomId") chatRoomId: Int,
        @Body request: ChatMessageRequest
    ): ChatMessageSendResponse
}

@Serializable
data class ChatRoomSummaryDto(
    val chatRoomId: Int,
    val type: String,
    val roomName: String? = null,
    val lastMessage: String? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int = 0
)

@Serializable
data class ChatRoomInfoDto(
    val chatRoomId: Int,
    val type: String,
    val roomName: String? = null,
    val participantCount: Int? = null,
    val createdAt: String
)

@Serializable
data class ChatMessageDto(
    val messageId: Int,
    val type: String,
    val sender: String,
    val content: String,
    val createdAt: String
)

@Serializable
data class ChatMessageRequest(val content: String)

@Serializable
data class ChatMessageSendResponse(
    val messageId: Int,
    val createdAt: String
)
