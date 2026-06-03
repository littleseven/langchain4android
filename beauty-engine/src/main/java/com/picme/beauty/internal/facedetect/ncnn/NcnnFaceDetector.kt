package com.picme.beauty.internal.facedetect.ncnn

import android.graphics.Bitmap
import com.picme.beauty.api.Logger

/**
 * NCNN 人脸检测器 JNI 桥接类
 *
 * 通过 JNI 调用 C++ 层的 NCNN 推理引擎，支持 Vulkan GPU 加速。
 */
class NcnnFaceDetector private constructor(
    private var nativeHandle: Long,
    private val inputSize: Int
) {
    companion object {
        private const val TAG = "NcnnFaceDetector"

        // [性能优化] 复用像素缓冲区和 RGB 数据缓冲区，避免每帧 new IntArray/ByteArray
        private var reusablePixels: IntArray? = null
        private var reusableRgbData: ByteArray? = null

        init {
            try {
                System.loadLibrary("picme_native")
                // 同步 native 层日志开关状态
                val logEnabled = Logger.isLogEnabled(TAG)
                nativeSetLogEnabled(logEnabled)
                Logger.i(TAG, "Native library loaded successfully, nativeLogEnabled=$logEnabled")
            } catch (e: UnsatisfiedLinkError) {
                Logger.e(TAG, "Failed to load native library", e)
            }
        }

        private fun getPixelsBuffer(size: Int): IntArray {
            var buffer = reusablePixels
            if (buffer == null || buffer.size < size) {
                buffer = IntArray(size)
                reusablePixels = buffer
            }
            return buffer
        }

        private fun getRgbDataBuffer(size: Int): ByteArray {
            var buffer = reusableRgbData
            if (buffer == null || buffer.size < size) {
                buffer = ByteArray(size)
                reusableRgbData = buffer
            }
            return buffer
        }

        /**
         * 设置 native 层日志开关。
         * 根据 Logger 的模块开关状态同步控制 native 层日志输出。
         */
        fun setNativeLogEnabled(enabled: Boolean) {
            nativeSetLogEnabled(enabled)
        }

        @JvmStatic
        private external fun nativeSetLogEnabled(enabled: Boolean)

        /**
         * 创建检测器实例
         *
         * @param paramPath NCNN param 文件路径 (.param)
         * @param binPath NCNN bin 文件路径 (.bin)
         * @param inputSize 模型输入尺寸（正方形）
         * @param useGpu 是否尝试使用 Vulkan GPU
         * @param inputName 输入 blob 名称
         * @param outputNames 输出 blob 名称列表（RetinaFace 多输出）
         * @return 检测器实例，失败返回 null
         */
        fun create(
            paramPath: String,
            binPath: String,
            inputSize: Int,
            useGpu: Boolean = true,
            inputName: String = "data",
            outputNames: Array<String> = emptyArray()
        ): NcnnFaceDetector? {
            val handle = nativeCreate(paramPath, binPath, inputSize, useGpu, inputName, outputNames)
            return if (handle != 0L) {
                NcnnFaceDetector(handle, inputSize)
            } else {
                Logger.e(TAG, "Failed to create native NCNN detector")
                null
            }
        }

        @JvmStatic
        private external fun nativeCreate(
            paramPath: String,
            binPath: String,
            inputSize: Int,
            useGpu: Boolean,
            inputName: String,
            outputNames: Array<String>
        ): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeDetect(
            handle: Long,
            imageData: ByteArray,
            width: Int,
            height: Int,
            channels: Int
        ): FloatArray?

        @JvmStatic
        private external fun nativeDetectRetinaFace(
            handle: Long,
            imageData: ByteArray,
            width: Int,
            height: Int,
            channels: Int,
            confidenceThreshold: Float,
            nmsThreshold: Float
        ): FloatArray?
    }

    /**
     * 执行单输出检测（2D106 关键点）
     *
     * @param bitmap 输入 Bitmap（ARGB_8888）
     * @return 检测结果数组，未检测到返回 null
     */
    fun detect(bitmap: Bitmap): FloatArray? {
        if (nativeHandle == 0L) {
            Logger.w(TAG, "Detector not initialized")
            return null
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = getPixelsBuffer(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 将 ARGB IntArray 转为 RGB ByteArray
        val rgbData = getRgbDataBuffer(pixelCount * 3)
        for (i in 0 until pixelCount) {
            val pixel = pixels[i]
            rgbData[i * 3] = (pixel shr 16 and 0xFF).toByte()     // R
            rgbData[i * 3 + 1] = (pixel shr 8 and 0xFF).toByte()  // G
            rgbData[i * 3 + 2] = (pixel and 0xFF).toByte()        // B
        }

        return nativeDetect(nativeHandle, rgbData, width, height, 3)
    }

    /**
     * RetinaFace 检测（多输出：bbox + score + 5 landmarks）
     * @return [x1, y1, x2, y2, score, lx1, ly1, lx2, ly2, lx3, ly3, lx4, ly4, lx5, ly5]
     */
    fun detectRetinaFace(bitmap: Bitmap, confidenceThreshold: Float = 0.5f, nmsThreshold: Float = 0.4f): FloatArray? {
        if (nativeHandle == 0L) {
            Logger.w(TAG, "Detector not initialized")
            return null
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = getPixelsBuffer(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val rgbData = getRgbDataBuffer(pixelCount * 3)
        for (i in 0 until pixelCount) {
            val pixel = pixels[i]
            rgbData[i * 3] = (pixel shr 16 and 0xFF).toByte()
            rgbData[i * 3 + 1] = (pixel shr 8 and 0xFF).toByte()
            rgbData[i * 3 + 2] = (pixel and 0xFF).toByte()
        }

        return nativeDetectRetinaFace(nativeHandle, rgbData, width, height, 3, confidenceThreshold, nmsThreshold)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    protected fun finalize() {
        release()
    }
}
