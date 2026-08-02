package com.apptive.slowtalk.data.remote

import com.apptive.slowtalk.BuildConfig
import com.apptive.slowtalk.data.auth.AuthSession
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object RetrofitClient {
    private const val FALLBACK_BASE_URL = "https://api.example.com/"
    private val baseUrl = BuildConfig.API_BASE_URL
        .ifBlank { FALLBACK_BASE_URL }
        .let { if (it.endsWith("/")) it else "$it/" }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val refreshLock = Any()
    private val refreshClient = OkHttpClient.Builder().build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val token = AuthSession.accessToken
                ?: BuildConfig.API_AUTH_TOKEN.takeIf { it.isNotBlank() }
            val authenticatedRequest = if (token == null || request.header("Authorization") != null) {
                request
            } else {
                request.newBuilder().header("Authorization", "Bearer $token").build()
            }
            chain.proceed(authenticatedRequest)
        }
        .authenticator(Authenticator { _, response ->
            if (response.retryCount() >= 2) return@Authenticator null
            val failedToken = response.request.header("Authorization")
                ?.removePrefix("Bearer ")
            val renewedToken = renewTokens(failedToken) ?: return@Authenticator null
            response.request.newBuilder()
                .header("Authorization", "Bearer $renewedToken")
                .build()
        })
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    redactHeader("Authorization")
                    level = HttpLoggingInterceptor.Level.BODY
                })
            }
        }
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
    val chatApi: ChatApiService = retrofit.create(ChatApiService::class.java)
    val letterApi: LetterApiService = retrofit.create(LetterApiService::class.java)
    val authApi: AuthApi = retrofit.create(AuthApi::class.java)
    val meetingApi: MeetingApiService = retrofit.create(MeetingApiService::class.java)
    val reportApi: ReportApi = retrofit.create(ReportApi::class.java)

    fun openChatWebSocket(chatRoomId: Int, listener: WebSocketListener): WebSocket {
        val socketBaseUrl = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/')
        val request = Request.Builder()
            .url("$socketBaseUrl/ws/chat/$chatRoomId")
            .apply {
                AuthSession.accessToken?.let { header("Authorization", "Bearer $it") }
            }
            .build()
        return okHttpClient.newWebSocket(request, listener)
    }

    private fun renewTokens(failedToken: String?): String? = synchronized(refreshLock) {
        val currentAccessToken = AuthSession.accessToken
        if (!currentAccessToken.isNullOrBlank() && currentAccessToken != failedToken) {
            return@synchronized currentAccessToken
        }
        val currentRefreshToken = AuthSession.refreshToken ?: return@synchronized null
        val requestBody = json.encodeToString(RefreshRequest(currentRefreshToken))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${baseUrl}auth/token/refresh")
            .post(requestBody)
            .build()

        runCatching {
            refreshClient.newCall(request).execute().use { refreshResponse ->
                if (!refreshResponse.isSuccessful) {
                    if (refreshResponse.code == 400 || refreshResponse.code == 401) {
                        AuthSession.clear()
                    }
                    return@use null
                }
                val body = refreshResponse.body?.string() ?: return@use null
                val envelope = json.decodeFromString<ApiEnvelope<LoginResponse>>(body)
                val renewed = envelope.data?.takeIf { envelope.ok } ?: return@use null
                AuthSession.save(renewed.accessToken, renewed.refreshToken)
                renewed.accessToken
            }
        }.getOrNull()
    }

    private fun Response.retryCount(): Int {
        var count = 1
        var previous = priorResponse
        while (previous != null) {
            count++
            previous = previous.priorResponse
        }
        return count
    }
}
