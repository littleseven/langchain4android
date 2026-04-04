package com.picme.domain.usecase

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

interface OcrProcessor {
    suspend fun recognizeFromUri(context: Context, uri: Uri): String?

    fun close()
}

class OcrUseCase : OcrProcessor {
    private val textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun recognizeFromUri(context: Context, uri: Uri): String? {
        return try {
            val inputImage = InputImage.fromFilePath(context, uri)
            val visionText = textRecognizer.process(inputImage).await()
            visionText.text.trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        // 不在这里 close，让 recognizer 可以复用
    }
    
    override fun close() {
        textRecognizer.close()
    }
}