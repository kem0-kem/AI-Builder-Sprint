package com.apptive.slowtalk.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable
data class SafetyCheckRequest(
    @SerialName("contentType") val contentType: String,
    val text: String
)

@Serializable
data class SafetyCheckResponse(
    val level: String,
    val title: String,
    val message: String,
    @SerialName("canOverride") val canOverride: Boolean,
    @SerialName("delaySeconds") val delaySeconds: Int = 0,
    @SerialName("operatorReviewRecommended") val operatorReviewRecommended: Boolean = false,
    val available: Boolean = true,
    val categories: List<String> = emptyList(),
    val severity: String = "NONE"
)

interface SafetyApiService {
    @POST("moderation/check")
    suspend fun check(@Body request: SafetyCheckRequest): ApiEnvelope<SafetyCheckResponse>
}
