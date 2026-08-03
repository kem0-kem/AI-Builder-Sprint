package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.FeedTimelineDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeedApiAdapterTest {
    @Test
    fun `feed keeps category UUID separate from display label`() {
        val categoryId = "d5d13bf5-2e15-4923-8762-f2b29937ac37"
        val dto = FeedTimelineDto(
            id = "9a4c3d88-5ac9-4a63-9e77-fbb74e33a610",
            categoryId = categoryId,
            title = "Slow day",
            content = "I took a walk.",
            createdAt = "2026-08-03T10:00:00+00:00",
            updatedAt = "2026-08-03T10:00:00+00:00",
        )

        val post = dto.toFeedPost(categoryName = "일상")

        assertEquals(categoryId, post.categoryId)
        assertEquals("일상 이야기", post.category)
        assertNotEquals(categoryId, post.category)
    }
}
