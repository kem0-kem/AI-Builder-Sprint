package com.apptive.slowtalk.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

interface InterestApi {
    @GET("interests")
    suspend fun getInterests(): ApiEnvelope<List<InterestDto>>

    @PUT("users/me/interests")
    suspend fun updateMyInterests(
        @Body request: InterestUpdateRequest
    ): ApiEnvelope<UserProfileDto>
}
