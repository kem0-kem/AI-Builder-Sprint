package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.RegionApi
import com.apptive.slowtalk.data.remote.RegionItemDto
import com.apptive.slowtalk.data.remote.RetrofitClient

class RegionRepository(private val api: RegionApi = RetrofitClient.regionApi) {
    private val provinceCodes = mutableMapOf<String, String>()
    private val districtCodes = mutableMapOf<String, String>()

    suspend fun getProvinces(): Result<List<String>> = runCatching {
        api.getProvinces().requireRegionData().also { items ->
            provinceCodes.replaceWith(items)
        }.map { it.name }
    }

    suspend fun getDistricts(province: String): Result<List<String>> = runCatching {
        val provinceCode = provinceCodes[province]
            ?: api.getProvinces().requireRegionData().also { provinceCodes.replaceWith(it) }
                .firstOrNull { it.name == province }?.code
            ?: error("선택한 시·도를 찾을 수 없습니다.")
        api.getDistricts(provinceCode).requireRegionData().also { items ->
            districtCodes.replaceWith(items)
        }.map { it.name }
    }

    suspend fun getSubDistricts(province: String, district: String): Result<List<String>> = runCatching {
        val districtCode = districtCodes[district]
            ?: run {
                val provinceCode = provinceCodes[province]
                    ?: api.getProvinces().requireRegionData().also { provinceCodes.replaceWith(it) }
                        .firstOrNull { it.name == province }?.code
                    ?: error("선택한 시·도를 찾을 수 없습니다.")
                api.getDistricts(provinceCode).requireRegionData().also { districtCodes.replaceWith(it) }
                    .firstOrNull { it.name == district }?.code
            }
            ?: error("선택한 시·군·구를 찾을 수 없습니다.")
        api.getSubDistricts(districtCode).requireRegionData().map { it.name }
    }
}

private fun MutableMap<String, String>.replaceWith(items: List<RegionItemDto>) {
    clear()
    putAll(items.associate { it.name to it.code })
}

private fun <T> ApiEnvelope<T>.requireRegionData(): T {
    if (!ok || data == null) {
        throw IllegalStateException(error?.message ?: "지역 정보를 불러오지 못했습니다.")
    }
    return data
}
