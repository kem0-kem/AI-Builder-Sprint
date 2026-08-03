package com.apptive.slowtalk

import androidx.compose.ui.graphics.Color

sealed interface Screen {
    data object Login : Screen
    data object SignUp : Screen
    data object Feed : Screen
    data object WriteFeed : Screen
    data class EditFeed(val feedId: String) : Screen
    data class FeedDetail(val feedId: String) : Screen
    data object Conversations : Screen
    data class Chat(
        val title: String,
        val isGroup: Boolean = false,
        val chatRoomId: String? = null
    ) : Screen
    data object CreateGroup : Screen
    data object LetterHome : Screen
    data object WriteLetter : Screen
    data object LetterHistory : Screen
    data class LetterDetail(val letterId: String?, val title: String) : Screen
    data object Profile : Screen
    data object EditProfile : Screen
    data object Interests : Screen
    data object WriteReflection : Screen
    data class ReflectionDetail(val title: String) : Screen
}

data class FeedPost(
    val id: String,
    val category: String,
    val categoryId: String = category,
    val title: String,
    val body: String,
    val comments: MutableList<Comment> = mutableListOf(),
    val commentCount: Int = 0,
    val accent: Color,
    val isMine: Boolean = false
)

data class Comment(
    val author: String,
    val message: String,
    val time: String,
    val isMine: Boolean = false,
    val replies: List<Comment> = emptyList(),
    val id: String? = null
)

internal fun List<Comment>.treeCount(): Int = sumOf { 1 + it.replies.treeCount() }

data class Conversation(
    val title: String,
    val preview: String,
    val time: String,
    val unread: Boolean = false,
    val isGroup: Boolean = false,
    val members: Int = 1,
    val chatRoomId: String? = null
)

data class ChatMessage(
    val sender: String,
    val body: String,
    val time: String,
    val mine: Boolean,
    val id: String? = null,
    val type: String = "CHAT"
)

data class MeetingInviteUser(
    val userId: String,
    val nickname: String
)

data class MeetingCreation(
    val meetingId: String,
    val chatRoomId: String
)

data class Letter(
    val title: String,
    val preview: String,
    val date: String,
    val received: Boolean,
    val id: String? = null,
    val content: String = preview
)

enum class MainTab { CONVERSATIONS, FEED, LETTER }

val Purple = Color(0xFF7055C7)
val PurpleSoft = Color(0xFFF0EBFF)
val Peach = Color(0xFFF6B7A5)
val Mint = Color(0xFFB9DCCF)
val Ink = Color(0xFF242229)
val SubtleInk = Color(0xFF77737D)
val Paper = Color(0xFFFFFCF8)
val BlockSurface = Color(0xFFFFFEFC)
val LineColor = Color(0xFFE9E4DE)
