package com.apptive.slowtalk.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

interface ProfileApi {
    @GET("users/me")
    suspend fun getMyProfile(): UserProfileDto

    @PATCH("users/me")
    suspend fun updateProfile(
        @Body request: ProfileUpdateRequest
    ): ProfileUpdateResponse
}
