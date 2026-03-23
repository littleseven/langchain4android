package com.picme.domain.usecase

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrUseCase {
    private val textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognizeFromUri(context: Context, uri: Uri): String? {
        return try {
            val inputImage = InputImage.fromFilePath(context, uri)
            val visionText = textRecognizer.process(inputImage).await()
            visionText.text.trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null // 在 UseCase 层处理异常，返回 null 表示失败
        } finally {
            textRecognizer.close() // 确保资源被释放
        }
    }
}