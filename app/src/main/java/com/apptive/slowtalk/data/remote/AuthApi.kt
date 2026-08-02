package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AuthApi {
    @POST("auth/signup")
    suspend fun signup(@Body request: SignupRequest): Response<ApiEnvelope<SignupResponse>>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiEnvelope<TokenPairResponse>>

    @POST("auth/token/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<ApiEnvelope<TokenPairResponse>>

    @GET("auth/check-username")
    suspend fun checkUsername(@Query("username") username: String): Response<ApiEnvelope<AvailabilityResponse>>
}

@Serializable
data class ApiEnvelope<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ApiError? = null
)

@Serializable
data class ApiError(
    val code: String,
    val message: String
)

@Serializable
data class SignupRequest(
    val email: String,
    val password: String,
    val nickname: String,
    val username: String? = null
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Int
)

@Serializable
data class SignupResponse(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Int
) {
    fun tokenPair() = TokenPairResponse(accessToken, refreshToken, tokenType, expiresIn)
}

@Serializable
data class AvailabilityResponse(val available: Boolean)
