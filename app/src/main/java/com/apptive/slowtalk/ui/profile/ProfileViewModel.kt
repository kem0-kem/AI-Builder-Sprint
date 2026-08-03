package com.apptive.slowtalk.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apptive.slowtalk.data.remote.InterestDto
import com.apptive.slowtalk.data.remote.ProfileUpdateRequest
import com.apptive.slowtalk.data.remote.RegionOptionDto
import com.apptive.slowtalk.data.remote.RegionPatchDto
import com.apptive.slowtalk.data.remote.UserProfileDto
import com.apptive.slowtalk.data.repository.InterestRepository
import com.apptive.slowtalk.data.repository.ProfileRepository
import com.apptive.slowtalk.data.repository.RegionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ProfileUiState {
    data object Idle : ProfileUiState()
    data object Loading : ProfileUiState()
    data class Success(val profile: UserProfileDto) : ProfileUiState()
    data class Error(val message: String) : ProfileUiState()
}

class ProfileViewModel(
    private val profileRepository: ProfileRepository = ProfileRepository(),
    private val regionRepository: RegionRepository = RegionRepository(),
    private val interestRepository: InterestRepository = InterestRepository(),
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

    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()

    private var provinceOptions: List<RegionOptionDto> = emptyList()
    private var districtOptions: List<RegionOptionDto> = emptyList()
    private var subDistrictOptions: List<RegionOptionDto> = emptyList()
    private var districtProvinceCode: String? = null

    fun fetchProfile() {
        viewModelScope.launch {
            _uiState.value = ProfileUiState.Loading
            profileRepository.getMyProfile()
                .onSuccess {
                    _operationError.value = null
                    _uiState.value = ProfileUiState.Success(it)
                }
                .onFailure { _uiState.value = ProfileUiState.Error(messageFor(it)) }
        }
    }

    fun updateProfile(
        nickname: String,
        bio: String,
        province: String,
        district: String,
        subDistrict: String?,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _operationError.value = null
            val updateDto = ProfileUpdateRequest(
                nickname = nickname,
                bio = bio,
                region = RegionPatchDto(
                    provinceCode = regionCode(province, provinceOptions, RegionLevel.PROVINCE),
                    districtCode = regionCode(district, districtOptions, RegionLevel.DISTRICT),
                    subDistrictCode = subDistrict?.let {
                        regionCode(it, subDistrictOptions, RegionLevel.SUB_DISTRICT)
                    },
                ),
            )
            profileRepository.updateProfile(updateDto)
                .onSuccess {
                    _uiState.value = ProfileUiState.Success(it)
                    onSuccess()
                }
                .onFailure { _operationError.value = messageFor(it) }
        }
    }

    fun fetchProvinces() {
        viewModelScope.launch {
            runCatching { loadProvinceOptions() }
                .onSuccess {
                    _operationError.value = null
                    _provinces.value = it.map(RegionOptionDto::name)
                }
                .onFailure { _operationError.value = messageFor(it) }
        }
    }

    fun fetchDistricts(province: String) {
        viewModelScope.launch {
            districtOptions = emptyList()
            subDistrictOptions = emptyList()
            districtProvinceCode = null
            _districts.value = emptyList()
            _subDistricts.value = emptyList()
            runCatching { loadDistrictOptions(province) }
                .onSuccess {
                    _operationError.value = null
                    _districts.value = it.map(RegionOptionDto::name)
                }
                .onFailure { _operationError.value = messageFor(it) }
        }
    }

    fun fetchSubDistricts(province: String, district: String) {
        viewModelScope.launch {
            subDistrictOptions = emptyList()
            _subDistricts.value = emptyList()
            runCatching {
                val provinceCode = regionCode(
                    province,
                    ensureProvinceOptions(),
                    RegionLevel.PROVINCE,
                )
                if (districtProvinceCode != provinceCode) {
                    loadDistrictOptions(province)
                }
                val districtCode = regionCode(district, districtOptions, RegionLevel.DISTRICT)
                regionRepository.getSubDistricts(districtCode).getOrThrow()
            }
                .onSuccess {
                    _operationError.value = null
                    subDistrictOptions = it
                    _subDistricts.value = it.map(RegionOptionDto::name)
                }
                .onFailure { _operationError.value = messageFor(it) }
        }
    }

    fun fetchAllInterests() {
        viewModelScope.launch {
            interestRepository.getAllInterests()
                .onSuccess {
                    _operationError.value = null
                    _allInterests.value = it
                }
                .onFailure { _operationError.value = messageFor(it) }
        }
    }

    fun updateInterests(interestIds: List<String>, onComplete: () -> Unit) {
        viewModelScope.launch {
            _operationError.value = null
            interestRepository.updateMyInterests(interestIds)
                .onSuccess {
                    _uiState.value = ProfileUiState.Success(it)
                    onComplete()
                }
                .onFailure { _operationError.value = messageFor(it) }
        }
    }

    private fun regionCode(
        value: String,
        options: List<RegionOptionDto>,
        level: RegionLevel,
    ): String = options.firstOrNull { it.name == value || it.code == value }?.code
        ?: profileRegionOption(level)?.takeIf { it.name == value || it.code == value }?.code
        ?: value

    private suspend fun ensureProvinceOptions(): List<RegionOptionDto> =
        provinceOptions.ifEmpty { loadProvinceOptions() }

    private suspend fun loadProvinceOptions(): List<RegionOptionDto> {
        val loaded = regionRepository.getProvinces().getOrThrow()
        provinceOptions = loaded
        _provinces.value = loaded.map(RegionOptionDto::name)
        return loaded
    }

    private suspend fun loadDistrictOptions(province: String): List<RegionOptionDto> {
        val provinceCode = regionCode(
            province,
            ensureProvinceOptions(),
            RegionLevel.PROVINCE,
        )
        val loaded = regionRepository.getDistricts(provinceCode).getOrThrow()
        districtOptions = loaded
        districtProvinceCode = provinceCode
        _districts.value = loaded.map(RegionOptionDto::name)
        return loaded
    }

    private fun profileRegionOption(level: RegionLevel): RegionOptionDto? {
        val profile = (_uiState.value as? ProfileUiState.Success)?.profile ?: return null
        return when (level) {
            RegionLevel.PROVINCE -> profile.region?.province
            RegionLevel.DISTRICT -> profile.region?.district
            RegionLevel.SUB_DISTRICT -> profile.region?.subDistrict
        }
    }

    private fun messageFor(throwable: Throwable): String =
        throwable.message ?: "요청을 처리하지 못했습니다."

    private enum class RegionLevel { PROVINCE, DISTRICT, SUB_DISTRICT }
}
