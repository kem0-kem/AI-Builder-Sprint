package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.auth.AuthSession
import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.AuthApi
import com.apptive.slowtalk.data.remote.EmailCheckResponse
import com.apptive.slowtalk.data.remote.LoginRequest
import com.apptive.slowtalk.data.remote.LoginResponse
import com.apptive.slowtalk.data.remote.RefreshRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.SignupRequest
import com.apptive.slowtalk.data.remote.SignupResponse

class AuthRepository(private val api: AuthApi = RetrofitClient.authApi) {
    suspend fun signup(request: SignupRequest): Result<SignupResponse> = runCatching {
        api.signup(request).requireData()
    }

    suspend fun login(request: LoginRequest): Result<LoginResponse> = runCatching {
        api.login(request).requireData().also {
            AuthSession.save(it.accessToken, it.refreshToken)
        }
    }

    suspend fun checkEmail(email: String): Result<Boolean> = runCatching {
        api.checkEmail(email.trim()).requireData().available
    }

    suspend fun checkUsername(username: String): Result<Boolean> = runCatching {
        api.checkUsername(username.trim().lowercase()).requireData().available
    }

    suspend fun logout(): Result<String> = runCatching {
        val refreshToken = AuthSession.refreshToken
        if (refreshToken != null) {
            api.logout(RefreshRequest(refreshToken))
        }
        AuthSession.clear()
        "로그아웃했습니다."
    }
}

private fun <T> ApiEnvelope<T>.requireData(): T {
    if (!ok || data == null) {
        throw IllegalStateException(error?.message ?: "서버 요청을 처리하지 못했습니다.")
    }
    return data
}
