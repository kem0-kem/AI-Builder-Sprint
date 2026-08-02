package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.AuthApi
import com.apptive.slowtalk.data.remote.EmailCheckResponse
import com.apptive.slowtalk.data.remote.LoginRequest
import com.apptive.slowtalk.data.remote.LoginResponse
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.SignupRequest
import com.apptive.slowtalk.data.remote.SignupResponse

class AuthRepository(private val api: AuthApi = RetrofitClient.authApi) {

    private val MOCK_MODE = true

    suspend fun signup(request: SignupRequest): Result<SignupResponse> {
        if (MOCK_MODE) {
            return Result.success(SignupResponse(1, "회원가입이 완료되었습니다."))
        }
        return try {
            Result.success(api.signup(request))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(request: LoginRequest): Result<LoginResponse> {
        if (MOCK_MODE) {
            return Result.success(LoginResponse("mock-access-token", "mock-refresh-token"))
        }
        return try {
            Result.success(api.login(request))
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
            val response = api.checkEmail(email)
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
            val response = api.checkUsername(username)
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
            val response = api.logout()
            Result.success(response.message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
