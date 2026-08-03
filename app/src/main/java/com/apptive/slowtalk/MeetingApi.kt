package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.CreateMeetingRequest
import com.apptive.slowtalk.data.remote.RetrofitClient

object MeetingApi {
    suspend fun getInviteUsers(keyword: String? = null): Result<List<MeetingInviteUser>> = runCatching {
        RetrofitClient.meetingApi
            .getInviteUsers(keyword?.trim()?.takeIf { it.isNotEmpty() })
            .data.orEmpty().map { MeetingInviteUser(it.candidateId, it.displayName) }
    }

    suspend fun createMeeting(
        title: String,
        description: String,
        inviteCandidateIds: List<String>
    ): Result<MeetingCreation> = runCatching {
        val response = RetrofitClient.meetingApi.createMeeting(
            CreateMeetingRequest(
                title = title.trim(),
                description = description.trim(),
                inviteCandidateIds = inviteCandidateIds
            )
        )
        val meeting = requireNotNull(response.data)
        MeetingCreation(meeting.id, meeting.chatRoom.id)
    }
}
