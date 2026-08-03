package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.CreateMeetingRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.requireData

object MeetingApi {
    suspend fun getInviteUsers(keyword: String? = null): Result<List<MeetingInviteUser>> = runCatching {
        RetrofitClient.meetingApi
            .getInviteUsers(keyword?.trim()?.takeIf { it.isNotEmpty() })
            .requireData()
            .map { MeetingInviteUser(it.candidateId, it.displayName) }
    }

    suspend fun createMeeting(
        title: String,
        description: String,
        inviteUserIds: List<String>
    ): Result<MeetingCreation> = runCatching {
        val response = RetrofitClient.meetingApi.createMeeting(
            CreateMeetingRequest(
                title = title.trim(),
                description = description.trim(),
                inviteCandidateIds = inviteUserIds
            )
        )
        val data = response.requireData()
        MeetingCreation(data.id, data.chatRoom.id)
    }
}
