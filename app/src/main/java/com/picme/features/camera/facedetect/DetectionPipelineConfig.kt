package com.picme.features.camera.facedetect

/**
 * 检测流水线配置
 */
data class DetectionPipelineConfig(
    val roiDetector: RoiDetectorType = RoiDetectorType.MEDIAPIPE,
    val landmarkDetector: LandmarkDetectorType = LandmarkDetectorType.INSIGHTFACE_2D106,
    val useLooseCrop: Boolean = false  // LOOSE_CROP_SCALE = 1f or 1.2f
)

enum class RoiDetectorType {
    MEDIAPIPE,  // MediaPipe 468 点计算 ROI
    DET10G;     // InsightFace Det10G 检测 ROI
    
    val displayName: String
        get() = when (this) {
            MEDIAPIPE -> "MediaPipe (快速+精确)"
            DET10G -> "Det10G (轻量级)"
        }
}

enum class LandmarkDetectorType {
    INSIGHTFACE_2D106,  // InsightFace 2d106det (106 点)
    MEDIAPIPE;          // MediaPipe FaceLandmarker (468 点 → 适配为 106)
    
    val displayName: String
        get() = when (this) {
            INSIGHTFACE_2D106 -> "InsightFace 2D106 (高精度)"
            MEDIAPIPE -> "MediaPipe (468点)"
        }
}
