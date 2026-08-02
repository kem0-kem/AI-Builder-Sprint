package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.ProfileApi
import com.apptive.slowtalk.data.remote.ProfilePatchRequest
import com.apptive.slowtalk.data.remote.ProfilePayloadDto
import com.apptive.slowtalk.data.remote.ProfileUpdateRequest
import com.apptive.slowtalk.data.remote.RegionDto
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.UserProfileDto

class ProfileRepository(private val api: ProfileApi = RetrofitClient.profileApi) {
    suspend fun getMyProfile(): Result<UserProfileDto> = runCatching {
        api.getMyProfile().requireData().toUiProfile()
    }

    suspend fun updateProfile(profile: ProfileUpdateRequest): Result<String> = runCatching {
        api.updateProfile(
            ProfilePatchRequest(
                nickname = profile.nickname,
                bio = profile.bio,
                region = profile.region
            )
        ).requireData()
        "프로필을 수정했습니다."
    }
}

private fun ProfilePayloadDto.toUiProfile(): UserProfileDto = UserProfileDto(
    nickname = nickname,
    bio = bio.orEmpty(),
    interest = interests.joinToString(", ") { it.name },
    region = region?.let {
        RegionDto(
            province = it.province.name,
            district = it.district.name,
            subDistrict = it.subDistrict?.name
        )
    } ?: RegionDto(province = "", district = "", subDistrict = null),
    statistics = statistics
)

private fun <T> ApiEnvelope<T>.requireData(): T {
    if (!ok || data == null) {
        throw IllegalStateException(error?.message ?: "프로필을 불러오지 못했습니다.")
    }
    return data
}
