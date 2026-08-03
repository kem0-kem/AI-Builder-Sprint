package com.apptive.slowtalk.ui.auth

import com.apptive.slowtalk.data.auth.AuthSession
import com.apptive.slowtalk.data.auth.FakeAuthTokenStore
import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.AuthApi
import com.apptive.slowtalk.data.remote.EmailCheckResponse
import com.apptive.slowtalk.data.remote.LoginRequest
import com.apptive.slowtalk.data.remote.LoginResponse
import com.apptive.slowtalk.data.remote.RefreshRequest
import com.apptive.slowtalk.data.remote.SignupRequest
import com.apptive.slowtalk.data.remote.SignupResponse
import com.apptive.slowtalk.data.repository.AuthRepository
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class AuthViewModelTest {
    @Before
    fun setUp() {
        AuthSession.initialize(FakeAuthTokenStore())
    }

    @After
    fun tearDown() {
        AuthSession.resetForTest()
    }

    @Test
    fun `signup success publishes authenticated session and invokes navigation`() = runBlocking {
        val viewModel = AuthViewModel(AuthRepository(FakeAuthApi()))
        var navigated = false

        viewModel.signupNow("Test_User", "Test User", "person@example.com", "password123") {
            navigated = true
        }

        assertTrue(navigated)
        assertTrue(AuthSession.isAuthenticated)
        assertTrue(viewModel.uiState.value is AuthUiState.Idle)
    }

    @Test
    fun `logout network failure clears session and invokes navigation`() = runBlocking {
        AuthSession.save("access-value", "refresh-value")
        val viewModel = AuthViewModel(AuthRepository(FakeAuthApi(logoutFails = true)))
        var navigated = false

        viewModel.logoutNow { navigated = true }

        assertTrue(navigated)
        assertFalse(AuthSession.isAuthenticated)
        assertTrue(viewModel.uiState.value is AuthUiState.Idle)
    }
}

private class FakeAuthApi(
    private val logoutFails: Boolean = false,
) : AuthApi {
    override suspend fun signup(request: SignupRequest): ApiEnvelope<SignupResponse> =
        ApiEnvelope(
            ok = true,
            data = SignupResponse(
                userId = "00000000-0000-0000-0000-000000000001",
                accessToken = "access-value",
                refreshToken = "refresh-value",
                expiresIn = 3600,
            ),
        )

    override suspend fun login(request: LoginRequest): ApiEnvelope<LoginResponse> = error("not used")

    override suspend fun refresh(request: RefreshRequest): ApiEnvelope<LoginResponse> = error("not used")

    override suspend fun checkEmail(email: String): ApiEnvelope<EmailCheckResponse> = error("not used")

    override suspend fun checkUsername(username: String): ApiEnvelope<EmailCheckResponse> = error("not used")

    override suspend fun logout(request: RefreshRequest): Response<Unit> {
        if (logoutFails) throw IOException("offline")
        return Response.success(Unit)
    }
}
