package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

@Serializable
data class RegionItemDto(val code: String, val name: String)

interface RegionApi {
    @GET("regions/provinces")
    suspend fun getProvinces(): ApiEnvelope<List<RegionItemDto>>

    @GET("regions/provinces/{provinceCode}/districts")
    suspend fun getDistricts(
        @Path("provinceCode") provinceCode: String
    ): ApiEnvelope<List<RegionItemDto>>

    @GET("regions/districts/{districtCode}/sub-districts")
    suspend fun getSubDistricts(
        @Path("districtCode") districtCode: String
    ): ApiEnvelope<List<RegionItemDto>>
}
