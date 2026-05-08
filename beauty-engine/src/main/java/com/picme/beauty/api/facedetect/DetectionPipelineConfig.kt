package com.picme.beauty.api.facedetect

/**
 * 检测流水线配置
 */
data class DetectionPipelineConfig(
    val roiDetector: RoiDetectorType = RoiDetectorType.MEDIAPIPE,
    val landmarkDetector: LandmarkDetectorType = LandmarkDetectorType.INSIGHTFACE_2D106,
    val useLooseCrop: Boolean = false
)

enum class RoiDetectorType {
    MEDIAPIPE,
    DET10G;

    val displayName: String
        get() = when (this) {
            MEDIAPIPE -> "MediaPipe (快速+精确)"
            DET10G -> "Det10G (轻量级)"
        }
}

enum class LandmarkDetectorType {
    INSIGHTFACE_2D106,
    MEDIAPIPE;

    val displayName: String
        get() = when (this) {
            INSIGHTFACE_2D106 -> "InsightFace 2D106 (高精度)"
            MEDIAPIPE -> "MediaPipe (468点)"
        }
}
