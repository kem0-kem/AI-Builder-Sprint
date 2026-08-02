package com.apptive.slowtalk.data.remote

import com.apptive.slowtalk.BuildConfig
import com.apptive.slowtalk.data.auth.AuthSession
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

object RetrofitClient {
    // API가 완성되지 않았으므로 베이스 URL은 플레이스홀더로 설정합니다.
    private const val FALLBACK_BASE_URL = "https://api.example.com/"
    private val baseUrl = BuildConfig.API_BASE_URL
        .ifBlank { FALLBACK_BASE_URL }
        .let { if (it.endsWith("/")) it else "$it/" }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val token = AuthSession.accessToken ?: BuildConfig.API_AUTH_TOKEN.takeIf { it.isNotBlank() }
            val authenticatedRequest = if (token == null || request.header("Authorization") != null) {
                request
            } else {
                request.newBuilder().header("Authorization", "Bearer $token").build()
            }
            chain.proceed(authenticatedRequest)
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val profileApi: ProfileApi = retrofit.create(ProfileApi::class.java)
    val regionApi: RegionApi = retrofit.create(RegionApi::class.java)
    val interestApi: InterestApi = retrofit.create(InterestApi::class.java)
    val feedApi: FeedApiService = retrofit.create(FeedApiService::class.java)
    val authApi: AuthApi = retrofit.create(AuthApi::class.java)
}
