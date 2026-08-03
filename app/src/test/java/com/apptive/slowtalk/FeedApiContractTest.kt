package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.CommentContentRequest
import com.apptive.slowtalk.data.remote.CommentCreateRequest
import com.apptive.slowtalk.data.remote.FeedApiService
import com.apptive.slowtalk.data.remote.FeedCreateRequest
import com.apptive.slowtalk.data.remote.FeedReportCreateRequest
import com.apptive.slowtalk.data.remote.FeedUpdateRequest
import com.apptive.slowtalk.data.remote.apiJson
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class FeedApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: FeedApiService

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("api/v1/"))
            .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FeedApiService::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `feed page sends scope cursor limit and categoryId and decodes next cursor`() = runBlocking {
        server.enqueue(jsonResponse("""{"ok":true,"data":[],"error":null,"meta":{"nextCursor":"$FEED_ID","hasNext":true}}"""))

        val response = api.getFeeds("mine", FEED_ID, 10, CATEGORY_ID)

        assertEquals(FEED_ID, response.meta?.nextCursor)
        assertEquals(
            "/api/v1/feeds?scope=mine&cursor=$FEED_ID&limit=10&categoryId=$CATEGORY_ID",
            server.takeRequest().path,
        )
    }

    @Test
    fun `feed create detail update and delete use backend contract`() = runBlocking {
        server.enqueue(jsonResponse(FEED_RESPONSE, 201))
        server.enqueue(jsonResponse(FEED_RESPONSE))
        server.enqueue(jsonResponse(FEED_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(204))

        api.createFeed(FeedCreateRequest(CATEGORY_ID, "hello", "body"))
        api.getFeed(FEED_ID)
        api.updateFeed(FEED_ID, FeedUpdateRequest(CATEGORY_ID, "edited", "new body"))
        api.deleteFeed(FEED_ID)

        server.takeRequest().let {
            assertEquals("POST", it.method)
            assertEquals("/api/v1/feeds", it.path)
            assertEquals(
                "{\"categoryId\":\"$CATEGORY_ID\",\"title\":\"hello\",\"content\":\"body\"}",
                it.body.readUtf8(),
            )
        }
        server.takeRequest().let {
            assertEquals("GET", it.method)
            assertEquals("/api/v1/feeds/$FEED_ID", it.path)
        }
        server.takeRequest().let {
            assertEquals("PATCH", it.method)
            assertEquals("/api/v1/feeds/$FEED_ID", it.path)
            assertEquals(
                "{\"categoryId\":\"$CATEGORY_ID\",\"title\":\"edited\",\"content\":\"new body\"}",
                it.body.readUtf8(),
            )
        }
        server.takeRequest().let {
            assertEquals("DELETE", it.method)
            assertEquals("/api/v1/feeds/$FEED_ID", it.path)
        }
    }

    @Test
    fun `like uses PUT and unlike uses DELETE`() = runBlocking {
        server.enqueue(jsonResponse(LIKED_RESPONSE))
        server.enqueue(jsonResponse(UNLIKED_RESPONSE))

        api.likeFeed(FEED_ID)
        api.unlikeFeed(FEED_ID)

        server.takeRequest().let {
            assertEquals("PUT", it.method)
            assertEquals("/api/v1/feeds/$FEED_ID/like", it.path)
        }
        server.takeRequest().let {
            assertEquals("DELETE", it.method)
            assertEquals("/api/v1/feeds/$FEED_ID/like", it.path)
        }
    }

    @Test
    fun `feed report uses reports path and reason body`() = runBlocking {
        server.enqueue(jsonResponse(REPORTED_RESPONSE, 201))

        api.reportFeed(FEED_ID, FeedReportCreateRequest("spam"))

        server.takeRequest().let {
            assertEquals("POST", it.method)
            assertEquals("/api/v1/feeds/$FEED_ID/reports", it.path)
            assertEquals("{\"reason\":\"spam\"}", it.body.readUtf8())
        }
    }

    @Test
    fun `comments use feed create path and top level mutation paths`() = runBlocking {
        server.enqueue(jsonResponse(COMMENT_RESPONSE, 201))
        server.enqueue(jsonResponse(COMMENT_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse(REPORTED_RESPONSE, 201))

        api.createComment(FEED_ID, CommentCreateRequest("hello", PARENT_ID))
        api.updateComment(COMMENT_ID, CommentContentRequest("edited"))
        api.deleteComment(COMMENT_ID)
        api.reportComment(COMMENT_ID, FeedReportCreateRequest("abuse"))

        server.takeRequest().let {
            assertEquals("/api/v1/feeds/$FEED_ID/comments", it.path)
            assertEquals("{\"content\":\"hello\",\"parentCommentId\":\"$PARENT_ID\"}", it.body.readUtf8())
        }
        server.takeRequest().let {
            assertEquals("PATCH", it.method)
            assertEquals("/api/v1/comments/$COMMENT_ID", it.path)
        }
        server.takeRequest().let {
            assertEquals("DELETE", it.method)
            assertEquals("/api/v1/comments/$COMMENT_ID", it.path)
        }
        server.takeRequest().let {
            assertEquals("/api/v1/comments/$COMMENT_ID/reports", it.path)
            assertEquals("{\"reason\":\"abuse\"}", it.body.readUtf8())
        }
    }

    @Test
    fun `comment list uses feed path and limit query`() = runBlocking {
        server.enqueue(jsonResponse("""{"ok":true,"data":[],"error":null,"meta":{"nextCursor":null,"hasNext":false}}"""))

        api.getComments(FEED_ID, 50)

        assertEquals("/api/v1/feeds/$FEED_ID/comments?limit=50", server.takeRequest().path)
    }

    private fun jsonResponse(body: String, status: Int = 200) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val FEED_ID = "11111111-1111-4111-8111-111111111111"
        const val CATEGORY_ID = "22222222-2222-4222-8222-222222222222"
        const val COMMENT_ID = "33333333-3333-4333-8333-333333333333"
        const val PARENT_ID = "44444444-4444-4444-8444-444444444444"
        const val LIKED_RESPONSE = """{"ok":true,"data":{"liked":true},"error":null,"meta":null}"""
        const val UNLIKED_RESPONSE = """{"ok":true,"data":{"liked":false},"error":null,"meta":null}"""
        const val REPORTED_RESPONSE = """{"ok":true,"data":{"reported":true},"error":null,"meta":null}"""
        const val COMMENT_RESPONSE = """{"ok":true,"data":{"id":"$COMMENT_ID","feedId":"$FEED_ID","parentCommentId":null,"content":"hello","isMine":true,"createdAt":"2026-08-03T10:00:00Z"},"error":null,"meta":null}"""
        const val FEED_RESPONSE = """{"ok":true,"data":{"id":"$FEED_ID","categoryId":"$CATEGORY_ID","title":"hello","content":"body","isMine":true,"liked":false,"likeCount":0,"commentCount":3,"createdAt":"2026-08-03T10:00:00Z","updatedAt":"2026-08-03T10:00:00Z"},"error":null,"meta":null}"""
    }
}
