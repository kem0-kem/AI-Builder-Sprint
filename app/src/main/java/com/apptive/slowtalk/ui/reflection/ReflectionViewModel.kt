package com.apptive.slowtalk.ui.reflection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apptive.slowtalk.data.remote.ReportFeedbackResponse
import com.apptive.slowtalk.data.repository.ReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class ReflectionUiState {
    object Idle : ReflectionUiState()
    object Loading : ReflectionUiState()
    data class Success(
        val reportId: String,
        val feedback: ReportFeedbackResponse,
    ) : ReflectionUiState()
    data class OcrSuccess(val content: String) : ReflectionUiState()
    data class FeedbackSuccess(val feedback: ReportFeedbackResponse) : ReflectionUiState()
    data class Error(val message: String) : ReflectionUiState()
}

class ReflectionViewModel(
    private val repository: ReportRepository = ReportRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReflectionUiState>(ReflectionUiState.Idle)
    val uiState: StateFlow<ReflectionUiState> = _uiState.asStateFlow()

    fun createReport(content: String) {
        viewModelScope.launch {
            _uiState.value = ReflectionUiState.Loading
            runCatching {
                val feedback = repository.getReportFeedback(content).getOrThrow()
                val reportId = repository.createReport(content).getOrThrow()
                reportId to feedback
            }
                .onSuccess { (reportId, feedback) ->
                    _uiState.value = ReflectionUiState.Success(reportId, feedback)
                }
                .onFailure { _uiState.value = ReflectionUiState.Error(it.message ?: "Failed to create report") }
        }
    }

    fun performOcr(imageFile: File) {
        viewModelScope.launch {
            _uiState.value = ReflectionUiState.Loading
            repository.performOcr(imageFile)
                .onSuccess { _uiState.value = ReflectionUiState.OcrSuccess(it) }
                .onFailure { _uiState.value = ReflectionUiState.Error(it.message ?: "OCR failed") }
        }
    }

    fun fetchFeedback(content: String) {
        viewModelScope.launch {
            _uiState.value = ReflectionUiState.Loading
            repository.getReportFeedback(content)
                .onSuccess { _uiState.value = ReflectionUiState.FeedbackSuccess(it) }
                .onFailure { _uiState.value = ReflectionUiState.Error(it.message ?: "Failed to get feedback") }
        }
    }

    fun resetState() {
        _uiState.value = ReflectionUiState.Idle
    }
}
