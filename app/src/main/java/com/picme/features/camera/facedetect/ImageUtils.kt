package com.picme.features.camera.facedetect

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy

/**
 * 图像处理工具类
 *
 * [GPU 检测优化] 消除 JPEG 压缩/解压中间步骤，
 * YUV_420_888 直接转 ARGB_8888 Bitmap，降低 CPU→GPU 传输延迟。
 */
object ImageUtils {
    // [常量定义] 旋转角度
    internal const val ROTATION_90 = 90
    internal const val ROTATION_180 = 180
    internal const val ROTATION_270 = 270

    // [常量定义] UV 通道移位（除以 2）
    internal const val UV_CHANNEL_SHIFT = 1

    // [常量定义] ITU-R BT.601 颜色转换系数
    internal const val Y_TO_R_COEFF = 1.402f
    internal const val Y_TO_G_U_COEFF = 0.344136f
    internal const val Y_TO_G_V_COEFF = 0.714136f
    internal const val Y_TO_B_U_COEFF = 1.772f

    // [常量定义] 位操作和颜色范围
    internal const val BYTE_MASK = 0xFF
    internal const val UV_OFFSET = 128
    internal const val COLOR_MIN = 0
    internal const val COLOR_MAX = 255
    internal const val ALPHA_BYTE = 0xFF
    internal const val RED_SHIFT = 16
    internal const val GREEN_SHIFT = 8

    // [性能优化] 复用 ARGB 像素缓冲区，避免每帧 new IntArray
    private var reusableArgbBuffer: IntArray? = null
    private var reusableArgbBufferSize: Int = 0

    // [GC 优化] 复用 Y/U/V 平面 ByteArray 缓冲区，消除每像素 ByteBuffer.get() JNI 调用
    private var reusableYPlane: ByteArray? = null
    private var reusableUPlane: ByteArray? = null
    private var reusableVPlane: ByteArray? = null

    // [性能优化] 复用 Bitmap 池，避免每帧 Bitmap.createBitmap
    private var reusableBitmap: Bitmap? = null
    private var reusableBitmapWidth: Int = 0
    private var reusableBitmapHeight: Int = 0

    private fun getYuvPlaneBuffer(existing: ByteArray?, size: Int): ByteArray {
        if (existing != null && existing.size >= size) return existing
        return ByteArray(size)
    }

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
    internal const val TARGET_MAX_SIZE = 320

    /**
     * 统一的 ImageProxy → Bitmap 转换（零 JPEG 路径 + 直接缩放）
     *
     * 相比旧方案（YUV→NV21→JPEG→Bitmap），本方案直接做 YUV→ARGB 色彩空间转换：
     * - 省去 JPEG 压缩（~5ms）和解压（~3ms）
     * - 省去 Bitmap 旋转的额外内存分配
     * - 直接生成 MediaPipe BitmapImageBuilder 所需的 ARGB_8888 格式
     *
     * [发热优化] YUV→Bitmap 时直接缩放到此尺寸，像素处理量从 40 万降至约 10 万（320×320），
     * 预期耗时从 ~20ms 降至 ~5ms。
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
            val needSwap = rotationDegrees == ROTATION_90 || rotationDegrees == ROTATION_270
            val rotatedWidth = if (needSwap) height else width
            val rotatedHeight = if (needSwap) width else height

            // [性能优化] 计算缩放比例，YUV 转换直接输出缩放后尺寸
            val scale = TARGET_MAX_SIZE.toFloat() / maxOf(rotatedWidth, rotatedHeight)
            val invScale = 1f / scale
            val outWidth = (rotatedWidth * scale).toInt().coerceAtLeast(1)
            val outHeight = (rotatedHeight * scale).toInt().coerceAtLeast(1)
            val pixelCount = outWidth * outHeight

            val argb = getArgbBuffer(pixelCount)

            // [GC 优化] 批量读取 Y/U/V 平面到 ByteArray，消除每像素 3 次 ByteBuffer.get() JNI 调用
            // 使用 buffer.remaining() 而非 rowStride*height，因为 CameraX 实际 buffer 大小可能小于 stride*height
            val yPlaneSize = yBuffer.remaining()
            reusableYPlane = getYuvPlaneBuffer(reusableYPlane, yPlaneSize)
            yBuffer.position(0)
            yBuffer.get(reusableYPlane!!, 0, yPlaneSize)

            val uPlaneSize = uBuffer.remaining()
            reusableUPlane = getYuvPlaneBuffer(reusableUPlane, uPlaneSize)
            uBuffer.position(0)
            uBuffer.get(reusableUPlane!!, 0, uPlaneSize)

            val vPlaneSize = vBuffer.remaining()
            reusableVPlane = getYuvPlaneBuffer(reusableVPlane, vPlaneSize)
            vBuffer.position(0)
            vBuffer.get(reusableVPlane!!, 0, vPlaneSize)

            val yBytes = reusableYPlane!!
            val uBytes = reusableUPlane!!
            val vBytes = reusableVPlane!!

            // YUV → ARGB 转换（ITU-R BT.601 标准），直接采样到目标尺寸
            // [性能优化] 使用乘法替代每像素除法
            for (outY in 0 until outHeight) {
                val srcY = (outY * invScale).toInt().coerceIn(0, rotatedHeight - 1)
                for (outX in 0 until outWidth) {
                    // 反向映射到原始图像坐标
                    val srcX = (outX * invScale).toInt().coerceIn(0, rotatedWidth - 1)

                    // 根据旋转角度映射回 ImageProxy 原始坐标
                    val (origCol, origRow) = when (rotationDegrees) {
                        ROTATION_90 -> Pair(srcY, height - 1 - srcX)
                        ROTATION_180 -> Pair(width - 1 - srcX, height - 1 - srcY)
                        ROTATION_270 -> Pair(width - 1 - srcY, srcX)
                        else -> Pair(srcX, srcY)
                    }

                    val y = yBytes[origRow * yRowStride + origCol].toInt() and BYTE_MASK

                    val uvRow = origRow shr UV_CHANNEL_SHIFT
                    val uvCol = origCol shr UV_CHANNEL_SHIFT
                    val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride

                    val v = (vBytes[uvIndex].toInt() and BYTE_MASK) - UV_OFFSET
                    val u = (uBytes[uvIndex].toInt() and BYTE_MASK) - UV_OFFSET

                    val r = ((y + Y_TO_R_COEFF * v).toInt().coerceIn(COLOR_MIN, COLOR_MAX))
                    val g = ((y - Y_TO_G_U_COEFF * u - Y_TO_G_V_COEFF * v).toInt().coerceIn(COLOR_MIN, COLOR_MAX))
                    val b = ((y + Y_TO_B_U_COEFF * u).toInt().coerceIn(COLOR_MIN, COLOR_MAX))

                    val argbPixel = ((ALPHA_BYTE shl 24) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b)
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
        reusableYPlane = null
        reusableUPlane = null
        reusableVPlane = null
    }
}
