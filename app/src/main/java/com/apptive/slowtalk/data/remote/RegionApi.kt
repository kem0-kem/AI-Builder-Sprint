package com.apptive.slowtalk.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

interface RegionApi {
    @GET("regions/provinces")
    suspend fun getProvinces(): ApiEnvelope<List<RegionOptionDto>>

    @GET("regions/provinces/{provinceCode}/districts")
    suspend fun getDistricts(
        @Path("provinceCode") provinceCode: String,
    ): ApiEnvelope<List<RegionOptionDto>>

    @GET("regions/districts/{districtCode}/sub-districts")
    suspend fun getSubDistricts(
        @Path("districtCode") districtCode: String,
    ): ApiEnvelope<List<RegionOptionDto>>
}
