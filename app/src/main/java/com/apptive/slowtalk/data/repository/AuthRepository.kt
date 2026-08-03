package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.AuthApi
import com.apptive.slowtalk.data.remote.EmailCheckResponse
import com.apptive.slowtalk.data.remote.LoginRequest
import com.apptive.slowtalk.data.remote.LoginResponse
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.SignupRequest
import com.apptive.slowtalk.data.remote.SignupResponse
import com.apptive.slowtalk.data.remote.apiData
import com.apptive.slowtalk.data.remote.apiUnit

class AuthRepository(private val api: AuthApi = RetrofitClient.authApi) {

    private val MOCK_MODE = true

    suspend fun signup(request: SignupRequest): Result<SignupResponse> {
        if (MOCK_MODE) {
            return Result.success(
                SignupResponse(
                    userId = "00000000-0000-0000-0000-000000000001",
                    accessToken = "mock-access-token",
                    refreshToken = "mock-refresh-token",
                    expiresIn = 3600,
                ),
            )
        }
        return try {
            Result.success(apiData { api.signup(request) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(request: LoginRequest): Result<LoginResponse> {
        if (MOCK_MODE) {
            return Result.success(LoginResponse("mock-access-token", "mock-refresh-token", expiresIn = 3600))
        }
        return try {
            Result.success(apiData { api.login(request) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkEmail(email: String): Result<Boolean> {
        if (MOCK_MODE) {
            // "test@example.com"만 중복된 것으로 처리
            return Result.success(email != "test@example.com")
        }
        return try {
            val response = apiData { api.checkEmail(email) }
            Result.success(response.available)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkUsername(username: String): Result<Boolean> {
        if (MOCK_MODE) {
            // "admin"만 중복된 것으로 처리
            return Result.success(username != "admin")
        }
        return try {
            val response = apiData { api.checkUsername(username) }
            Result.success(response.available)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout(): Result<String> {
        if (MOCK_MODE) {
            return Result.success("로그아웃 되었습니다.")
        }
        return try {
            apiUnit { api.logout() }
            Result.success("로그아웃 되었습니다.")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
