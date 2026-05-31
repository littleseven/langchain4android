package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder

/**
 * 基于 MediaPipe 的 ROI 检测器
 * 优势: 快速、精确、支持多帧跟踪
 */
class MediaPipeRoiDetector(
    private val faceDetector: MediaPipeFaceDetector
) : RoiDetector {
    companion object {
        private const val TAG = "PicMe:MediaPipeRoi"
    }

    override fun detectRoi(bitmap: Bitmap): android.graphics.RectF? {
        val startTime = SystemClock.elapsedRealtime()
        return try {
            Log.d(TAG, "[Perf] MediaPipe ROI START: bitmap=${bitmap.width}x${bitmap.height}")

            val mpImage = BitmapImageBuilder(bitmap).build()
            val inferenceStart = SystemClock.elapsedRealtime()
            val result = faceDetector.videoLandmarker?.detectForVideo(
                mpImage,
                android.os.SystemClock.uptimeMillis()
            )
            val inferenceTime = SystemClock.elapsedRealtime() - inferenceStart

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

                val totalTime = SystemClock.elapsedRealtime() - startTime
                Log.d(TAG, "[Perf] MediaPipe ROI DONE: total=${totalTime}ms, inference=${inferenceTime}ms, roi=$roi")
                roi
            } ?: run {
                val totalTime = SystemClock.elapsedRealtime() - startTime
                Log.w(TAG, "[Perf] MediaPipe ROI DONE: total=${totalTime}ms, no face detected")
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
