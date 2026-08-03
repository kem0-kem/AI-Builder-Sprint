package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ApiErrorDto? = null
)

@Serializable
data class ApiErrorDto(val code: String, val message: String)

@Serializable
data class SignupRequest(
    val email: String,
    val password: String,
    val nickname: String,
    val username: String? = null
)

@Serializable
data class SignupResponse(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "bearer",
    val expiresIn: Int = 0
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "bearer",
    val expiresIn: Int = 0
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class EmailCheckResponse(val available: Boolean)
