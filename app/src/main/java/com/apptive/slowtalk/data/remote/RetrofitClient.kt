package com.apptive.slowtalk.data.remote

import com.apptive.slowtalk.BuildConfig
import com.apptive.slowtalk.data.auth.AuthSession
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
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
}
