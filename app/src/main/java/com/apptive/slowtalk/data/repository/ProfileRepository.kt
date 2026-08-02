package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ProfileApi
import com.apptive.slowtalk.data.remote.ProfileUpdateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.UserProfileDto

class ProfileRepository(private val api: ProfileApi = RetrofitClient.profileApi) {

    private val MOCK_MODE = false

    suspend fun getMyProfile(): Result<UserProfileDto> {
        if (MOCK_MODE) {
            return Result.success(
                UserProfileDto(
                    nickname = "지연",
                    bio = "느리게 걷는 것을 좋아하는 평범한 직장인입니다. 함께 따뜻한 이야기를 나누고 싶어요.",
                    interest = "산책, 독서, 커피",
                    region = com.apptive.slowtalk.data.remote.RegionDto(
                        province = "서울특별시",
                        district = "마포구",
                        subDistrict = "상암동"
                    ),
                    statistics = com.apptive.slowtalk.data.remote.StatisticsDto(
                        sentLetters = 12,
                        receivedLetters = 8,
                        matchCount = 5
                    )
                )
            )
        }
        return try {
            val response = api.getMyProfile()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateProfile(profile: ProfileUpdateRequest): Result<String> {
        if (MOCK_MODE) {
            return Result.success("프로필이 성공적으로 업데이트되었습니다. (Mock)")
        }
        return try {
            val response = api.updateProfile(profile)
            Result.success(response.message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
