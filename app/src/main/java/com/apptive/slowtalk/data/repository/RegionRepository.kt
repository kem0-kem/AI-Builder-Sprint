package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.RegionApi
import com.apptive.slowtalk.data.remote.RegionOptionDto
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.apiData

class RegionRepository(private val api: RegionApi = RetrofitClient.regionApi) {
    suspend fun getProvinces(): Result<List<RegionOptionDto>> = runCatching {
        apiData { api.getProvinces() }
    }

    suspend fun getDistricts(provinceCode: String): Result<List<RegionOptionDto>> = runCatching {
        apiData { api.getDistricts(provinceCode) }
    }

    suspend fun getSubDistricts(districtCode: String): Result<List<RegionOptionDto>> = runCatching {
        apiData { api.getSubDistricts(districtCode) }
    }
}
