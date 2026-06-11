package com.mamba.picme.data.local

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mamba.picme.domain.usecase.OcrProcessor
import kotlinx.coroutines.tasks.await

/**
 * ML Kit 驱动的 OCR 处理器实现（Data 层）
 *
 * Domain 层通过 [OcrProcessor] 接口依赖，此类不应被 features 层直接引用。
 * 由 AppContainer 通过依赖注入提供实例。
 */
class MlKitOcrProcessor : OcrProcessor {

    private val textRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    override suspend fun recognizeFromUri(context: Context, uri: Uri): String? {
        return try {
            val inputImage = InputImage.fromFilePath(context, uri)
            val visionText = textRecognizer.process(inputImage).await()
            visionText.text.trim().takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
        // 不在此处 close，让 recognizer 可跨次调用复用
    }

    override fun close() {
        textRecognizer.close()
    }
}

