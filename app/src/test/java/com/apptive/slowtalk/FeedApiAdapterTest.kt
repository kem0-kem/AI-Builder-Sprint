package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.FeedTimelineDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

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

    @Test
    fun `cold feed load fetches categories once before mapping labels`() = runBlocking {
        var loadCount = 0
        val categoryId = "d5d13bf5-2e15-4923-8762-f2b29937ac37"
        val catalog = FeedCategoryCatalog {
            loadCount += 1
            listOf(FeedCategoryResult(categoryId, "일상"))
        }

        val snapshot = catalog.snapshotOrEmpty()
        val first = snapshot.toFeedPost(feed(categoryId, "00000000-0000-0000-0000-000000000001"))
        val second = snapshot.toFeedPost(feed(categoryId, "00000000-0000-0000-0000-000000000002"))

        assertEquals(1, loadCount)
        assertEquals("일상 이야기", first.category)
        assertEquals("일상 이야기", second.category)
    }

    @Test
    fun `transient category failure falls back then retries and caches success`() = runBlocking {
        var loadCount = 0
        val categoryId = "d5d13bf5-2e15-4923-8762-f2b29937ac37"
        val catalog = FeedCategoryCatalog {
            loadCount += 1
            if (loadCount == 1) error("category endpoint unavailable")
            listOf(FeedCategoryResult(categoryId, "일상"))
        }

        val first = catalog.snapshotOrEmpty().toFeedPost(
            feed(categoryId, "00000000-0000-0000-0000-000000000001"),
        )
        val second = catalog.snapshotOrEmpty().toFeedPost(
            feed(categoryId, "00000000-0000-0000-0000-000000000002"),
        )
        val third = catalog.snapshotOrEmpty().toFeedPost(
            feed(categoryId, "00000000-0000-0000-0000-000000000003"),
        )

        assertEquals("기타", first.category)
        assertEquals("일상 이야기", second.category)
        assertEquals("일상 이야기", third.category)
        assertEquals(2, loadCount)
    }

    @Test
    fun `concurrent cold loads publish one successful category snapshot`() = runBlocking {
        var loadCount = 0
        val categoryId = "d5d13bf5-2e15-4923-8762-f2b29937ac37"
        val catalog = FeedCategoryCatalog {
            loadCount += 1
            delay(25)
            listOf(FeedCategoryResult(categoryId, "일상"))
        }

        val snapshots = coroutineScope {
            List(8) { async { catalog.snapshotOrEmpty() } }.awaitAll()
        }

        assertEquals(1, loadCount)
        snapshots.forEach { snapshot ->
            assertEquals(
                "일상 이야기",
                snapshot.toFeedPost(feed(categoryId, "00000000-0000-0000-0000-000000000001")).category,
            )
        }
    }

    @Test
    fun `direct category load exposes loader failure`() = runBlocking {
        val catalog = FeedCategoryCatalog { error("category endpoint unavailable") }

        val result = runCatching { catalog.categories() }

        assertEquals(true, result.isFailure)
    }

    private fun feed(categoryId: String, feedId: String) = FeedTimelineDto(
        id = feedId,
        categoryId = categoryId,
        title = "Slow day",
        content = "I took a walk.",
        createdAt = "2026-08-03T10:00:00+00:00",
        updatedAt = "2026-08-03T10:00:00+00:00",
    )
}
