package com.apptive.slowtalk.data.remote

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

object RetrofitClient {
    // API가 완성되지 않았으므로 베이스 URL은 플레이스홀더로 설정합니다.
    private const val BASE_URL = "https://api.example.com/" 

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
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val profileApi: ProfileApi = retrofit.create(ProfileApi::class.java)
    val regionApi: RegionApi = retrofit.create(RegionApi::class.java)
    val interestApi: InterestApi = retrofit.create(InterestApi::class.java)
    val feedApi: FeedApiService = retrofit.create(FeedApiService::class.java)
    val chatApi: ChatApiService = retrofit.create(ChatApiService::class.java)
    val letterApi: LetterApiService = retrofit.create(LetterApiService::class.java)
    val meetingApi: MeetingApiService = retrofit.create(MeetingApiService::class.java)

    fun openChatWebSocket(chatRoomId: Int, listener: WebSocketListener): WebSocket {
        val socketBaseUrl = BASE_URL
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/')
        val request = Request.Builder()
            .url("$socketBaseUrl/ws/chat/$chatRoomId")
            .build()
        return okHttpClient.newWebSocket(request, listener)
    }
}
