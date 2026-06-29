package com.mamba.picme.beauty.internal.clip

import android.graphics.Bitmap
import android.util.Log
import com.mamba.picme.agent.core.platform.mnn.MnnGlobalReleaseLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MobileCLIP 编码器（MNN 引擎）
 *
 * 通过 JNI 桥接调用 C++ 层的 MNN 推理引擎，支持图像和文本编码。
 * 生成 512 维 L2 归一化 embedding，用于语义搜索和图像理解。
 *
 * 模型文件：
 * - vision_model.mnn: 图像编码器 (45MB)
 * - text_model.mnn: 文本编码器 (169MB)
 *
 * 模型路径由调用方提供（通常从 assets 复制到 filesDir）。
 */
class MobileClipEncoder private constructor(
    private var nativeHandle: Long
) {
    companion object {
        private const val TAG = "MobileClipEncoder"
        private const val EMBEDDING_DIM = 512
        private const val VISION_INPUT_SIZE = 256
        private const val MAX_TEXT_TOKENS = 77

        init {
            try {
                System.loadLibrary("beauty_native")
                Log.i(TAG, "Native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }

        /**
         * 创建编码器实例
         */
        fun create(): MobileClipEncoder? {
            val handle = MnnGlobalReleaseLock.withOperation {
                nativeCreate()
            }
            return if (handle != 0L) {
                MobileClipEncoder(handle)
            } else {
                Log.e(TAG, "Failed to create native MobileClipEncoder")
                null
            }
        }

        @JvmStatic
        private external fun nativeCreate(): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeLoadVisionModel(handle: Long, modelPath: String, useGpu: Boolean): Boolean

        @JvmStatic
        private external fun nativeLoadTextModel(handle: Long, modelPath: String, useGpu: Boolean): Boolean

        @JvmStatic
        private external fun nativeEncodeImage(handle: Long, imageData: ByteBuffer, width: Int, height: Int): FloatArray?

        @JvmStatic
        private external fun nativeEncodeText(handle: Long, tokenIds: LongArray, tokenCount: Int): FloatArray?

        @JvmStatic
        private external fun nativeIsVisionLoaded(handle: Long): Boolean

        @JvmStatic
        private external fun nativeIsTextLoaded(handle: Long): Boolean
    }

    val isVisionLoaded: Boolean
        get() = nativeHandle != 0L && nativeIsVisionLoaded(nativeHandle)

    val isTextLoaded: Boolean
        get() = nativeHandle != 0L && nativeIsTextLoaded(nativeHandle)

    /**
     * 加载 vision 模型
     */
    fun loadVisionModel(modelFile: File, useGpu: Boolean = false): Boolean {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.w(TAG, "Vision model not found: ${modelFile.absolutePath}")
            return false
        }
        return MnnGlobalReleaseLock.withOperation {
            nativeLoadVisionModel(nativeHandle, modelFile.absolutePath, useGpu)
        }
    }

    /**
     * 加载 text 模型
     */
    fun loadTextModel(modelFile: File, useGpu: Boolean = false): Boolean {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.w(TAG, "Text model not found: ${modelFile.absolutePath}")
            return false
        }
        return MnnGlobalReleaseLock.withOperation {
            nativeLoadTextModel(nativeHandle, modelFile.absolutePath, useGpu)
        }
    }

    /**
     * 编码图像为 512 维 embedding
     *
     * @param bitmap 输入 Bitmap（任意尺寸，内部按 CLIP 标准：短边缩放到 256 后中心裁剪 256x256）
     * @return 512 维 L2 归一化 embedding，失败返回 null
     */
    fun encodeImage(bitmap: Bitmap): FloatArray? {
        if (nativeHandle == 0L) {
            Log.w(TAG, "Encoder not initialized")
            return null
        }
        if (!isVisionLoaded) {
            Log.w(TAG, "Vision model not loaded")
            return null
        }

        // CLIP 标准预处理：保持宽高比，短边 -> 256，中心裁剪 256x256
        val cropped = createCenterCroppedBitmap(bitmap, VISION_INPUT_SIZE)

        try {
            val pixelCount = cropped.width * cropped.height
            val pixels = IntArray(pixelCount)
            cropped.getPixels(pixels, 0, cropped.width, 0, 0, cropped.width, cropped.height)

            // ARGB -> RGB DirectByteBuffer
            val rgbBuffer = ByteBuffer.allocateDirect(pixelCount * 3)
                .order(ByteOrder.nativeOrder())
            for (i in 0 until pixelCount) {
                val pixel = pixels[i]
                rgbBuffer.put(i * 3, (pixel shr 16 and 0xFF).toByte())     // R
                rgbBuffer.put(i * 3 + 1, (pixel shr 8 and 0xFF).toByte())  // G
                rgbBuffer.put(i * 3 + 2, (pixel and 0xFF).toByte())        // B
            }

            return MnnGlobalReleaseLock.withOperation {
                nativeEncodeImage(nativeHandle, rgbBuffer, cropped.width, cropped.height)
            }
        } finally {
            if (cropped !== bitmap) cropped.recycle()
        }
    }

    /**
     * CLIP 标准图像预处理：resize 短边到 targetSize，中心裁剪 targetSize x targetSize。
     *
     * 避免直接拉伸非正方形图片导致物体比例失真，影响 embedding 准确性。
     */
    private fun createCenterCroppedBitmap(source: Bitmap, targetSize: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width == targetSize && height == targetSize) return source

        val scale = targetSize.toFloat() / kotlin.math.min(width, height)
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()

        // 若源图已是正方形且尺寸不同，直接等比缩放即可
        if (width == height) {
            return Bitmap.createScaledBitmap(source, targetSize, targetSize, true)
        }

        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val x = (scaledWidth - targetSize) / 2
        val y = (scaledHeight - targetSize) / 2
        val cropped = Bitmap.createBitmap(scaled, x.coerceAtLeast(0), y.coerceAtLeast(0), targetSize, targetSize)
        scaled.recycle()
        return cropped
    }

    /**
     * 编码文本为 512 维 embedding
     *
     * @param tokenIds token ID 数组（int64），长度不超过 77
     * @return 512 维 L2 归一化 embedding，失败返回 null
     */
    fun encodeText(tokenIds: LongArray): FloatArray? {
        if (nativeHandle == 0L) {
            Log.w(TAG, "Encoder not initialized")
            return null
        }
        if (!isTextLoaded) {
            Log.w(TAG, "Text model not loaded")
            return null
        }
        if (tokenIds.size > MAX_TEXT_TOKENS) {
            Log.w(TAG, "Token count too large: ${tokenIds.size} > $MAX_TEXT_TOKENS")
            return null
        }

        return MnnGlobalReleaseLock.withOperation {
            nativeEncodeText(nativeHandle, tokenIds, tokenIds.size)
        }
    }

    /**
     * 释放 native 资源
     */
    fun release() {
        if (nativeHandle != 0L) {
            MnnGlobalReleaseLock.withLock {
                nativeDestroy(nativeHandle)
            }
            nativeHandle = 0L
        }
    }
}
