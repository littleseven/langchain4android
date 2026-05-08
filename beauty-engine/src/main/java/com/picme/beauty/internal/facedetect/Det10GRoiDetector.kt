package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * 基于 InsightFace Det10G 的 ROI 检测器
 * 优势: 轻量级、兼容性好
 */
class Det10GRoiDetector(context: Context) : RoiDetector {
    companion object {
        private const val TAG = "PicMe:Det10GRoi"
    }

    private val det10g = InsightFaceDet10GDetector(context)

    override fun detectRoi(bitmap: Bitmap): android.graphics.RectF? {
        return try {
            Log.d(TAG, "=== Det10G ROI Detection ===")
            Log.d(TAG, "  Bitmap size: ${bitmap.width}x${bitmap.height}")

            val roi = det10g.detectLargestFace(bitmap)

            roi?.let {
                Log.d(TAG, "  Det10G ROI in pixels: $it")
                Log.d(TAG, "=====================================")
            } ?: run {
                Log.w(TAG, "  No face detected by Det10G")
                Log.d(TAG, "=====================================")
            }

            roi
        } catch (e: Exception) {
            Log.e(TAG, "Det10G ROI detection failed", e)
            null
        }
    }

    override fun release() {
        det10g.release()
    }
}
