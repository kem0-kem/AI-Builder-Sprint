package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ProfileApi
import com.apptive.slowtalk.data.remote.ProfileUpdateRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.UserProfileDto
import com.apptive.slowtalk.data.remote.InterestDto
import com.apptive.slowtalk.data.remote.ProfileRegionDto
import com.apptive.slowtalk.data.remote.RegionOptionDto
import com.apptive.slowtalk.data.remote.apiData

class ProfileRepository(private val api: ProfileApi = RetrofitClient.profileApi) {

    private val MOCK_MODE = true

    suspend fun getMyProfile(): Result<UserProfileDto> {
        if (MOCK_MODE) {
            return Result.success(
                UserProfileDto(
                    id = "00000000-0000-0000-0000-000000000001",
                    nickname = "지연",
                    bio = "느리게 걷는 것을 좋아하는 평범한 직장인입니다. 함께 따뜻한 이야기를 나누고 싶어요.",
                    interests = listOf(
                        InterestDto("00000000-0000-0000-0000-000000000005", "산책"),
                        InterestDto("00000000-0000-0000-0000-000000000002", "독서"),
                    ),
                    region = ProfileRegionDto(
                        province = RegionOptionDto("11", "서울특별시"),
                        district = RegionOptionDto("11440", "마포구"),
                        subDistrict = RegionOptionDto("1144066000", "상암동"),
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
            val response = apiData { api.getMyProfile() }
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
            apiData { api.updateProfile(profile) }
            Result.success("프로필이 성공적으로 업데이트되었습니다.")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
