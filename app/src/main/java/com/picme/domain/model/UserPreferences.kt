package com.picme.domain.model

/**
 * 主题模式（领域模型，与 Android 平台无关）
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

/**
 * 应用语言（领域模型）
 */
enum class AppLanguage {
    SYSTEM, ENGLISH, CHINESE, TRADITIONAL_CHINESE
}

/**
 * 美颜引擎策略（领域模型）
 *
 * BIG_BEAUTY: R 计划自研 OpenGL ES 管线（默认主引擎）
 */
enum class BeautyStrategy {
    BIG_BEAUTY
}

/**
 * 人脸检测算法引擎模式（领域模型）
 */
enum class FaceDetectionEngineMode {
    MEDIAPIPE,
    INSIGHTFACE
}

/**
 * 人脸检测间隔档位（领域模型）
 */
enum class FaceDetectIntervalProfile {
    CONSERVATIVE,
    BALANCED,
    AGGRESSIVE
}

/**
 * InsightFace ROI 检测器类型（领域模型）
 */
enum class InsightFaceRoiDetectorType {
    MEDIAPIPE,  // MediaPipe 468 点计算 ROI
    DET10G,     // InsightFace Det10G 检测 ROI
    MNN         // MNN + Vulkan GPU 检测 ROI
}

/**
 * InsightFace 关键点检测器类型（领域模型）
 */
enum class InsightFaceLandmarkDetectorType {
    INSIGHTFACE_2D106,  // InsightFace 2d106det (106 点)
    MEDIAPIPE,          // MediaPipe FaceLandmarker (468 点 → 适配为 106)
    MNN                 // MNN + Vulkan GPU 检测关键点
}

