package com.apptive.slowtalk.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernamePolicyTest {
    @Test
    fun `username input normalizes case and length`() {
        assertEquals("test_user", normalizeUsernameInput("Test_User"))
        assertEquals(30, normalizeUsernameInput("a".repeat(31)).length)
    }

    @Test
    fun `username accepts only backend format`() {
        assertTrue(isValidUsername("test_user1"))
        assertFalse(isValidUsername("ab"))
        assertFalse(isValidUsername("has-hyphen"))
        assertFalse(isValidUsername("한글id"))
    }
}
