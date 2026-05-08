package com.picme.beauty.api.facedetect

import android.graphics.PointF

/**
 * 人脸 106 点数据结构（通用格式）
 *
 * 注：返回 111 个点，但前 106 个是标准 Face++ 106 点规范
 * 我们只使用标准的 106 点，忽略额外的 5 个辅助点
 */
data class GpuPixelLandmarks(
    val points: List<PointF> = emptyList(),
    val hasFace: Boolean = false
) {
    companion object {
        /**
         * 从 float 数组创建（格式：[x0,y0,x1,y1,...]）
         * 只取前 106 个点，忽略额外的 5 个辅助点
         */
        fun fromFloatArray(landmarks: FloatArray?): GpuPixelLandmarks {
            if (landmarks == null || landmarks.isEmpty()) {
                return GpuPixelLandmarks()
            }
            val points = mutableListOf<PointF>()
            val count = minOf(landmarks.size / 2, 106)
            for (i in 0 until count) {
                val x = landmarks[i * 2]
                val y = landmarks[i * 2 + 1]
                points.add(PointF(x, y))
            }
            return GpuPixelLandmarks(points = points, hasFace = true)
        }
    }
}
