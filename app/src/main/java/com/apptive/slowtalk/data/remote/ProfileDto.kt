package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class UserProfileDto(
    val id: String,
    val nickname: String,
    val bio: String? = null,
    val interests: List<InterestDto> = emptyList(),
    val region: ProfileRegionDto? = null,
    val statistics: StatisticsDto? = null
) {
    val interest: String
        get() = interests.joinToString(", ") { it.name }
}

@Serializable
data class ProfileUpdateRequest(
    val nickname: String? = null,
    val bio: String? = null,
    val region: RegionPatchDto? = null,
)

@Serializable
data class RegionDto(
    val province: String,
    val district: String,
    val subDistrict: String? = null,
)

@Serializable
data class ProfileRegionDto(
    val province: RegionOptionDto,
    val district: RegionOptionDto,
    val subDistrict: RegionOptionDto? = null,
)

@Serializable
data class RegionOptionDto(
    val code: String,
    val name: String,
)

@Serializable
data class RegionPatchDto(
    val provinceCode: String,
    val districtCode: String,
    val subDistrictCode: String? = null,
)

@Serializable
data class StatisticsDto(
    val sentLetters: Int,
    val receivedLetters: Int,
    val matchCount: Int
)

@Serializable
data class InterestDto(
    val id: String,
    val name: String
) {
    val interestId: String
        get() = id
}

@Serializable
data class InterestUpdateRequest(
    val interestIds: List<String>
)
