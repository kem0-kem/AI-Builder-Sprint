package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.ChatMessageDto
import com.apptive.slowtalk.data.remote.ChatMessageRequest
import com.apptive.slowtalk.data.remote.ChatReadRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class ChatRoomInfo(
    val id: String,
    val isGroup: Boolean,
    val name: String?,
    val participantCount: Int? = null
)

object ChatApi {
    suspend fun getRooms(): Result<List<Conversation>> = runCatching {
        RetrofitClient.chatApi.getChatRooms().data.orEmpty().map { room ->
            val isGroup = room.type == "GROUP"
            Conversation(
                title = room.name ?: if (isGroup) "모임 대화" else "익명의 이웃",
                preview = "",
                time = displayTime(room.createdAt),
                unread = false,
                isGroup = isGroup,
                members = if (isGroup) 0 else 1,
                chatRoomId = room.id
            )
        }
    }

    suspend fun getRoom(chatRoomId: String): Result<ChatRoomInfo> = runCatching {
        val room = requireNotNull(RetrofitClient.chatApi.getChatRoom(chatRoomId).data)
        ChatRoomInfo(room.id, room.type == "GROUP", room.name)
    }

    suspend fun openCommentAuthorChat(commentId: String): Result<ChatRoomInfo> = runCatching {
        val room = requireNotNull(RetrofitClient.chatApi.openCommentAuthorChat(commentId).data)
        ChatRoomInfo(room.id, room.type == "GROUP", room.name)
    }

    suspend fun getMessages(chatRoomId: String): Result<List<ChatMessage>> = runCatching {
        RetrofitClient.chatApi.getMessages(chatRoomId).data.orEmpty().map { it.toModel() }.reversed()
    }

    suspend fun sendMessage(chatRoomId: String, content: String): Result<ChatMessage> = runCatching {
        val request = ChatMessageRequest(UUID.randomUUID().toString(), content)
        requireNotNull(RetrofitClient.chatApi.sendMessage(chatRoomId, request).data).toModel()
    }

    suspend fun markAsRead(chatRoomId: String, lastReadMessageId: String): Result<Int> = runCatching {
        requireNotNull(
            RetrofitClient.chatApi.markAsRead(chatRoomId, ChatReadRequest(lastReadMessageId)).data
        ).unreadCount
    }
}

class ChatSocketConnection(private val socket: WebSocket) {
    fun send(content: String): Boolean = socket.send(
        Json.encodeToString(ChatMessageRequest(UUID.randomUUID().toString(), content))
    )
    fun close() { socket.close(1000, "screen closed") }
}

object ChatSocket {
    private val json = Json { ignoreUnknownKeys = true }

    fun connect(
        chatRoomId: String,
        onMessage: (ChatMessage) -> Unit,
        onFailure: () -> Unit
    ): ChatSocketConnection {
        val socket = RetrofitClient.openChatWebSocket(chatRoomId, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.decodeFromString<ChatMessageDto>(text).toModel() }
                    .onSuccess(onMessage)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onFailure()
            }
        })
        return ChatSocketConnection(socket)
    }
}

private fun ChatMessageDto.toModel() = ChatMessage(
    sender = sender.displayName,
    body = content,
    time = displayTime(createdAt),
    mine = sender.isMe,
    id = id,
    type = type
)

private fun displayTime(value: String?): String =
    if (value.isNullOrBlank()) "" else value.substringAfter('T', value).take(5)
