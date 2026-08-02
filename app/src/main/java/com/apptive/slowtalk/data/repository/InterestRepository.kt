package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.InterestApi
import com.apptive.slowtalk.data.remote.InterestDto
import com.apptive.slowtalk.data.remote.InterestUpdateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient

class InterestRepository(private val api: InterestApi = RetrofitClient.interestApi) {

    private val MOCK_MODE = true

    suspend fun getAllInterests(): Result<List<InterestDto>> {
        if (MOCK_MODE) {
            return Result.success(
                listOf(
                    InterestDto(1, "운동"),
                    InterestDto(2, "독서"),
                    InterestDto(3, "영화"),
                    InterestDto(4, "게임"),
                    InterestDto(5, "산책"),
                    InterestDto(6, "카페"),
                    InterestDto(7, "요리"),
                    InterestDto(8, "사진"),
                    InterestDto(9, "여행"),
                    InterestDto(10, "음악"),
                    InterestDto(11, "자기계발"),
                    InterestDto(12, "반려동물")
                )
            )
        }
        return try {
            Result.success(api.getInterests())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateMyInterests(interestIds: List<Int>): Result<List<Int>> {
        if (MOCK_MODE) {
            return Result.success(interestIds)
        }
        return try {
            val response = api.updateMyInterests(InterestUpdateRequest(interestIds))
            Result.success(response.interestIds)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
