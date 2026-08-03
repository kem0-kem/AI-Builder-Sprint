package com.apptive.slowtalk.data.remote

import com.apptive.slowtalk.BuildConfig
import com.apptive.slowtalk.data.auth.AuthSession
import com.apptive.slowtalk.data.auth.AuthTokens
import kotlinx.serialization.encodeToString
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

private const val DEFAULT_LOCAL_API_BASE_URL = "http://10.0.2.2:8000/api/v1/"

internal fun normalizeBaseUrl(value: String): String =
    value.trim().trimEnd('/') + "/"

internal fun configuredBaseUrl(value: String, isDebug: Boolean): String {
    val configuredValue = if (isDebug) {
        value.ifBlank { DEFAULT_LOCAL_API_BASE_URL }
    } else {
        value
    }
    require(configuredValue.isNotBlank()) {
        "A release API base URL must be configured at build time."
    }
    val normalizedValue = normalizeBaseUrl(configuredValue)
    require(isDebug || normalizedValue.startsWith("https://", ignoreCase = true)) {
        "The release API base URL must use HTTPS."
    }
    return normalizedValue
}

internal fun webSocketBaseUrl(apiBaseUrl: String): String =
    normalizeBaseUrl(apiBaseUrl)
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

internal fun createOkHttpClient(
    isDebug: Boolean,
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
    tokenRefresher: AuthTokenRefresher = AuthTokenRefresher { null },
): OkHttpClient =
    OkHttpClient.Builder()
        .authenticator(SessionAuthenticator(tokenRefresher))
        .apply {
            addInterceptor { chain ->
                val original = chain.request()
                val accessToken = AuthSession.accessToken
                val request = if (
                    accessToken.isNullOrBlank() ||
                    original.header("Authorization") != null ||
                    isPublicAuthPath(original.url.encodedPath)
                ) {
                    original
                } else {
                    original.newBuilder()
                        .header("Authorization", "Bearer $accessToken")
                        .tag(LocalAuthToken::class.java, LocalAuthToken(accessToken))
                        .build()
                }
                chain.proceed(request)
            }
            if (isDebug) {
                addInterceptor(
                    HttpLoggingInterceptor(logger).apply {
                        redactHeader("Authorization")
                        level = HttpLoggingInterceptor.Level.BASIC
                    },
                )
            }
        }
        .build()

internal fun isPublicAuthPath(encodedPath: String): Boolean =
    encodedPath.trimEnd('/').ifBlank { "/" } in PUBLIC_AUTH_PATHS

private val PUBLIC_AUTH_PATHS = setOf(
    "/auth/signup",
    "/auth/login",
    "/auth/email-availability",
    "/auth/check-username",
    "/auth/token/refresh",
    "/api/v1/auth/signup",
    "/api/v1/auth/login",
    "/api/v1/auth/email-availability",
    "/api/v1/auth/check-username",
    "/api/v1/auth/token/refresh",
)

fun interface AuthTokenRefresher {
    fun refresh(refreshToken: String): AuthTokens?
}

private data class LocalAuthToken(val accessToken: String)

private class SessionAuthenticator(
    private val tokenRefresher: AuthTokenRefresher,
) : okhttp3.Authenticator {
    private val refreshLock = Any()

    override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): Request? {
        val attached = response.request.tag(LocalAuthToken::class.java) ?: return null
        if (responseCount(response) >= 2) {
            AuthSession.compareAndClear(attached.accessToken)
            return null
        }
        synchronized(refreshLock) {
            val current = AuthSession.tokens.value ?: return null
            if (current.accessToken != attached.accessToken) {
                return response.request.withLocalToken(current.accessToken)
            }
            val rotated = runCatching {
                tokenRefresher.refresh(current.refreshToken)
            }.getOrNull()
            if (rotated == null) {
                AuthSession.compareAndClear(attached.accessToken)
                return null
            }
            return runCatching {
                AuthSession.save(rotated.accessToken, rotated.refreshToken)
                response.request.withLocalToken(rotated.accessToken)
            }.getOrElse {
                AuthSession.compareAndClear(attached.accessToken)
                null
            }
        }
    }

    private fun responseCount(response: okhttp3.Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count += 1
            prior = prior.priorResponse
        }
        return count
    }
}

private fun Request.withLocalToken(accessToken: String): Request = newBuilder()
    .header("Authorization", "Bearer $accessToken")
    .tag(LocalAuthToken::class.java, LocalAuthToken(accessToken))
    .build()

internal fun createBackendTokenRefresher(apiBaseUrl: String): AuthTokenRefresher {
    val refreshClient = OkHttpClient.Builder().build()
    val refreshUrl = normalizeBaseUrl(apiBaseUrl) + "auth/token/refresh"
    return AuthTokenRefresher { refreshToken ->
        val payload = apiJson.encodeToString(RefreshRequest(refreshToken))
        val request = Request.Builder()
            .url(refreshUrl)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        refreshClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@AuthTokenRefresher null
            val body = response.body?.string() ?: return@AuthTokenRefresher null
            val envelope = apiJson.decodeFromString<ApiEnvelope<LoginResponse>>(body)
            val pair = envelope.requireData()
            AuthTokens(pair.accessToken, pair.refreshToken)
        }
    }
}

object RetrofitClient {
    internal val baseUrl = configuredBaseUrl(BuildConfig.API_BASE_URL, BuildConfig.DEBUG)

    private val okHttpClient = createOkHttpClient(
        BuildConfig.DEBUG,
        tokenRefresher = createBackendTokenRefresher(baseUrl),
    )

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
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

    fun openChatWebSocket(chatRoomId: String, listener: WebSocketListener): WebSocket {
        val socketBaseUrl = webSocketBaseUrl(baseUrl).trimEnd('/')
        val request = Request.Builder()
            .url("$socketBaseUrl/ws/chat/$chatRoomId")
            .build()
        return okHttpClient.newWebSocket(request, listener)
    }
}
