package com.picme.features.camera.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * 基于 MediaPipe 的关键点检测器
 * 优势: 468 点高密度、实时性强
 */
class MediaPipeLandmarkDetector(context: Context) : LandmarkDetector {
    companion object {
        private const val TAG = "PicMe:MediaPipeLandmark"
    }
    
    private val faceDetector = MediaPipeFaceDetector(context)
    
    override fun detectLandmarks(
        bitmap: Bitmap,
        lensFacing: Int,
        roi: android.graphics.RectF?
    ): FloatArray? {
        return try {
            // MediaPipe 不需要 ROI,直接检测全图
            val result = faceDetector.detectForPhoto(bitmap, lensFacing)
            
            if (result != null) {
                Log.d(TAG, "MediaPipe landmarks detected: ${result.size / 2} points")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe landmark detection failed", e)
            null
        }
    }
    
    override fun release() {
        faceDetector.release()
    }
}
