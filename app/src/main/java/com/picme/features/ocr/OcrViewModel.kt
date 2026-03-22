package com.picme.features.ocr

import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OcrUiState(
    val detectedText: Text? = null,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null
)

class OcrViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OcrUiState())
    val uiState = _uiState.asStateFlow()

    fun onTextDetected(text: Text) {
        _uiState.value = _uiState.value.copy(
            detectedText = text,
            isProcessing = false,
            errorMessage = null
        )
    }

    fun onProcessingStart() {
        _uiState.value = _uiState.value.copy(isProcessing = true)
    }

    fun onError(message: String) {
        _uiState.value = _uiState.value.copy(
            errorMessage = message,
            isProcessing = false
        )
    }
}
