package com.apptive.slowtalk.ui.auth

private val USERNAME_PATTERN = Regex("^[a-z0-9_]{3,30}$")

internal fun normalizeUsernameInput(value: String): String = value.lowercase().take(30)

internal fun isValidUsername(value: String): Boolean = USERNAME_PATTERN.matches(value)
