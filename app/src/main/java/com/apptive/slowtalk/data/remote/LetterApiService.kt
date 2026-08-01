package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface LetterApiService {
    @GET("letters")
    suspend fun getLetters(@Query("type") type: String? = null): List<LetterSummaryDto>

    @GET("letters/{letterId}")
    suspend fun getLetter(@Path("letterId") letterId: Int): LetterDetailDto
}

@Serializable
data class LetterSummaryDto(
    val letterId: Int,
    val type: String,
    val createdAt: String
)

@Serializable
data class LetterDetailDto(
    val letterId: Int,
    val type: String,
    val content: String,
    val createdAt: String
)
