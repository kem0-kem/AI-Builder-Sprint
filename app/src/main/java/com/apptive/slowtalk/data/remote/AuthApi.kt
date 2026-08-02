package com.apptive.slowtalk.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AuthApi {
    @POST("auth/signup")
    suspend fun signup(@Body request: SignupRequest): ApiEnvelope<SignupResponse>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): ApiEnvelope<LoginResponse>

    @GET("auth/email-availability")
    suspend fun checkEmail(@Query("email") email: String): ApiEnvelope<EmailCheckResponse>

    @GET("auth/check-username")
    suspend fun checkUsername(@Query("username") username: String): ApiEnvelope<EmailCheckResponse>

    @POST("auth/logout")
    suspend fun logout(@Body request: RefreshRequest): Response<Unit>
}
