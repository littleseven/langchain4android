package com.picme.features.camera.facedetect

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy

/**
 * 图像处理工具类
 *
 * [GPU 检测优化] 消除 JPEG 压缩/解压中间步骤，
 * YUV_420_888 直接转 ARGB_8888 Bitmap，降低 CPU→GPU 传输延迟。
 */
object ImageUtils {

    // [性能优化] 复用 ARGB 像素缓冲区，避免每帧 new IntArray
    private var reusableArgbBuffer: IntArray? = null
    private var reusableArgbBufferSize: Int = 0

    // [性能优化] 复用 Bitmap 池，避免每帧 Bitmap.createBitmap
    private var reusableBitmap: Bitmap? = null
    private var reusableBitmapWidth: Int = 0
    private var reusableBitmapHeight: Int = 0

    private fun getArgbBuffer(size: Int): IntArray {
        var buffer = reusableArgbBuffer
        if (buffer == null || buffer.size < size) {
            buffer = IntArray(size)
            reusableArgbBuffer = buffer
            reusableArgbBufferSize = size
        }
        return buffer
    }

    private fun getReusableBitmap(width: Int, height: Int): Bitmap {
        var bmp = reusableBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            reusableBitmap = bmp
            reusableBitmapWidth = width
            reusableBitmapHeight = height
        }
        return bmp
    }

    /**
     * 目标检测最大输入尺寸。
     * Det10G 模型输入为 640×640，2D106 为 192×192。
     * YUV→Bitmap 时直接缩放到此尺寸，避免全分辨率（720×1280）转换的巨额开销。
     */
    private const val TARGET_MAX_SIZE = 640

    /**
     * 统一的 ImageProxy → Bitmap 转换（零 JPEG 路径 + 直接缩放）
     *
     * 相比旧方案（YUV→NV21→JPEG→Bitmap），本方案直接做 YUV→ARGB 色彩空间转换：
     * - 省去 JPEG 压缩（~5ms）和解压（~3ms）
     * - 省去 Bitmap 旋转的额外内存分配
     * - 直接生成 MediaPipe BitmapImageBuilder 所需的 ARGB_8888 格式
     *
     * [性能优化 v2] YUV 转换时直接缩放到目标尺寸，像素处理量从 92 万降至约 40 万（640×640），
     * 预期耗时从 ~100ms 降至 ~20ms。
     */
    @ExperimentalGetImage
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return imageProxy.image?.let { img ->
            val width = imageProxy.width
            val height = imageProxy.height
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val yRowStride = imageProxy.planes[0].rowStride
            val uvRowStride = imageProxy.planes[1].rowStride
            val uvPixelStride = imageProxy.planes[1].pixelStride

            // 计算旋转后的输出尺寸
            val needSwap = rotationDegrees == 90 || rotationDegrees == 270
            val rotatedWidth = if (needSwap) height else width
            val rotatedHeight = if (needSwap) width else height

            // [性能优化] 计算缩放比例，YUV 转换直接输出缩放后尺寸
            val scale = TARGET_MAX_SIZE.toFloat() / maxOf(rotatedWidth, rotatedHeight)
            val invScale = 1f / scale
            val outWidth = (rotatedWidth * scale).toInt().coerceAtLeast(1)
            val outHeight = (rotatedHeight * scale).toInt().coerceAtLeast(1)
            val pixelCount = outWidth * outHeight

            val argb = getArgbBuffer(pixelCount)

            // YUV → ARGB 转换（ITU-R BT.601 标准），直接采样到目标尺寸
            // [性能优化] 使用乘法替代每像素除法
            for (outY in 0 until outHeight) {
                val srcY = (outY * invScale).toInt().coerceIn(0, rotatedHeight - 1)
                for (outX in 0 until outWidth) {
                    // 反向映射到原始图像坐标
                    val srcX = (outX * invScale).toInt().coerceIn(0, rotatedWidth - 1)

                    // 根据旋转角度映射回 ImageProxy 原始坐标
                    val (origCol, origRow) = when (rotationDegrees) {
                        90 -> Pair(srcY, height - 1 - srcX)
                        180 -> Pair(width - 1 - srcX, height - 1 - srcY)
                        270 -> Pair(width - 1 - srcY, srcX)
                        else -> Pair(srcX, srcY)
                    }

                    val y = (yBuffer.get(origRow * yRowStride + origCol).toInt() and 0xFF)

                    val uvRow = origRow shr 1
                    val uvCol = origCol shr 1
                    val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride

                    val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
                    val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128

                    val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                    val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                    val b = (y + 1.772f * u).toInt().coerceIn(0, 255)

                    val argbPixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    argb[outY * outWidth + outX] = argbPixel
                }
            }

            val bitmap = getReusableBitmap(outWidth, outHeight)
            bitmap.setPixels(argb, 0, outWidth, 0, 0, outWidth, outHeight)
            bitmap
        }
    }

    /**
     * 释放复用的 Bitmap 资源（在相机销毁时调用）
     */
    fun release() {
        reusableBitmap?.recycle()
        reusableBitmap = null
        reusableArgbBuffer = null
        reusableArgbBufferSize = 0
        reusableBitmapWidth = 0
        reusableBitmapHeight = 0
    }
}
