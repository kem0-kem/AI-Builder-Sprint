package com.apptive.slowtalk.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apptive.slowtalk.data.remote.InterestDto
import com.apptive.slowtalk.data.remote.ProfileUpdateRequest
import com.apptive.slowtalk.data.remote.RegionDto
import com.apptive.slowtalk.data.remote.UserProfileDto
import com.apptive.slowtalk.data.repository.InterestRepository
import com.apptive.slowtalk.data.repository.ProfileRepository
import com.apptive.slowtalk.data.repository.RegionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ProfileUiState {
    object Idle : ProfileUiState()
    object Loading : ProfileUiState()
    data class Success(val profile: UserProfileDto) : ProfileUiState()
    data class Error(val message: String) : ProfileUiState()
}

class ProfileViewModel(
    private val profileRepository: ProfileRepository = ProfileRepository(),
    private val regionRepository: RegionRepository = RegionRepository(),
    private val interestRepository: InterestRepository = InterestRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Idle)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _provinces = MutableStateFlow<List<String>>(emptyList())
    val provinces: StateFlow<List<String>> = _provinces.asStateFlow()

    private val _districts = MutableStateFlow<List<String>>(emptyList())
    val districts: StateFlow<List<String>> = _districts.asStateFlow()

    private val _subDistricts = MutableStateFlow<List<String>>(emptyList())
    val subDistricts: StateFlow<List<String>> = _subDistricts.asStateFlow()

    private val _allInterests = MutableStateFlow<List<InterestDto>>(emptyList())
    val allInterests: StateFlow<List<InterestDto>> = _allInterests.asStateFlow()

    fun fetchProfile() {
        viewModelScope.launch {
            _uiState.value = ProfileUiState.Loading
            profileRepository.getMyProfile()
                .onSuccess { _uiState.value = ProfileUiState.Success(it) }
                .onFailure { _uiState.value = ProfileUiState.Error(it.message ?: "Unknown error") }
        }
    }

    fun updateProfile(nickname: String, bio: String, interest: String, province: String, district: String, subDistrict: String?) {
        viewModelScope.launch {
            val updateDto = ProfileUpdateRequest(
                nickname = nickname,
                bio = bio,
                interest = interest,
                region = RegionDto(province, district, subDistrict)
            )
            profileRepository.updateProfile(updateDto)
                .onSuccess { fetchProfile() } // 갱신
                .onFailure { /* 에러 처리 */ }
        }
    }

    fun fetchProvinces() {
        viewModelScope.launch {
            regionRepository.getProvinces()
                .onSuccess { _provinces.value = it }
        }
    }

    fun fetchDistricts(province: String) {
        viewModelScope.launch {
            regionRepository.getDistricts(province)
                .onSuccess { _districts.value = it }
        }
    }

    fun fetchSubDistricts(province: String, district: String) {
        viewModelScope.launch {
            regionRepository.getSubDistricts(province, district)
                .onSuccess { _subDistricts.value = it }
        }
    }

    fun fetchAllInterests() {
        viewModelScope.launch {
            interestRepository.getAllInterests()
                .onSuccess { _allInterests.value = it }
        }
    }

    fun updateInterests(interestIds: List<String>, onComplete: () -> Unit) {
        viewModelScope.launch {
            interestRepository.updateMyInterests(interestIds)
                .onSuccess { 
                    fetchProfile()
                    onComplete()
                }
        }
    }
}
