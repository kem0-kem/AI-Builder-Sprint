package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface MeetingApiService {
    @GET("meetings/invite-users")
    suspend fun getInviteUsers(@Query("keyword") keyword: String? = null): List<MeetingInviteUserDto>

    @POST("meetings")
    suspend fun createMeeting(@Body request: CreateMeetingRequest): CreateMeetingResponse
}

@Serializable
data class MeetingInviteUserDto(
    val userId: Int,
    val nickname: String
)

@Serializable
data class CreateMeetingRequest(
    val title: String,
    val description: String,
    val inviteUserIds: List<Int>
)

@Serializable
data class CreateMeetingResponse(
    val meetingId: Int,
    val chatRoomId: Int
)
