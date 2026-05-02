package com.picme.features.camera.facedetect.adapter

import androidx.camera.core.CameraSelector
import com.picme.features.camera.preview.core.FaceDetectionSource

/**
 * InsightFace 2D106 适配器
 *
 * InsightFace 的 `2d106det.onnx` 直接输出 106 点，其拓扑与统一 106 标准一致。
 * 因此本适配器为 1:1 直通映射，仅处理前置摄像头镜像。
 *
 * 映射规则：
 * - 同索引直通：`index -> same index`
 * - 不做 reorder、不做区域互换、不做左右半脸成组翻转
 * - 前置摄像头时水平镜像 x 坐标
 *
 * 参考文档：docs/BIG_BEAUTY_INSIGHTFACE_106_MAPPING.md
 */
class InsightFaceAdapter : FaceLandmarkAdapter {

    override val detectionSource: FaceDetectionSource = FaceDetectionSource.INSIGHTFACE

    companion object {
        private const val POINT_COUNT = 106
    }

    override fun adapt(nativeLandmarks: FloatArray, lensFacing: Int): Result<FloatArray> {
        if (nativeLandmarks.size < POINT_COUNT * 2) {
            return Result.failure(
                IllegalArgumentException(
                    "InsightFace landmarks size ${nativeLandmarks.size} < required ${POINT_COUNT * 2}"
                )
            )
        }

        val isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT

        // 前置摄像头需要水平镜像 x 坐标
        return if (isFrontCamera) {
            val mirrored = FloatArray(nativeLandmarks.size)
            for (i in nativeLandmarks.indices step 2) {
                mirrored[i] = 1f - nativeLandmarks[i]     // x = 1 - x
                mirrored[i + 1] = nativeLandmarks[i + 1]  // y 不变
            }
            Result.success(mirrored)
        } else {
            Result.success(nativeLandmarks.copyOf())
        }
    }
}
