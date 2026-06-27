package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mamba.picme.beauty.internal.clip.MobileClipEncoder
import com.mamba.picme.data.download.ModelPathConfig
import java.io.File

/**
 * MobileCLIP 语义编码引擎
 *
 * 使用 MNN 引擎加载 MobileCLIP-S0 的 vision_model 和 text_model，
 * 生成 512 维 L2 归一化 embedding，用于：
 * - Pass 4: 图像语义编码（替代/补充 Qwen 标签）
 * - 未来: 文本→图像语义搜索
 *
 * 模型文件从 ModelScope 远程下载到 llm_models/mobileclip-mnn/ 目录。
 *
 * @param context Application Context
 */
class MobileClipEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "MobileClipEngine"
        private const val EMBEDDING_DIM = 512

        /** Vision 模型文件名 */
        private const val VISION_MODEL_NAME = "vision_model.mnn"

        /** Text 模型文件名 */
        private const val TEXT_MODEL_NAME = "text_model.mnn"
    }

    private var encoder: MobileClipEncoder? = null

    /** 模型是否已准备好（已从 ModelScope 下载） */
    val isModelReady: Boolean
        get() = ModelPathConfig.isMobileClipModelReady(context)

    /** 引擎是否已初始化（模型已加载） */
    val isInitialized: Boolean
        get() = encoder?.isVisionLoaded == true

    private val visionModelFile: File
        get() = File(ModelPathConfig.getMobileClipModelDir(context), VISION_MODEL_NAME)

    private val textModelFile: File
        get() = File(ModelPathConfig.getMobileClipModelDir(context), TEXT_MODEL_NAME)

    /**
     * 初始化引擎：加载 MNN 模型（需先通过模型中心下载）
     *
     * @param useGpu 是否尝试使用 OpenCL GPU（vision 模型）
     * @return 是否成功
     */
    fun initialize(useGpu: Boolean = false): Boolean {
        if (encoder != null && encoder?.isVisionLoaded == true) {
            Log.d(TAG, "Already initialized")
            return true
        }

        // 检查模型是否已从 ModelScope 下载
        if (!isModelReady) {
            Log.w(TAG, "Model files not downloaded. Please download from Model Center: mobileclip-mnn")
            return false
        }

        // 创建编码器
        val enc = MobileClipEncoder.create()
        if (enc == null) {
            Log.e(TAG, "Failed to create MobileClipEncoder")
            return false
        }

        // 加载 vision 模型
        if (!enc.loadVisionModel(visionModelFile, useGpu)) {
            Log.e(TAG, "Failed to load vision model")
            enc.release()
            return false
        }

        // 加载 text 模型（CPU 即可，text 推理很快）
        if (!enc.loadTextModel(textModelFile, false)) {
            Log.w(TAG, "Failed to load text model, vision-only mode")
            // text 模型失败不影响 vision 功能
        }

        encoder = enc
        Log.i(TAG, "MobileClipEngine initialized (vision=${enc.isVisionLoaded}, text=${enc.isTextLoaded})")
        return true
    }

    /**
     * 编码图像为语义 embedding
     *
     * @param bitmap 输入图像（任意尺寸，内部处理为 256x256）
     * @return 512 维 L2 归一化 embedding，失败返回 null
     */
    fun encodeImage(bitmap: Bitmap): FloatArray? {
        val enc = encoder ?: run {
            Log.w(TAG, "Engine not initialized")
            return null
        }
        if (!enc.isVisionLoaded) {
            Log.w(TAG, "Vision model not loaded")
            return null
        }

        return enc.encodeImage(bitmap)
    }

    /**
     * 编码文本为语义 embedding
     *
     * @param tokenIds token ID 数组（int64）
     * @return 512 维 L2 归一化 embedding，失败返回 null
     */
    fun encodeText(tokenIds: LongArray): FloatArray? {
        val enc = encoder ?: run {
            Log.w(TAG, "Engine not initialized")
            return null
        }
        if (!enc.isTextLoaded) {
            Log.w(TAG, "Text model not loaded")
            return null
        }

        return enc.encodeText(tokenIds)
    }

    /**
     * 计算两个 embedding 的余弦相似度
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA > 0 && normB > 0) {
            dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
        } else 0f
    }

    /**
     * 释放引擎资源
     */
    fun release() {
        encoder?.release()
        encoder = null
        Log.i(TAG, "Engine released")
    }
}
