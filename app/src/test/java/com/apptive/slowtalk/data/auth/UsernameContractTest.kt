package com.apptive.slowtalk.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameContractTest {
    @Test
    fun `normalizes usernames to lowercase`() {
        assertEquals("test_user9", UsernameContract.normalize("Test_User9"))
    }

    @Test
    fun `accepts backend username contract`() {
        assertTrue(UsernameContract.isValid("Test_User9"))
        assertTrue(UsernameContract.isValid("abc"))
        assertTrue(UsernameContract.isValid("a".repeat(30)))
    }

    @Test
    fun `rejects invalid backend usernames`() {
        assertFalse(UsernameContract.isValid("ab"))
        assertFalse(UsernameContract.isValid("has-hyphen"))
        assertFalse(UsernameContract.isValid("가나다"))
        assertFalse(UsernameContract.isValid("a".repeat(31)))
    }
}
