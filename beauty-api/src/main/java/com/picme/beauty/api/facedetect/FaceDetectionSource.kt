package com.picme.beauty.api.facedetect

/**
 * 人脸检测算法来源
 */
enum class FaceDetectionSource {
    NONE,
    MEDIAPIPE,
    MNN,       // [性能优化] MNN Vulkan GPU 检测器
    NCNN       // [性能优化] NCNN 轻量级检测器
}
