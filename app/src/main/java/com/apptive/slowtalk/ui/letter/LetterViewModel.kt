package com.apptive.slowtalk.ui.letter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apptive.slowtalk.data.remote.LetterFeedbackResponse
import com.apptive.slowtalk.data.remote.RegionDto
import com.apptive.slowtalk.data.repository.LetterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class LetterUiState {
    object Idle : LetterUiState()
    object Loading : LetterUiState()
    object Success : LetterUiState()
    data class OcrSuccess(val content: String) : LetterUiState()
    data class Error(val message: String) : LetterUiState()
}

class LetterViewModel(
    private val repository: LetterRepository = LetterRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<LetterUiState>(LetterUiState.Idle)
    val uiState: StateFlow<LetterUiState> = _uiState.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _aiFeedback = MutableStateFlow<LetterFeedbackResponse?>(null)
    val aiFeedback: StateFlow<LetterFeedbackResponse?> = _aiFeedback.asStateFlow()

    private val _isFeedbackLoading = MutableStateFlow(false)
    val isFeedbackLoading: StateFlow<Boolean> = _isFeedbackLoading.asStateFlow()

    fun updateContent(newContent: String) {
        if (_content.value != newContent) {
            _aiFeedback.value = null
        }
        _content.value = newContent
    }

    fun analyzeContent() {
        val text = _content.value.trim()
        if (text.isBlank() || _isFeedbackLoading.value) return
        viewModelScope.launch {
            _isFeedbackLoading.value = true
            repository.getLetterFeedback(text)
                .onSuccess {
                    if (_content.value.trim() == text) _aiFeedback.value = it
                }
            _isFeedbackLoading.value = false
        }
    }

    fun createLetter(match: Boolean, province: String, district: String, subDistrict: String?) {
        viewModelScope.launch {
            _uiState.value = LetterUiState.Loading
            repository.createLetter(
                content = _content.value,
                match = match,
                region = RegionDto(province, district, subDistrict)
            ).onSuccess {
                _uiState.value = LetterUiState.Success
            }.onFailure {
                _uiState.value = LetterUiState.Error(it.message ?: "편지 저장 실패")
            }
        }
    }

    fun performOcr(imageFile: File) {
        viewModelScope.launch {
            _uiState.value = LetterUiState.Loading
            repository.performLetterOcr(imageFile)
                .onSuccess { 
                    _content.value = it
                    _uiState.value = LetterUiState.OcrSuccess(it) 
                }
                .onFailure { 
                    _uiState.value = LetterUiState.Error(it.message ?: "OCR 인식 실패") 
                }
        }
    }

    fun resetState() {
        _uiState.value = LetterUiState.Idle
    }
}
