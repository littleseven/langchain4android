package com.picme.features.camera.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * 基于 InsightFace 2d106det 的关键点检测器
 * 优势: 高精度 106 点检测
 */
class InsightFaceLandmarkDetector(context: Context) : LandmarkDetector {
    companion object {
        private const val TAG = "PicMe:InsightFaceLandmark"
    }
    
    private val detector = InsightFace2D106Detector(context)
    
    override fun detectLandmarks(
        bitmap: Bitmap,
        lensFacing: Int,
        roi: android.graphics.RectF?
    ): FloatArray? {
        return try {
            val result = detector.detect(bitmap, lensFacing, roi)
            
            if (result != null) {
                Log.d(TAG, "InsightFace landmarks detected: ${result.size / 2} points")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "InsightFace landmark detection failed", e)
            null
        }
    }
    
    override fun release() {
        detector.release()
    }
}
