package com.apptive.slowtalk.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfilePayloadDto(
    val id: String? = null,
    val nickname: String,
    val bio: String? = null,
    val interests: List<ProfileInterestDto> = emptyList(),
    val region: RegionDto? = null,
    val statistics: StatisticsDto? = null
)

@Serializable
data class ProfileInterestDto(val id: String, val name: String)

@Serializable
data class ProfilePatchRequest(
    val nickname: String,
    val bio: String
)

@Serializable
data class UserProfileDto(
    val nickname: String,
    val bio: String,
    val interest: String,
    val region: RegionDto,
    val statistics: StatisticsDto? = null
)

@Serializable
data class ProfileUpdateRequest(
    val nickname: String,
    val bio: String,
    val interest: String,
    val region: RegionDto
)

@Serializable
data class RegionDto(
    val province: String,
    val district: String,
    val subDistrict: String?
)

@Serializable
data class StatisticsDto(
    val sentLetters: Int,
    val receivedLetters: Int,
    val matchCount: Int
)

@Serializable
data class ProfileUpdateResponse(
    val message: String
)

@Serializable
data class InterestDto(
    @SerialName("id") val interestId: String,
    val name: String
)

@Serializable
data class InterestUpdateRequest(
    val interestIds: List<String>
)

@Serializable
data class InterestUpdateResponse(
    val interestIds: List<String>
)
