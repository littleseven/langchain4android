package com.picme.beauty.internal.facedetect.ncnn

import android.graphics.Bitmap
import com.picme.beauty.api.Logger
import java.nio.ByteBuffer

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

        // [性能优化] 复用像素缓冲区
        private var reusablePixels: IntArray? = null
        // [性能优化] DirectByteBuffer 零拷贝输入
        private var reusableRgbBuffer: ByteBuffer? = null
        // [性能优化] 复用结果缓冲区，避免 JNI NewFloatArray 每帧分配
        private var reusableDetectResult: FloatArray? = null
        private var reusableRetinaResult: FloatArray? = null
        private const val RETINA_RESULT_SIZE = 15

        init {
            try {
                System.loadLibrary("beauty_native")
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

        private fun getRgbBuffer(size: Int): ByteBuffer {
            var buffer = reusableRgbBuffer
            if (buffer == null || buffer.capacity() < size) {
                buffer = ByteBuffer.allocateDirect(size)
                reusableRgbBuffer = buffer
            }
            buffer.clear()
            return buffer
        }

        private fun getDetectResult(size: Int): FloatArray {
            var buffer = reusableDetectResult
            if (buffer == null || buffer.size < size) {
                buffer = FloatArray(size)
                reusableDetectResult = buffer
            }
            return buffer
        }

        private fun getRetinaResult(): FloatArray {
            var buffer = reusableRetinaResult
            if (buffer == null) {
                buffer = FloatArray(RETINA_RESULT_SIZE)
                reusableRetinaResult = buffer
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
            imageData: ByteBuffer,
            width: Int,
            height: Int,
            channels: Int,
            outResult: FloatArray
        ): Int

        @JvmStatic
        private external fun nativeDetectRetinaFace(
            handle: Long,
            imageData: ByteBuffer,
            width: Int,
            height: Int,
            channels: Int,
            confidenceThreshold: Float,
            nmsThreshold: Float,
            outResult: FloatArray
        ): Boolean
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

        // 将 ARGB IntArray 写入 DirectByteBuffer（零拷贝 JNI 传输）
        val rgbBuffer = getRgbBuffer(pixelCount * 3)
        for (i in 0 until pixelCount) {
            val pixel = pixels[i]
            rgbBuffer.put(i * 3, (pixel shr 16 and 0xFF).toByte())     // R
            rgbBuffer.put(i * 3 + 1, (pixel shr 8 and 0xFF).toByte())  // G
            rgbBuffer.put(i * 3 + 2, (pixel and 0xFF).toByte())        // B
        }

        val outResult = getDetectResult(pixelCount * 2)  // 最大 212 个 float
        val written = nativeDetect(nativeHandle, rgbBuffer, width, height, 3, outResult)
        return if (written > 0) outResult.copyOf(written) else null
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

        val rgbBuffer = getRgbBuffer(pixelCount * 3)
        for (i in 0 until pixelCount) {
            val pixel = pixels[i]
            rgbBuffer.put(i * 3, (pixel shr 16 and 0xFF).toByte())
            rgbBuffer.put(i * 3 + 1, (pixel shr 8 and 0xFF).toByte())
            rgbBuffer.put(i * 3 + 2, (pixel and 0xFF).toByte())
        }

        val outResult = getRetinaResult()
        val detected = nativeDetectRetinaFace(nativeHandle, rgbBuffer, width, height, 3, confidenceThreshold, nmsThreshold, outResult)
        return if (detected) outResult.copyOf() else null
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
