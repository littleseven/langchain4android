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
 * PIXEL_FREE: PixelFreeEffects SDK（备用兜底引擎）
 * R_PLAN: R 计划自研 OpenGL ES 管线（默认主引擎）
 */
enum class BeautyStrategy {
    PIXEL_FREE,
    R_PLAN
}

/**
 * 人脸检测间隔档位（领域模型）
 */
enum class FaceDetectIntervalProfile {
    CONSERVATIVE,
    BALANCED,
    AGGRESSIVE
}

