package com.picme.domain.model

/**
 * 媒体类型领域模型
 */
enum class MediaType {
    PHOTO,      // 照片
    VIDEO,      // 视频
    PORTRAIT,   // 人像模式
    PRO,        // 专业模式
    DOCUMENT    // 文档模式 (OCR)
}

/**
 * 相机镜头方向
 */
enum class LensFacing(val cameraSelector: Int) {
    FRONT(androidx.camera.core.CameraSelector.LENS_FACING_FRONT),
    BACK(androidx.camera.core.CameraSelector.LENS_FACING_BACK)
}

/**
 * 画面比例
 */
enum class AspectRatio(val value: Int) {
    RATIO_4_3(0),
    RATIO_16_9(1),
    RATIO_1_1(2),
    RATIO_FULL(3)
}

/**
 * 场景模式
 */
enum class SceneMode {
    NONE,
    NIGHT,
    MOON
}

/**
 * 网格类型
 */
enum class GridType {
    NONE,
    THIRDS,
    GOLDEN
}
