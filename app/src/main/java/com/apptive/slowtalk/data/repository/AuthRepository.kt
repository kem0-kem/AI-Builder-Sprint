package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.auth.AuthSession
import com.apptive.slowtalk.data.remote.AuthApi
import com.apptive.slowtalk.data.remote.LoginRequest
import com.apptive.slowtalk.data.remote.LoginResponse
import com.apptive.slowtalk.data.remote.RefreshRequest
import com.apptive.slowtalk.data.remote.RetrofitClient
import com.apptive.slowtalk.data.remote.SignupRequest
import com.apptive.slowtalk.data.remote.SignupResponse
import com.apptive.slowtalk.data.remote.apiData
import com.apptive.slowtalk.data.remote.apiUnit

class AuthRepository(private val api: AuthApi = RetrofitClient.authApi) {
    suspend fun signup(request: SignupRequest): Result<SignupResponse> = try {
        val response = apiData { api.signup(request) }
        AuthSession.save(response.accessToken, response.refreshToken)
        Result.success(response)
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    suspend fun login(request: LoginRequest): Result<LoginResponse> = try {
        val response = apiData { api.login(request) }
        AuthSession.save(response.accessToken, response.refreshToken)
        Result.success(response)
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    suspend fun checkEmail(email: String): Result<Boolean> = try {
        Result.success(apiData { api.checkEmail(email) }.available)
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    suspend fun checkUsername(username: String): Result<Boolean> = try {
        Result.success(apiData { api.checkUsername(username) }.available)
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    suspend fun logout(): Result<String> {
        val refreshToken = AuthSession.refreshToken
        if (refreshToken == null) {
            AuthSession.clear()
            return Result.success("로그아웃되었습니다.")
        }
        return try {
            apiUnit { api.logout(RefreshRequest(refreshToken)) }
            Result.success("로그아웃되었습니다.")
        } catch (exception: Exception) {
            Result.failure(exception)
        } finally {
            AuthSession.clear()
        }
    }
}
