package com.mamba.picme.data.indexing

import android.graphics.Bitmap
import com.mamba.picme.core.common.Logger
import java.io.File
import java.nio.FloatBuffer

/**
 * MobileFaceNet 人脸嵌入提取器
 *
 * 使用 ONNX Runtime 运行 MobileFaceNet 模型，
 * 将人脸图片转换为 512 维 embedding 向量。
 *
 * 模型由 LlmModelDownloadManager 统一下载，路径在:
 * {filesDir}/llm_models/picme-face-embedding-onnx/w600k_mbf.onnx
 *
 * 输入: 112x112 RGB 人脸图像
 * 输出: 512 维 float32 特征向量（L2 归一化）
 */
class MobileFaceNetEmbedder(private val modelFile: File) {

    companion object {
        private const val TAG = "PicMe:MobileFaceNet"
        private const val INPUT_SIZE = 112
        private const val EMBEDDING_DIM = 512
    }

    val isModelReady: Boolean
        get() = modelFile.exists() && modelFile.length() > 100_000

    private var ortEnv: ai.onnxruntime.OrtEnvironment? = null
    private var ortSession: ai.onnxruntime.OrtSession? = null

    fun initialize(): Boolean {
        if (ortSession != null) return true
        if (!isModelReady) {
            Logger.w(TAG, "Model not found at ${modelFile.absolutePath}")
            return false
        }
        return try {
            ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val opts = ai.onnxruntime.OrtSession.SessionOptions()
            ortSession = ortEnv?.createSession(modelFile.absolutePath, opts)
            Logger.i(TAG, "Model loaded, inputs=${ortSession?.inputNames?.size}")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load ONNX model", e)
            ortEnv?.close()
            ortEnv = null
            false
        }
    }

    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        val session = ortSession ?: return null
        val env = ortEnv ?: return null

        val resized = if (faceBitmap.width != INPUT_SIZE || faceBitmap.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        } else faceBitmap

        return try {
            val tensor = bitmapToInputTensor(env, resized)
            val output = session.run(mapOf(session.inputNames.first() to tensor))
            val embedding = parseOutput(output)
            if (embedding != null && embedding.size == EMBEDDING_DIM) {
                l2Normalize(embedding)
            } else null
        } catch (e: Exception) {
            Logger.e(TAG, "Embedding failed", e)
            null
        }
    }

    private fun bitmapToInputTensor(
        env: ai.onnxruntime.OrtEnvironment,
        bitmap: Bitmap
    ): ai.onnxruntime.OnnxTensor {
        val totalPixels = INPUT_SIZE * INPUT_SIZE
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val chR = FloatArray(totalPixels)
        val chG = FloatArray(totalPixels)
        val chB = FloatArray(totalPixels)

        for (i in 0 until totalPixels) {
            val px = pixels[i]
            chR[i] = ((px shr 16 and 0xFF) / 255.0f - 0.5f) / 0.5f
            chG[i] = ((px shr 8 and 0xFF) / 255.0f - 0.5f) / 0.5f
            chB[i] = ((px and 0xFF) / 255.0f - 0.5f) / 0.5f
        }

        val nchw = FloatArray(3 * totalPixels)
        System.arraycopy(chR, 0, nchw, 0, totalPixels)
        System.arraycopy(chG, 0, nchw, totalPixels, totalPixels)
        System.arraycopy(chB, 0, nchw, 2 * totalPixels, totalPixels)

        return ai.onnxruntime.OnnxTensor.createTensor(
            env, FloatBuffer.wrap(nchw),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseOutput(
        output: ai.onnxruntime.OrtSession.Result
    ): FloatArray? {
        val tensor = output.first().value
        return when (tensor) {
            is FloatArray -> tensor
            is Array<*> -> (tensor[0] as? FloatArray)
                ?: (tensor[0] as? Array<*>)?.get(0) as? FloatArray
            else -> null
        }
    }

    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var norm = 0f
        for (v in embedding) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) for (i in embedding.indices) embedding[i] /= norm
        return embedding
    }

    fun close() {
        try { ortSession?.close(); ortEnv?.close() } catch (_: Exception) {}
        ortSession = null; ortEnv = null
    }
}
