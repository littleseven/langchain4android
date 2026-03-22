package com.picme.features.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OcrHistoryItem(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class OcrUiState(
    val detectedText: String? = null,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val history: List<OcrHistoryItem> = emptyList(),
    val toastMessage: String? = null,
    val imageUri: Uri? = null
)

class OcrViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OcrUiState())
    val uiState = _uiState.asStateFlow()
    
    // 最多保存 10 条历史记录
    private val maxHistorySize = 10
    private val textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 从 Uri 识别图片中的文字
     */
    fun recognizeFromUri(context: Context, imageUri: Uri) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            errorMessage = null,
            imageUri = imageUri
        )
        
        try {
            val inputImage = InputImage.fromFilePath(context, imageUri)
            
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    processRecognizedText(visionText.text)
                }
                .addOnFailureListener { e ->
                    onError("识别失败：${e.message}")
                }
        } catch (e: Exception) {
            onError("图片加载失败：${e.message}")
        }
    }

    /**
     * 处理识别结果
     */
    private fun processRecognizedText(text: String) {
        val trimmedText = text.trim()
        if (trimmedText.isNotBlank()) {
            // 添加到历史记录
            val currentHistory = _uiState.value.history.toMutableList()
            val newItem = OcrHistoryItem(text = trimmedText)
            currentHistory.add(0, newItem)
            
            // 保持最多 10 条记录
            if (currentHistory.size > maxHistorySize) {
                currentHistory.removeAt(currentHistory.lastIndex)
            }
            
            _uiState.value = _uiState.value.copy(
                detectedText = trimmedText,
                isProcessing = false,
                errorMessage = null,
                history = currentHistory,
                toastMessage = "识别成功：${trimmedText.length} 个字"
            )
        } else {
            _uiState.value = _uiState.value.copy(
                detectedText = null,
                isProcessing = false,
                errorMessage = "未检测到文字",
                toastMessage = null
            )
        }
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
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
    
    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }
    
    fun clearHistory() {
        _uiState.value = _uiState.value.copy(history = emptyList())
    }
    
    fun resetState() {
        _uiState.value = _uiState.value.copy(
            detectedText = null,
            imageUri = null,
            errorMessage = null
        )
    }
    
    override fun onCleared() {
        super.onCleared()
        textRecognizer.close()
    }
}
