package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.ChatMessageDto
import com.apptive.slowtalk.data.remote.ChatMessageRequest
import com.apptive.slowtalk.data.remote.ChatReadRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.requireData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID

data class ChatRoomInfo(
    val id: String,
    val isGroup: Boolean,
    val name: String?,
    val participantCount: Int?
)

object ChatApi {
    suspend fun getRooms(): Result<List<Conversation>> = runCatching {
        RetrofitClient.chatApi.getChatRooms().requireData().map { room ->
            val isGroup = room.type == "GROUP"
            Conversation(
                title = room.name ?: "익명의 이웃",
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
        RetrofitClient.chatApi.getChatRoom(chatRoomId).requireData().let {
            ChatRoomInfo(
                id = it.id,
                isGroup = it.type == "GROUP",
                name = it.name,
                participantCount = null
            )
        }
    }

    suspend fun getMessages(chatRoomId: String): Result<List<ChatMessage>> = runCatching {
        RetrofitClient.chatApi.getMessages(chatRoomId).requireData().map { it.toModel() }
    }

    suspend fun sendMessage(chatRoomId: String, content: String): Result<ChatMessage> = runCatching {
        RetrofitClient.chatApi.sendMessage(
            chatRoomId,
            ChatMessageRequest(UUID.randomUUID().toString(), content),
        ).requireData().toModel()
    }

    suspend fun markAsRead(chatRoomId: String, lastReadMessageId: String): Result<Int> = runCatching {
        RetrofitClient.chatApi.markAsRead(
            chatRoomId = chatRoomId,
            request = ChatReadRequest(lastReadMessageId)
        ).requireData().let { response ->
            response.unreadCount
        }
    }
}

class ChatSocketConnection(
    private val socket: WebSocket
) {
    fun send(content: String): Boolean = socket.send(
        Json.encodeToString(ChatMessageRequest(UUID.randomUUID().toString(), content))
    )

    fun close() {
        socket.close(1000, "screen closed")
    }
}

object ChatSocket {
    private val json = Json { ignoreUnknownKeys = true }

    fun connect(
        chatRoomId: String,
        onMessage: (ChatMessage) -> Unit,
        onFailure: () -> Unit
    ): ChatSocketConnection {
        val socket = RetrofitClient.openChatWebSocket(
            chatRoomId,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        json.decodeFromString<ChatMessageDto>(text).toModel()
                    }.onSuccess(onMessage)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onFailure()
                }
            }
        )
        return ChatSocketConnection(socket)
    }
}

private fun ChatMessageDto.toModel(): ChatMessage = ChatMessage(
    sender = sender.displayName,
    body = content,
    time = displayTime(createdAt),
    mine = sender.isMe,
    id = id,
    type = type
)

private fun displayTime(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return value.substringAfter('T', value).take(5)
}
