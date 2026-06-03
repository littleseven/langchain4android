package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import com.picme.beauty.api.Logger

/**
 * 基于 MediaPipe 的关键点检测器
 * 优势: 468 点高密度、实时性强
 */
class MediaPipeLandmarkDetector(
    private val faceDetector: MediaPipeFaceDetector
) : LandmarkDetector {
    companion object {
        private const val TAG = "MediaPipeLandmark"
    }

    override fun detectLandmarks(
        bitmap: Bitmap,
        lensFacing: Int,
        roi: android.graphics.RectF?
    ): FloatArray? {
        return try {
            // MediaPipe 不需要 ROI,直接检测全图
            val result = faceDetector.detectForPhoto(bitmap, lensFacing)

            if (result != null) {
                Logger.d(TAG, "MediaPipe landmarks detected: ${result.size / 2} points")
            }

            result
        } catch (e: Exception) {
            Logger.e(TAG, "MediaPipe landmark detection failed", e)
            null
        }
    }

    override fun release() {
        faceDetector.release()
    }
}
