package com.apptive.slowtalk

import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.SafetyCheckRequest

enum class SafetyLevel {
    SAFE,
    CAUTION,
    INTERVENTION,
    BLOCK,
    EMERGENCY
}

data class SafetyIntervention(
    val level: SafetyLevel,
    val title: String,
    val message: String,
    val canOverride: Boolean,
    val delaySeconds: Int,
    val operatorReviewRecommended: Boolean,
    val available: Boolean,
    val categories: List<String>,
    val severity: String
)

object SafetyApi {
    suspend fun check(contentType: String, text: String): Result<SafetyIntervention> = runCatching {
        val response = requireNotNull(
            RetrofitClient.safetyApi.check(SafetyCheckRequest(contentType, text)).data
        )
        SafetyIntervention(
            level = runCatching { SafetyLevel.valueOf(response.level) }
                .getOrDefault(SafetyLevel.CAUTION),
            title = response.title,
            message = response.message,
            canOverride = response.canOverride,
            delaySeconds = response.delaySeconds,
            operatorReviewRecommended = response.operatorReviewRecommended,
            available = response.available,
            categories = response.categories,
            severity = response.severity
        )
    }
}
