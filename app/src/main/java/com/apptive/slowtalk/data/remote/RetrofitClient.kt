package com.apptive.slowtalk.data.remote

import com.apptive.slowtalk.BuildConfig
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

private const val DEFAULT_LOCAL_API_BASE_URL = "http://10.0.2.2:8000/api/v1/"

internal fun normalizeBaseUrl(value: String): String =
    value.trim().trimEnd('/') + "/"

internal fun configuredBaseUrl(value: String): String =
    normalizeBaseUrl(value.ifBlank { DEFAULT_LOCAL_API_BASE_URL })

internal fun webSocketBaseUrl(apiBaseUrl: String): String =
    normalizeBaseUrl(apiBaseUrl)
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

object RetrofitClient {
    internal val baseUrl = configuredBaseUrl(BuildConfig.API_BASE_URL)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttpClient = OkHttpClient.Builder()
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
    val chatApi: ChatApiService = retrofit.create(ChatApiService::class.java)
    val letterApi: LetterApiService = retrofit.create(LetterApiService::class.java)
    val authApi: AuthApi = retrofit.create(AuthApi::class.java)
    val meetingApi: MeetingApiService = retrofit.create(MeetingApiService::class.java)
    val reportApi: ReportApi = retrofit.create(ReportApi::class.java)

    fun openChatWebSocket(chatRoomId: Int, listener: WebSocketListener): WebSocket {
        val socketBaseUrl = webSocketBaseUrl(baseUrl).trimEnd('/')
        val request = Request.Builder()
            .url("$socketBaseUrl/ws/chat/$chatRoomId")
            .build()
        return okHttpClient.newWebSocket(request, listener)
    }
}
