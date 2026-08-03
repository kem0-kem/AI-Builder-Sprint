package com.apptive.slowtalk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentChatNavigationTest {
    @Test
    fun `successful comment chat request opens the returned room`() = runBlocking {
        val room = ChatRoomInfo(ROOM_ID, false, null, null)
        var openedRoom: ChatRoomInfo? = null
        var errorCount = 0

        requestCommentChat(
            commentId = COMMENT_ID,
            openCommentChat = { Result.success(room) },
            onCommentChatOpened = { openedRoom = it },
            onFailure = { errorCount += 1 },
        )

        assertEquals(room, openedRoom)
        assertEquals(0, errorCount)
        assertEquals(
            Screen.Chat("익명의 이웃", isGroup = false, chatRoomId = ROOM_ID),
            screenForOpenedCommentChat(room),
        )
    }

    @Test
    fun `failed comment chat request stays on feed and reports retryable error`() = runBlocking {
        var navigationCount = 0
        var errorCount = 0

        requestCommentChat(
            commentId = COMMENT_ID,
            openCommentChat = { Result.failure(IllegalStateException("network")) },
            onCommentChatOpened = { navigationCount += 1 },
            onFailure = { errorCount += 1 },
        )

        assertEquals(0, navigationCount)
        assertEquals(1, errorCount)
    }

    private companion object {
        const val COMMENT_ID = "11111111-1111-1111-1111-111111111111"
        const val ROOM_ID = "22222222-2222-2222-2222-222222222222"
    }
}
