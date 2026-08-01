package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.CreateMeetingRequest
import com.apptive.slowtalk.data.remote.RetrofitClient

object MeetingApi {
    suspend fun getInviteUsers(keyword: String? = null): Result<List<MeetingInviteUser>> = runCatching {
        RetrofitClient.meetingApi
            .getInviteUsers(keyword?.trim()?.takeIf { it.isNotEmpty() })
            .map { MeetingInviteUser(it.userId, it.nickname) }
    }

    suspend fun createMeeting(
        title: String,
        description: String,
        inviteUserIds: List<Int>
    ): Result<MeetingCreation> = runCatching {
        val response = RetrofitClient.meetingApi.createMeeting(
            CreateMeetingRequest(
                title = title.trim(),
                description = description.trim(),
                inviteUserIds = inviteUserIds
            )
        )
        MeetingCreation(response.meetingId, response.chatRoomId)
    }
}
