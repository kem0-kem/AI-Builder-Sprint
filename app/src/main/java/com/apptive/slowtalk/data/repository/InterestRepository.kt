package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.InterestApi
import com.apptive.slowtalk.data.remote.InterestDto
import com.apptive.slowtalk.data.remote.InterestUpdateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.UserProfileDto
import com.apptive.slowtalk.data.remote.apiData

class InterestRepository(private val api: InterestApi = RetrofitClient.interestApi) {
    suspend fun getAllInterests(): Result<List<InterestDto>> = runCatching {
        apiData { api.getInterests() }
    }

    suspend fun updateMyInterests(interestIds: List<String>): Result<UserProfileDto> = runCatching {
        apiData { api.updateMyInterests(InterestUpdateRequest(interestIds)) }
    }
}
