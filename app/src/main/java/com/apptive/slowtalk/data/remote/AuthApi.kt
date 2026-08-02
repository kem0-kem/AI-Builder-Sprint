package com.apptive.slowtalk.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AuthApi {
    @POST("auth/signup")
    suspend fun signup(@Body request: SignupRequest): SignupResponse

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @GET("auth/check-email")
    suspend fun checkEmail(@Query("email") email: String): EmailCheckResponse

    @GET("auth/check-username")
    suspend fun checkUsername(@Query("username") username: String): EmailCheckResponse

    @POST("auth/logout")
    suspend fun logout(): LogoutResponse
}

@kotlinx.serialization.Serializable
data class LogoutResponse(
    val message: String
)
