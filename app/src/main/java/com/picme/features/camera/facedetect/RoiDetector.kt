package com.picme.features.camera.facedetect

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy

/**
 * 人脸区域检测器
 * 负责快速定位人脸边界框,为关键点检测提供 ROI
 */
interface RoiDetector {
    /**
     * 检测人脸边界框
     * @param imageProxy CameraX 图像代理
     * @return 人脸边界框(RectF),未检测到返回 null
     */
    @ExperimentalGetImage
    fun detectRoi(imageProxy: ImageProxy): android.graphics.RectF?
    
    /**
     * [方案4] 从 Bitmap 检测人脸边界框 (避免重复转换)
     * @param bitmap 已转换的 Bitmap
     * @return 人脸边界框(RectF),未检测到返回 null
     */
    fun detectRoiFromBitmap(bitmap: android.graphics.Bitmap): android.graphics.RectF? {
        // 默认实现: 不支持,返回 null
        return null
    }
    
    /**
     * 释放资源
     */
    fun release()
}
