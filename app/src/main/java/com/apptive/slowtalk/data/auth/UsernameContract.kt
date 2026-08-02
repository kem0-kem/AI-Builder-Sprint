package com.apptive.slowtalk.data.auth

import java.util.Locale

object UsernameContract {
    private val validPattern = Regex("^[a-z0-9_]{3,30}$")

    fun normalize(value: String): String = value.lowercase(Locale.ROOT)

    fun isValid(value: String): Boolean = validPattern.matches(normalize(value))
}
