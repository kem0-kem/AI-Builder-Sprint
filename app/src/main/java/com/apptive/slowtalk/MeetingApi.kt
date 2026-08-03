package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.CreateMeetingRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.apiData

object MeetingApi {
    suspend fun getInviteUsers(keyword: String? = null): Result<List<MeetingInviteUser>> = runCatching {
        apiData {
            RetrofitClient.meetingApi.getInviteUsers(keyword?.trim()?.takeIf { it.isNotEmpty() })
        }
            .map { MeetingInviteUser(it.candidateId, it.displayName) }
    }

    suspend fun createMeeting(
        title: String,
        description: String,
        inviteUserIds: List<String>
    ): Result<MeetingCreation> = runCatching {
        val data = apiData {
            RetrofitClient.meetingApi.createMeeting(
                CreateMeetingRequest(
                    title = title.trim(),
                    description = description.trim(),
                    inviteCandidateIds = inviteUserIds,
                ),
            )
        }
        MeetingCreation(data.id, data.chatRoom.id)
    }
}
