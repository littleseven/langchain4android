package com.picme.features.camera.facedetect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy

/**
 * 图像处理工具类
 */
object ImageUtils {
    /**
     * 统一的 ImageProxy → Bitmap 转换
     */
    @ExperimentalGetImage
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return imageProxy.image?.let { img ->
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val yRowStride = imageProxy.planes[0].rowStride
            val uvRowStride = imageProxy.planes[1].rowStride
            val uvPixelStride = imageProxy.planes[1].pixelStride

            val width = imageProxy.width
            val height = imageProxy.height

            val nv21 = ByteArray(width * height + width * height / 2)

            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }

            if (uvPixelStride == 2) {
                val uvHeight = height / 2
                val uvWidth = width / 2
                val bytesPerRow = uvWidth * 2
                for (row in 0 until uvHeight) {
                    val srcPos = row * uvRowStride
                    val bytesToCopy = minOf(bytesPerRow, vBuffer.limit() - srcPos)
                    if (bytesToCopy > 0) {
                        vBuffer.position(srcPos)
                        vBuffer.get(nv21, pos, bytesToCopy)
                        pos += bytesToCopy
                    }
                }
            } else {
                val uvHeight = height / 2
                val uvWidth = width / 2
                for (row in 0 until uvHeight) {
                    for (col in 0 until uvWidth) {
                        nv21[pos++] = vBuffer.get(row * uvRowStride + col)
                        nv21[pos++] = uBuffer.get(row * uvRowStride + col)
                    }
                }
            }

            val yuvImage = android.graphics.YuvImage(
                nv21,
                ImageFormat.NV21,
                width,
                height,
                null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, width, height),
                100,
                out
            )
            val imageBytes = out.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
            bmp
        }
    }
}
