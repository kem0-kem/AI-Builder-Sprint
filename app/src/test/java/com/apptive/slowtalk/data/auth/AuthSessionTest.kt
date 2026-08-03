package com.apptive.slowtalk.data.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}

internal class FakeAuthTokenStore(
    initialTokens: AuthTokens? = null,
) : AuthTokenStore {
    private var tokens = initialTokens

    override fun load(): AuthTokens? = tokens

    override fun save(tokens: AuthTokens) {
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}
