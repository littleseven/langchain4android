package com.mamba.picme.data.indexing

import android.graphics.Bitmap
import com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceDetector
import com.mamba.picme.core.common.Logger
import java.io.File

/**
 * MNN MobileFaceNet 人脸嵌入提取器
 *
 * 复用 MnnFaceDetector 的 JNI 层（beauty_native），
 * 加载 MNN 版的 MobileFaceNet 模型，提取 512 维 embedding。
 *
 * 模型由模型中心下载，路径: {filesDir}/llm_models/picme-face-embedding-mnn/w600k_mbf.mnn
 * 链接: https://modelscope.cn/models/budaoshou/InsightFace-MobileFaceNet-MNN
 */
class MnnEmbeddingExtractor(
    private val modelFile: File,
    private val inputSize: Int = 112,
    private val embeddingDim: Int = 512
) {
    companion object {
        private const val TAG = "PicMe:MnnEmbedding"
    }

    val isModelReady: Boolean
        get() = modelFile.exists() && modelFile.length() > 100_000

    private var detector: MnnFaceDetector? = null

    /**
     * 初始化 MNN 模型
     */
    fun initialize(): Boolean {
        if (detector != null) return true
        if (!isModelReady) {
            Logger.w(TAG, "Model not found: ${modelFile.absolutePath}")
            return false
        }
        detector = MnnFaceDetector.create(
            modelPath = modelFile.absolutePath,
            inputSize = inputSize,
            useGpu = false,
            inputName = "input",
            outputNames = emptyArray()
        )
        if (detector == null) {
            Logger.e(TAG, "Failed to create MNN detector")
            return false
        }
        Logger.i(TAG, "MNN MobileFaceNet loaded")
        return true
    }

    /**
     * 提取人脸 embedding
     *
     * @param faceBitmap 112x112 RGB 人脸图片
     * @return 512 维 L2 归一化 embedding，或 null
     */
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        val det = detector ?: return null

        // 确保输入尺寸
        val resized = if (faceBitmap.width != inputSize || faceBitmap.height != inputSize) {
            Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        } else faceBitmap

        try {
            val raw = det.detect(resized) ?: return null
            if (raw.size < embeddingDim) {
                Logger.w(TAG, "Output too small: ${raw.size}, expected $embeddingDim")
                return null
            }

            // 取前 embeddingDim 个值并 L2 归一化
            val embedding = raw.copyOf(embeddingDim)
            l2Normalize(embedding)
            return embedding
        } catch (e: Exception) {
            Logger.e(TAG, "Embedding extraction failed", e)
            return null
        }
    }

    private fun l2Normalize(embedding: FloatArray) {
        var norm = 0f
        for (v in embedding) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) {
            for (i in embedding.indices) embedding[i] /= norm
        }
    }

    fun close() {
        // MnnFaceDetector 自身不提供 close，实例会被 GC 回收
        detector = null
    }
}
