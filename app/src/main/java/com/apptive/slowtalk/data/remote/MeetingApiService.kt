package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface MeetingApiService {
    @GET("meeting-invite-candidates")
    suspend fun getInviteUsers(@Query("keyword") keyword: String? = null): ApiEnvelope<List<MeetingInviteUserDto>>

    @POST("meetings")
    suspend fun createMeeting(@Body request: CreateMeetingRequest): ApiEnvelope<CreateMeetingResponse>
}

@Serializable
data class MeetingInviteUserDto(
    val candidateId: String,
    val displayName: String
)

@Serializable
data class CreateMeetingRequest(
    val title: String,
    val description: String,
    val inviteCandidateIds: List<String>
)

@Serializable
data class CreateMeetingResponse(
    val id: String,
    val title: String,
    val description: String? = null,
    val chatRoom: MeetingChatRoomDto,
    val participantCount: Int,
)

@Serializable
data class MeetingChatRoomDto(val id: String, val type: String)
