package com.picme.features.camera.facedetect.adapter

import androidx.camera.core.CameraSelector
import com.picme.features.camera.preview.core.FaceDetectionSource

/**
 * GPUPixel Mars-face-kit 106 点适配器
 *
 * GPUPixel 内建的 Mars-face-kit 模型输出 111 个点（索引 0-110），
 * 其中前 106 个点（索引 0-105）为标准 Face++ 106 点规范，与统一 106 拓扑一致。
 *
 * 当前实现：1:1 直通映射（取前 106 点），仅处理前置摄像头镜像。
 * 预留扩展：若未来发现 Mars-face-kit 106 点与统一 106 存在拓扑差异，
 *          可在此添加重排映射表，不影响消费层。
 *
 * 参考文档：
 * - docs/BIG_BEAUTY_TECH_SPEC.md 8.4 节
 * - docs/face-detection/INSIGHTFACE_106_MAPPING.md
 */
class GpuPixelAdapter : FaceLandmarkAdapter {

    override val detectionSource: FaceDetectionSource = FaceDetectionSource.GPUPIXEL

    companion object {
        private const val STANDARD_POINT_COUNT = 106
        private const val MARS_OUTPUT_POINT_COUNT = 111
    }

    override fun adapt(nativeLandmarks: FloatArray, lensFacing: Int): Result<FloatArray> {
        if (nativeLandmarks.isEmpty()) {
            return Result.failure(IllegalArgumentException("GPUPixel landmarks is empty"))
        }

        val isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT
        val inputPointCount = nativeLandmarks.size / 2

        // Mars-face-kit 可能输出 111 点，只取前 106 点
        val effectiveCount = minOf(inputPointCount, STANDARD_POINT_COUNT)
        val result = FloatArray(effectiveCount * 2)

        for (index in 0 until effectiveCount) {
            val x = nativeLandmarks[index * 2]
            val y = nativeLandmarks[index * 2 + 1]
            result[index * 2] = if (isFrontCamera) 1f - x else x
            result[index * 2 + 1] = y
        }

        return Result.success(result)
    }
}
