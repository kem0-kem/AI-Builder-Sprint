package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ChatApiService {
    @GET("chat-rooms")
    suspend fun getRooms(): ApiEnvelope<List<ChatRoomDto>>

    @GET("chat-rooms/{roomId}/messages")
    suspend fun getMessages(@Path("roomId") roomId: String): ApiEnvelope<List<ChatMessageDto>>

    @POST("chat-rooms/{roomId}/messages")
    suspend fun createMessage(
        @Path("roomId") roomId: String,
        @Body request: ChatMessageCreateRequest
    ): ApiEnvelope<ChatMessageDto>

    @DELETE("chat-rooms/{roomId}")
    suspend fun leaveRoom(@Path("roomId") roomId: String): Response<Unit>

    @GET("meeting-invite-candidates")
    suspend fun getInviteCandidates(): ApiEnvelope<List<InviteCandidateDto>>

    @POST("meetings")
    suspend fun createMeeting(@Body request: MeetingCreateRequest): ApiEnvelope<MeetingDto>
}

@Serializable
data class ChatRoomDto(val id: String, val type: String, val name: String? = null, val createdAt: String)

@Serializable
data class ChatMessageDto(
    val id: String,
    val content: String,
    val createdAt: String,
    val sender: ChatSenderDto
)

@Serializable
data class ChatSenderDto(val displayName: String, val isMe: Boolean)

@Serializable
data class ChatMessageCreateRequest(val clientMessageId: String, val content: String)

@Serializable
data class InviteCandidateDto(val candidateId: String, val displayName: String)

@Serializable
data class MeetingCreateRequest(
    val title: String,
    val description: String? = null,
    val inviteCandidateIds: List<String>
)

@Serializable
data class MeetingDto(val id: String, val title: String, val chatRoom: MeetingChatRoomDto)

@Serializable
data class MeetingChatRoomDto(val id: String, val type: String)
