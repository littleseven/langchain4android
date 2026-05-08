package com.picme.beauty.api.facedetect

import android.graphics.RectF

/**
 * 人脸检测结果
 *
 * @param landmarks106 106 点归一化坐标（FloatArray，偶数索引=x，奇数索引=y）
 * @param detectionSource 检测算法来源
 * @param roiRect ROI 区域（归一化坐标）
 */
data class FaceDetectionResult(
    val landmarks106: FloatArray,
    val detectionSource: FaceDetectionSource,
    val roiRect: RectF? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FaceDetectionResult

        if (!landmarks106.contentEquals(other.landmarks106)) return false
        if (detectionSource != other.detectionSource) return false
        if (roiRect != other.roiRect) return false

        return true
    }

    override fun hashCode(): Int {
        var result = landmarks106.contentHashCode()
        result = 31 * result + detectionSource.hashCode()
        result = 31 * result + (roiRect?.hashCode() ?: 0)
        return result
    }
}
