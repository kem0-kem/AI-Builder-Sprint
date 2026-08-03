package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.InterestApi
import com.apptive.slowtalk.data.remote.InterestDto
import com.apptive.slowtalk.data.remote.InterestUpdateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.requireData

class InterestRepository(private val api: InterestApi = RetrofitClient.interestApi) {

    private val MOCK_MODE = true

    suspend fun getAllInterests(): Result<List<InterestDto>> {
        if (MOCK_MODE) {
            return Result.success(
                listOf(
                    InterestDto("00000000-0000-0000-0000-000000000001", "운동"),
                    InterestDto("00000000-0000-0000-0000-000000000002", "독서"),
                    InterestDto("00000000-0000-0000-0000-000000000003", "영화"),
                    InterestDto("00000000-0000-0000-0000-000000000004", "게임"),
                    InterestDto("00000000-0000-0000-0000-000000000005", "산책"),
                    InterestDto("00000000-0000-0000-0000-000000000006", "카페"),
                    InterestDto("00000000-0000-0000-0000-000000000007", "요리"),
                    InterestDto("00000000-0000-0000-0000-000000000008", "사진"),
                    InterestDto("00000000-0000-0000-0000-000000000009", "여행"),
                    InterestDto("00000000-0000-0000-0000-000000000010", "음악"),
                    InterestDto("00000000-0000-0000-0000-000000000011", "자기계발"),
                    InterestDto("00000000-0000-0000-0000-000000000012", "반려동물")
                )
            )
        }
        return try {
            Result.success(api.getInterests().requireData())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateMyInterests(interestIds: List<String>): Result<List<String>> {
        if (MOCK_MODE) {
            return Result.success(interestIds)
        }
        return try {
            api.updateMyInterests(InterestUpdateRequest(interestIds)).requireData()
            Result.success(interestIds)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
