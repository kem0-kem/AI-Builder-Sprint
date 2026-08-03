package com.apptive.slowtalk

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthSessionNavigationTest {
    @Test
    fun `session loss returns protected screen to login`() {
        assertEquals(Screen.Login, screenAfterSessionChange(Screen.Feed, isAuthenticated = false))
    }

    @Test
    fun `session loss does not loop between authentication screens`() {
        assertEquals(Screen.Login, screenAfterSessionChange(Screen.Login, isAuthenticated = false))
        assertEquals(Screen.SignUp, screenAfterSessionChange(Screen.SignUp, isAuthenticated = false))
    }

    @Test
    fun `active session preserves current screen`() {
        assertEquals(Screen.Feed, screenAfterSessionChange(Screen.Feed, isAuthenticated = true))
    }
}
