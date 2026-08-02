package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.ChatMessageCreateRequest
import com.apptive.slowtalk.data.remote.ChatMessageDto
import com.apptive.slowtalk.data.remote.InviteCandidateDto
import com.apptive.slowtalk.data.remote.MeetingCreateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class InviteCandidate(val id: String, val name: String)

object ChatApi {
    suspend fun getRooms(): Result<List<Conversation>> = runCatching {
        val response = RetrofitClient.chatApi.getRooms()
        check(response.ok) { "대화 목록을 불러오지 못했습니다." }
        response.data.orEmpty().map { room ->
            Conversation(
                title = room.name ?: "익명의 이웃",
                preview = "새로운 대화",
                time = relativeTime(room.createdAt),
                isGroup = room.type == "GROUP",
                roomId = room.id
            )
        }
    }

    suspend fun getMessages(roomId: String): Result<List<ChatMessage>> = runCatching {
        val response = RetrofitClient.chatApi.getMessages(roomId)
        check(response.ok) { "메시지를 불러오지 못했습니다." }
        response.data.orEmpty().asReversed().map(::toMessage)
    }

    suspend fun sendMessage(roomId: String, content: String): Result<ChatMessage> = runCatching {
        val response = RetrofitClient.chatApi.createMessage(
            roomId,
            ChatMessageCreateRequest(UUID.randomUUID().toString(), content)
        )
        check(response.ok && response.data != null) { "메시지를 전송하지 못했습니다." }
        toMessage(response.data)
    }

    suspend fun getInviteCandidates(): Result<List<InviteCandidate>> = runCatching {
        val response = RetrofitClient.chatApi.getInviteCandidates()
        check(response.ok) { "초대할 이웃을 불러오지 못했습니다." }
        response.data.orEmpty().map { InviteCandidate(it.candidateId, it.displayName) }
    }

    suspend fun createMeeting(
        title: String,
        description: String,
        candidateIds: List<String>
    ): Result<Conversation> = runCatching {
        val response = RetrofitClient.chatApi.createMeeting(
            MeetingCreateRequest(title, description.ifBlank { null }, candidateIds)
        )
        check(response.ok && response.data != null) { "모임 대화를 만들지 못했습니다." }
        Conversation(
            title = response.data.title,
            preview = "새 모임 대화",
            time = "방금 전",
            isGroup = true,
            members = candidateIds.size + 1,
            roomId = response.data.chatRoom.id
        )
    }

    suspend fun leaveRoom(roomId: String): Result<Unit> = runCatching {
        val response = RetrofitClient.chatApi.leaveRoom(roomId)
        check(response.isSuccessful) { "대화를 삭제하지 못했습니다." }
    }

    private fun toMessage(item: ChatMessageDto) = ChatMessage(
        sender = item.sender.displayName,
        body = item.content,
        time = relativeTime(item.createdAt),
        mine = item.sender.isMe
    )

    private fun relativeTime(value: String): String = runCatching {
        val localDate = OffsetDateTime.parse(value)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDate()
        val days = ChronoUnit.DAYS.between(localDate, java.time.LocalDate.now()).coerceAtLeast(0)
        when (days) {
            0L -> "방금 전"
            1L -> "어제"
            in 2L..6L -> "${days}일 전"
            else -> "${localDate.monthValue}월 ${localDate.dayOfMonth}일"
        }
    }.getOrDefault(value)
}
