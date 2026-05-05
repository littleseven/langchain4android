package com.picme.features.camera.facedetect

import android.content.Context
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder

/**
 * 基于 MediaPipe 的 ROI 检测器
 * 优势: 快速、精确、支持多帧跟踪
 */
class MediaPipeRoiDetector(context: Context) : RoiDetector {
    companion object {
        private const val TAG = "PicMe:MediaPipeRoi"
    }
    
    private val faceDetector = MediaPipeFaceDetector(context)
    
    @ExperimentalGetImage
    override fun detectRoi(imageProxy: ImageProxy): android.graphics.RectF? {
        return try {
            // 复用 MediaPipeFaceDetector 的内部逻辑获取检测结果
            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy) ?: return null
            
            Log.d(TAG, "=== MediaPipe ROI Detection ===")
            Log.d(TAG, "  ImageProxy size: ${imageProxy.width}x${imageProxy.height}")
            Log.d(TAG, "  ImageProxy rotation: ${imageProxy.imageInfo.rotationDegrees}")
            Log.d(TAG, "  Bitmap size after conversion: ${bitmap.width}x${bitmap.height}")
            
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = faceDetector.videoLandmarker?.detectForVideo(
                mpImage, 
                android.os.SystemClock.uptimeMillis()
            )
            
            bitmap.recycle()
            
            result?.faceLandmarks()?.firstOrNull()?.let { landmarks ->
                // 从 468 点计算边界框
                var minX = 1f
                var maxX = 0f
                var minY = 1f
                var maxY = 0f
                
                landmarks.forEach { landmark ->
                    val x = landmark.x()
                    val y = landmark.y()
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                
                // [修复] 使用旋转后的 Bitmap 尺寸,而不是原始 ImageProxy 尺寸
                val roi = android.graphics.RectF(
                    minX * bitmap.width.toFloat(),
                    minY * bitmap.height.toFloat(),
                    maxX * bitmap.width.toFloat(),
                    maxY * bitmap.height.toFloat()
                )
                
                Log.d(TAG, "  MediaPipe normalized bounds: ($minX,$minY)-($maxX,$maxY)")
                Log.d(TAG, "  ROI in pixels (using Bitmap size): $roi")
                Log.d(TAG, "=====================================")
                roi
            } ?: run {
                Log.w(TAG, "  No face detected by MediaPipe")
                Log.d(TAG, "=====================================")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe ROI detection failed", e)
            null
        }
    }
    
    override fun release() {
        faceDetector.release()
    }
}
