package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class SignupRequest(
    val email: String,
    val password: String,
    val nickname: String,
    val username: String? = null,
)

@Serializable
data class SignupResponse(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int,
)

@Serializable
data class EmailCheckResponse(
    val available: Boolean
)
