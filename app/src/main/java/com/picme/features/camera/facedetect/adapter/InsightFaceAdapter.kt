package com.picme.features.camera.facedetect.adapter

import androidx.camera.core.CameraSelector
import com.picme.features.camera.preview.core.FaceDetectionSource

/**
 * InsightFace 2D106 适配器
 *
 * InsightFace 的 `2d106det.onnx` 输出 106 点，其点序与统一 106 标准完全不同。
 * **所有 106 个点都需要重排映射，不存在 index -> same index 的直通点。**
 *
 * InsightFace 轮廓排列（从下巴开始）：
 * - 0：下巴中心
 * - 1-16：左半边脸（从下巴左侧逆时针到左上角）
 * - 17-32：右半边脸（从右上角顺时针到下巴右侧）
 *
 * 统一 106 轮廓排列（顺时针）：
 * - 0-15：从左上角顺时针到下巴左侧
 * - 16：下巴中心
 * - 17-32：从下巴右侧顺时针到右上角
 *
 * 非轮廓点（33-105）：InsightFace 的眉毛、眼睛、鼻子、嘴巴等区域索引排列
 * 与统一 106 标准完全不同，需要逐点位重新映射。
 *
 * 映射规则：
 * - 所有点 0-105：通过 FULL_REMAP 表重排映射
 * - 不存在 i -> i 的直通点
 * - 前置摄像头时水平镜像 x 坐标
 *
 * 参考文档：docs/BIG_BEAUTY_INSIGHTFACE_106_MAPPING.md
 */
class InsightFaceAdapter : FaceLandmarkAdapter {

    override val detectionSource: FaceDetectionSource = FaceDetectionSource.INSIGHTFACE

    companion object {
        private const val POINT_COUNT = 106

        /**
         * 统一 106 索引 → InsightFace 索引完整映射表
         *
         * 根据 InsightFace 截图（insightface_106_points.png）逐点位识别建立。
         * **所有 106 个点都需要重排映射，不存在 index -> same index 的直通点。**
         * 如果映射表中出现 i -> i 的情况，可以直接判定为错误。
         *
         * InsightFace 轮廓点顺序（逐点追踪）：
         * - 画面左侧（右脸从太阳穴到下巴）：1, 9, 10, 11, 12, 13, 14, 15, 16, 2, 3, 4, 5, 6, 7, 8
         * - 下巴中心：0
         * - 画面右侧（左脸从太阳穴到下）：17, 25, 26, 27, 28, 29, 30, 31, 32, 18, 19, 20, 21, 22, 23, 24
         *
         * 映射规则：FULL_REMAP[unifiedIndex] = insightFaceIndex
         */
        private val FULL_REMAP = intArrayOf(
            // ===== 轮廓点 0-32 =====
            // 统一 0-15（左太阳穴→下巴左侧）→ InsightFace 1, 9-16, 2-8
            1, 9, 10, 11, 12, 13, 14, 15, 16, 2, 3, 4, 5, 6, 7, 8,
            // 统一 16 → InsightFace 0（下巴中心）
            0,
            // 统一 17-32（下巴右侧→右太阳穴）→ InsightFace 24-18, 32-25, 17（倒排）
            24, 23, 22, 21, 20, 19, 18, 32, 31, 30, 29, 28, 27, 26, 25, 17,

            // ===== 非轮廓点 33-105 =====
            // 眉毛上部：统一 33-37（左眉）→ InsightFace 44, 45, 46, 47, 48
            44, 45, 46, 47, 48,
            // 眉毛上部：统一 38-42（右眉）→ InsightFace 102, 103, 104, 105, 101
            102, 103, 104, 105, 101,
            // 眉心：统一 43 → InsightFace 74
            74,
            // 鼻梁：统一 44-46 → InsightFace 75, 76, 86
            75, 76, 86,
            // 鼻尖：统一 47-51 → InsightFace 77, 78, 79, 80, 81
            77, 78, 79, 80, 81,
            // 左眼外轮廓：统一 52-57 → InsightFace 40, 41, 42, 43, 38, 39
            40, 41, 42, 43, 38, 39,
            // 右眼外轮廓：统一 58-63 → InsightFace 94, 95, 96, 93, 97, 98
            94, 95, 96, 93, 97, 98,
            // 左眉下部：统一 64-67 → InsightFace 35, 36, 37, 34
            35, 36, 37, 34,
            // 右眉下部：统一 68-71 → InsightFace 82, 83, 84, 85
            82, 83, 84, 85,
            // 左眼补充：统一 72-74 → InsightFace 72, 73, 71
            72, 73, 71,
            // 右眼补充：统一 75-77 → InsightFace 91, 92, 90
            91, 92, 90,
            // 山根：统一 78-79 → InsightFace 49, 50
            49, 50,
            // 鼻孔：统一 80-83 → InsightFace 51, 52, 53, 54
            51, 52, 53, 54,
            // 嘴巴外轮廓：统一 84-95 → InsightFace 64, 65, 66, 67, 68, 63, 62, 61, 60, 59, 58, 57
            64, 65, 66, 67, 68, 63, 62, 61, 60, 59, 58, 57,
            // 嘴巴内轮廓：统一 96-103 → InsightFace 55, 56, 69, 70, 87, 88, 89, 99
            55, 56, 69, 70, 87, 88, 89, 99,
            // 瞳孔：统一 104-105 → InsightFace 17, 32
            17, 32
        )
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
        val unified = FloatArray(POINT_COUNT * 2)

        // 完整 106 点重排映射
        for (unifiedIdx in 0 until POINT_COUNT) {
            val insightIdx = FULL_REMAP[unifiedIdx]
            val srcX = nativeLandmarks[insightIdx * 2]
            val srcY = nativeLandmarks[insightIdx * 2 + 1]

            unified[unifiedIdx * 2] = if (isFrontCamera) 1f - srcX else srcX
            unified[unifiedIdx * 2 + 1] = srcY
        }

        return Result.success(unified)
    }
}
