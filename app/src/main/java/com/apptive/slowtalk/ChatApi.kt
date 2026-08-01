package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.ChatMessageDto
import com.apptive.slowtalk.data.remote.ChatMessageRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class ChatRoomInfo(
    val id: Int,
    val isGroup: Boolean,
    val name: String?,
    val participantCount: Int?
)

object ChatApi {
    suspend fun getRooms(): Result<List<Conversation>> = runCatching {
        RetrofitClient.chatApi.getChatRooms().map { room ->
            val isGroup = room.type == "GROUP"
            Conversation(
                title = room.roomName ?: "익명의 이웃 ${room.chatRoomId.toString().padStart(2, '0')}",
                preview = room.lastMessage.orEmpty(),
                time = displayTime(room.lastMessageAt),
                unread = room.unreadCount > 0,
                isGroup = isGroup,
                members = if (isGroup) 0 else 1,
                chatRoomId = room.chatRoomId
            )
        }
    }

    suspend fun getRoom(chatRoomId: Int): Result<ChatRoomInfo> = runCatching {
        RetrofitClient.chatApi.getChatRoom(chatRoomId).let {
            ChatRoomInfo(
                id = it.chatRoomId,
                isGroup = it.type == "GROUP",
                name = it.roomName,
                participantCount = it.participantCount
            )
        }
    }

    suspend fun getMessages(chatRoomId: Int): Result<List<ChatMessage>> = runCatching {
        RetrofitClient.chatApi.getMessages(chatRoomId).map { it.toModel() }
    }

    suspend fun sendMessage(chatRoomId: Int, content: String): Result<ChatMessage> = runCatching {
        RetrofitClient.chatApi.sendMessage(chatRoomId, ChatMessageRequest(content)).let {
            ChatMessage(
                sender = "나",
                body = content,
                time = displayTime(it.createdAt),
                mine = true,
                id = it.messageId
            )
        }
    }
}

class ChatSocketConnection(
    private val socket: WebSocket
) {
    fun send(content: String): Boolean = socket.send(
        Json.encodeToString(ChatMessageRequest(content))
    )

    fun close() {
        socket.close(1000, "screen closed")
    }
}

object ChatSocket {
    private val json = Json { ignoreUnknownKeys = true }

    fun connect(
        chatRoomId: Int,
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
    sender = sender,
    body = content,
    time = displayTime(createdAt),
    mine = sender == "나" || sender == "글쓴이",
    id = messageId,
    type = type
)

private fun displayTime(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return value.substringAfter('T', value).take(5)
}
