package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.auth.AuthSession
import com.apptive.slowtalk.data.auth.UsernameContract
import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.ApiError
import com.apptive.slowtalk.data.remote.AuthApi
import com.apptive.slowtalk.data.remote.LoginRequest
import com.apptive.slowtalk.data.remote.RefreshRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.SignupRequest
import com.apptive.slowtalk.data.remote.TokenPairResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Response

class AuthRepository(private val api: AuthApi = RetrofitClient.authApi) {
    suspend fun login(email: String, password: String): Result<Unit> = runCatching {
        api.login(LoginRequest(email.trim(), password)).requireTokenPair().persist()
    }

    suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        username: String?
    ): Result<Unit> = runCatching {
        val normalizedUsername = username?.takeIf { it.isNotBlank() }?.let(UsernameContract::normalize)
        if (normalizedUsername != null && !UsernameContract.isValid(normalizedUsername)) {
            throw AuthException("INVALID_USERNAME")
        }
        api.signup(SignupRequest(email.trim(), password, nickname.trim(), normalizedUsername))
            .bodyOrThrow()
            .tokenPair()
            .persist()
    }

    suspend fun checkUsername(username: String): Result<Boolean> = runCatching {
        val normalized = UsernameContract.normalize(username)
        if (!UsernameContract.isValid(normalized)) throw AuthException("INVALID_USERNAME")
        api.checkUsername(normalized).bodyOrThrow().available
    }

    suspend fun restoreSession(): Boolean {
        val refreshToken = AuthSession.refreshToken ?: return false
        return runCatching {
            api.refresh(RefreshRequest(refreshToken)).requireTokenPair().persist()
            true
        }.getOrElse {
            AuthSession.clear()
            false
        }
    }

    private fun TokenPairResponse.persist() {
        AuthSession.save(accessToken, refreshToken)
    }
}

class AuthException(val code: String) : IllegalStateException(messageFor(code)) {
    companion object {
        private fun messageFor(code: String): String = when (code) {
            "INVALID_CREDENTIALS" -> "이메일 또는 비밀번호를 확인해 주세요."
            "EMAIL_ALREADY_EXISTS" -> "이미 사용 중인 이메일입니다."
            "USERNAME_ALREADY_EXISTS" -> "이미 사용 중인 아이디입니다."
            "INVALID_USERNAME" -> "아이디는 영문 소문자, 숫자, 밑줄 3~30자로 입력해 주세요."
            else -> "인증 요청을 처리하지 못했습니다."
        }
    }
}

private fun <T> Response<ApiEnvelope<T>>.bodyOrThrow(): T {
    val envelope = body()
    if (!isSuccessful || envelope?.ok != true || envelope.data == null) {
        val code = envelope?.error?.code ?: errorBody()?.string()?.let { body ->
            runCatching { errorJson.decodeFromString<ErrorEnvelope>(body).error?.code }.getOrNull()
        } ?: "AUTH_REQUEST_FAILED"
        throw AuthException(code)
    }
    return envelope.data
}

private fun Response<ApiEnvelope<TokenPairResponse>>.requireTokenPair(): TokenPairResponse = bodyOrThrow()

private val errorJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ErrorEnvelope(val error: ApiError? = null)
