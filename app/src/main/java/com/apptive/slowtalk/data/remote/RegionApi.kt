package com.apptive.slowtalk.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

interface RegionApi {
    @GET("regions/provinces")
    suspend fun getProvinces(): ApiEnvelope<List<RegionOptionDto>>

    @GET("regions/provinces/{province}/districts")
    suspend fun getDistricts(
        @Path("province") province: String
    ): ApiEnvelope<List<RegionOptionDto>>

    @GET("regions/provinces/{province}/districts/{district}/sub-districts")
    suspend fun getSubDistricts(
        @Path("province") province: String,
        @Path("district") district: String
    ): ApiEnvelope<List<RegionOptionDto>>
}
