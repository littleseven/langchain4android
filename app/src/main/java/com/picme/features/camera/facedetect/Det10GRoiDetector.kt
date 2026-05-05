package com.picme.features.camera.facedetect

import android.content.Context
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
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
    
    @ExperimentalGetImage
    override fun detectRoi(imageProxy: ImageProxy): android.graphics.RectF? {
        return try {
            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy) ?: return null
            
            Log.d(TAG, "=== Det10G ROI Detection ===")
            Log.d(TAG, "  ImageProxy size: ${imageProxy.width}x${imageProxy.height}")
            Log.d(TAG, "  ImageProxy rotation: ${imageProxy.imageInfo.rotationDegrees}")
            Log.d(TAG, "  Bitmap size after conversion: ${bitmap.width}x${bitmap.height}")
            
            val roi = det10g.detectLargestFace(bitmap)
            
            bitmap.recycle()
            
            roi?.let {
                Log.d(TAG, "  Det10G ROI in pixels (using Bitmap size): $it")
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
