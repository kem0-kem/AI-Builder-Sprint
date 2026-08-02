package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.RegionApi
import com.apptive.slowtalk.data.remote.RetrofitClient

class RegionRepository(private val api: RegionApi = RetrofitClient.regionApi) {

    // API가 완성되기 전까지 true로 설정하여 테스트합니다.
    private val MOCK_MODE = false

    suspend fun getProvinces(): Result<List<String>> {
        if (MOCK_MODE) {
            return Result.success(listOf("서울특별시", "경기도", "부산광역시", "강원특별자치도"))
        }
        return try {
            Result.success(api.getProvinces())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDistricts(province: String): Result<List<String>> {
        if (MOCK_MODE) {
            val districts = when (province) {
                "서울특별시" -> listOf("강남구", "강동구", "마포구", "송파구")
                "경기도" -> listOf("수원시", "성남시", "고양시", "용인시")
                "부산광역시" -> listOf("해운대구", "수영구", "부산진구")
                else -> listOf("기타 시/군/구")
            }
            return Result.success(districts)
        }
        return try {
            Result.success(api.getDistricts(province))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSubDistricts(province: String, district: String): Result<List<String>> {
        if (MOCK_MODE) {
            val subs = when (district) {
                "마포구" -> listOf("합정동", "상암동", "연남동", "망원동", "성산동")
                "강남구" -> listOf("역삼동", "삼성동", "청담동")
                "수원시" -> listOf("인계동", "매탄동", "영통동")
                else -> listOf("기타 동/읍/면")
            }
            return Result.success(subs)
        }
        return try {
            Result.success(api.getSubDistricts(province, district))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
