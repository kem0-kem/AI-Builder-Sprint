package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ProfileApi
import com.apptive.slowtalk.data.remote.ProfileUpdateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.UserProfileDto
import com.apptive.slowtalk.data.remote.apiData

class ProfileRepository(private val api: ProfileApi = RetrofitClient.profileApi) {
    suspend fun getMyProfile(): Result<UserProfileDto> = runCatching {
        apiData { api.getMyProfile() }
    }

    suspend fun updateProfile(profile: ProfileUpdateRequest): Result<UserProfileDto> = runCatching {
        apiData { api.updateProfile(profile) }
    }
}
