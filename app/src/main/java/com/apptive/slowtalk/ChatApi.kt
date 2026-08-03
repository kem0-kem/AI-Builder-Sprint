package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.ChatApiService
import com.apptive.slowtalk.data.remote.ChatMessageDto
import com.apptive.slowtalk.data.remote.ChatMessageRequest
import com.apptive.slowtalk.data.remote.ChatReadRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.apiData
import com.apptive.slowtalk.data.remote.apiModerated
import com.apptive.slowtalk.data.remote.requireResource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    suspend fun openFromComment(commentId: String): Result<ChatRoomInfo> =
        openFromComment(commentId, RetrofitClient.chatApi)

    internal suspend fun openFromComment(
        commentId: String,
        service: ChatApiService,
    ): Result<ChatRoomInfo> = runCatching {
        apiData { service.createFromComment(commentId) }.let {
            ChatRoomInfo(
                id = it.id,
                isGroup = it.type == "GROUP",
                name = it.name,
                participantCount = null,
            )
        }
    }

    suspend fun getRooms(): Result<List<Conversation>> = runCatching {
        val rooms = apiData { RetrofitClient.chatApi.getChatRooms() }
        coroutineScope {
            rooms.map { room ->
                async {
                    val latestMessage = runCatching {
                        apiData { RetrofitClient.chatApi.getMessages(room.id) }
                            .maxByOrNull { it.createdAt }
                    }.getOrNull()
                    room to latestMessage
                }
            }.awaitAll()
        }
            .sortedByDescending { (room, latestMessage) ->
                latestMessage?.createdAt ?: room.createdAt
            }
            .map { (room, latestMessage) ->
                val isGroup = room.type == "GROUP"
                Conversation(
                    title = room.name ?: "익명의 이웃",
                    preview = latestMessage?.content.orEmpty(),
                    time = displayTime(latestMessage?.createdAt ?: room.createdAt),
                    unread = false,
                    isGroup = isGroup,
                    members = if (isGroup) 0 else 1,
                    chatRoomId = room.id
                )
            }
    }

    suspend fun getRoom(chatRoomId: String): Result<ChatRoomInfo> = runCatching {
        apiData { RetrofitClient.chatApi.getChatRoom(chatRoomId) }.let {
            ChatRoomInfo(
                id = it.id,
                isGroup = it.type == "GROUP",
                name = it.name,
                participantCount = null
            )
        }
    }

    suspend fun getMessages(chatRoomId: String): Result<List<ChatMessage>> = runCatching {
        apiData { RetrofitClient.chatApi.getMessages(chatRoomId) }
            .sortedBy { it.createdAt }
            .map { it.toModel() }
    }

    suspend fun sendMessage(chatRoomId: String, content: String): Result<ChatMessage> = runCatching {
        apiModerated(ChatMessageDto.serializer()) {
            RetrofitClient.chatApi.sendMessage(
                chatRoomId,
                ChatMessageRequest(UUID.randomUUID().toString(), content),
            )
        }.requireResource().toModel()
    }

    suspend fun markAsRead(chatRoomId: String, lastReadMessageId: String): Result<Int> = runCatching {
        apiData {
            RetrofitClient.chatApi.markAsRead(
                chatRoomId = chatRoomId,
                request = ChatReadRequest(lastReadMessageId),
            )
        }.let { response ->
            response.unreadCount
        }
    }

    suspend fun leaveRoom(chatRoomId: String): Result<Unit> = runCatching {
        val response = RetrofitClient.chatApi.leaveChatRoom(chatRoomId)
        check(response.isSuccessful) {
            "대화방을 나가지 못했습니다. (HTTP ${response.code()})"
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
