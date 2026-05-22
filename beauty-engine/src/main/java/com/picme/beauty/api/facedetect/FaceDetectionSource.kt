package com.picme.beauty.api.facedetect

/**
 * 人脸检测算法来源
 */
enum class FaceDetectionSource {
    NONE,
    MEDIAPIPE,
    INSIGHTFACE,
    MNN        // [性能优化] MNN Vulkan GPU 检测器
}
