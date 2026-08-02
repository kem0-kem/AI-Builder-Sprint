package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.InterestApi
import com.apptive.slowtalk.data.remote.InterestDto
import com.apptive.slowtalk.data.remote.InterestUpdateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient

class InterestRepository(private val api: InterestApi = RetrofitClient.interestApi) {
    suspend fun getAllInterests(): Result<List<InterestDto>> = runCatching {
        api.getInterests().requireInterestData()
    }

    suspend fun updateMyInterests(interestIds: List<String>): Result<List<String>> = runCatching {
        api.updateMyInterests(InterestUpdateRequest(interestIds)).requireInterestData()
        interestIds
    }
}

private fun <T> ApiEnvelope<T>.requireInterestData(): T {
    if (!ok || data == null) {
        throw IllegalStateException(error?.message ?: "관심사 요청을 처리하지 못했습니다.")
    }
    return data
}
