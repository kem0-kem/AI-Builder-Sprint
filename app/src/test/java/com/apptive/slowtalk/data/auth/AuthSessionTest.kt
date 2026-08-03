package com.apptive.slowtalk.data.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class AuthSessionTest {
    @After
    fun tearDown() {
        AuthSession.resetForTest()
    }

    @Test
    fun `save keeps both tokens in memory and durable storage`() {
        val store = FakeAuthTokenStore()
        AuthSession.initialize(store)

        AuthSession.save("access-value", "refresh-value")

        assertEquals("access-value", AuthSession.accessToken)
        assertEquals("refresh-value", AuthSession.refreshToken)
        assertEquals(AuthTokens("access-value", "refresh-value"), store.load())
        assertTrue(AuthSession.isAuthenticated)
    }

    @Test
    fun `initialize restores a saved session`() {
        val store = FakeAuthTokenStore(AuthTokens("restored-access", "restored-refresh"))

        AuthSession.initialize(store)

        assertEquals("restored-access", AuthSession.accessToken)
        assertEquals("restored-refresh", AuthSession.refreshToken)
        assertTrue(AuthSession.isAuthenticated)
    }

    @Test
    fun `clear removes memory and durable storage`() {
        val store = FakeAuthTokenStore()
        AuthSession.initialize(store)
        AuthSession.save("access-value", "refresh-value")

        AuthSession.clear()

        assertNull(AuthSession.accessToken)
        assertNull(AuthSession.refreshToken)
        assertNull(store.load())
        assertFalse(AuthSession.isAuthenticated)
    }

    @Test
    fun `failed durable save does not publish a partial session`() {
        val store = FakeAuthTokenStore(failOnSave = true)
        AuthSession.initialize(store)
        val before = AuthSession.tokens.value

        runCatching { AuthSession.save("access-value", "refresh-value") }

        assertSame(before, AuthSession.tokens.value)
        assertNull(AuthSession.accessToken)
        assertNull(AuthSession.refreshToken)
    }

    @Test
    fun `compare and clear preserves a rotated session`() {
        val store = FakeAuthTokenStore()
        AuthSession.initialize(store)
        AuthSession.save("new-access", "new-refresh")

        val cleared = AuthSession.compareAndClear("old-access")

        assertFalse(cleared)
        assertEquals(AuthTokens("new-access", "new-refresh"), AuthSession.tokens.value)
    }
}

internal class FakeAuthTokenStore(
    initialTokens: AuthTokens? = null,
    private val failOnSave: Boolean = false,
) : AuthTokenStore {
    private var tokens = initialTokens

    override fun load(): AuthTokens? = tokens

    override fun save(tokens: AuthTokens) {
        if (failOnSave) error("durable save failed")
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}
