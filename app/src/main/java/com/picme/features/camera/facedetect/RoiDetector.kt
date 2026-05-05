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
     * 释放资源
     */
    fun release()
}
