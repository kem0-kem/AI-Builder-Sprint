package com.apptive.slowtalk.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apptive.slowtalk.data.remote.LoginRequest
import com.apptive.slowtalk.data.remote.SignupRequest
import com.apptive.slowtalk.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val message: String) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

class AuthViewModel(
    private val repository: AuthRepository = AuthRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            repository.login(LoginRequest(email, password))
                .onSuccess {
                    _uiState.value = AuthUiState.Idle
                    onSuccess()
                }
                .onFailure {
                    _uiState.value = AuthUiState.Error(it.message ?: "로그인 실패")
                }
        }
    }

    fun signup(nickname: String, email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            repository.signup(SignupRequest(email, password, nickname))
                .onSuccess {
                    _uiState.value = AuthUiState.Idle
                    onSuccess()
                }
                .onFailure {
                    _uiState.value = AuthUiState.Error(it.message ?: "회원가입 실패")
                }
        }
    }

    fun checkEmail(email: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            repository.checkEmail(email)
                .onSuccess { available ->
                    onResult(available)
                }
                .onFailure {
                    // 에러 시 기본적으로 중복된 것으로 처리하거나 에러 메시지 표시
                    onResult(false)
                }
        }
    }

    fun logout(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            repository.logout()
                .onSuccess {
                    _uiState.value = AuthUiState.Idle
                    onSuccess()
                }
                .onFailure {
                    _uiState.value = AuthUiState.Error(it.message ?: "로그아웃 실패")
                }
        }
    }
}
