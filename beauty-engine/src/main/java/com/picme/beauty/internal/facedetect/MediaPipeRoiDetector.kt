package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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

    override fun detectRoi(bitmap: Bitmap): android.graphics.RectF? {
        return try {
            Log.d(TAG, "=== MediaPipe ROI Detection ===")
            Log.d(TAG, "  Bitmap size: ${bitmap.width}x${bitmap.height}")

            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = faceDetector.videoLandmarker?.detectForVideo(
                mpImage,
                android.os.SystemClock.uptimeMillis()
            )

            result?.faceLandmarks()?.firstOrNull()?.let { landmarks ->
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
