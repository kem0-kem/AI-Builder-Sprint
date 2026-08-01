package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class SignupRequest(
    val email: String,
    val password: String,
    val nickname: String
)

@Serializable
data class SignupResponse(
    val userId: Int,
    val message: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String
)

@Serializable
data class EmailCheckResponse(
    val available: Boolean
)
