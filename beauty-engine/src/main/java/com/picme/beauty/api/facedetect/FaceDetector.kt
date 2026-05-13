package com.picme.beauty.api.facedetect

import android.graphics.Bitmap

/**
 * 人脸检测器公开接口
 *
 * 由 beauty-engine 内部实现，app 模块通过 Factory 获取实例。
 * 所有检测输入均为 Bitmap，ImageProxy → Bitmap 转换由调用方负责。
 */
interface FaceDetector {
    /**
     * 实时预览帧检测（Bitmap 输入）
     *
     * @param bitmap RGBA Bitmap（已由调用方从 CameraX ImageProxy 转换）
     * @param rotationDegrees 图像旋转角度（0/90/180/270）
     * @param lensFacing 镜头方向（CameraSelector.LENS_FACING_FRONT / BACK）
     * @return 检测结果，无人脸返回 null
     */
    fun detect(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int): FaceDetectionResult?

    /**
     * [GPU 检测优化 Phase 2] 实时预览帧检测（YUV Image 直接输入）
     *
     * MediaPipe 专用路径：跳过 CPU 端 Bitmap 生成，直接从 YUV_420_888
     * 数据进行 GPU 推理。结果坐标根据 rotationDegrees 在适配层旋转。
     *
     * @param mediaImage android.media.Image（YUV_420_888）
     * @param rotationDegrees 图像旋转角度（0/90/180/270）
     * @param lensFacing 镜头方向
     * @return 检测结果，无人脸返回 null
     */
    fun detect(mediaImage: android.media.Image, rotationDegrees: Int, lensFacing: Int): FaceDetectionResult?

    /**
     * 拍照后静态图检测
     *
     * @param bitmap 静态图片 Bitmap
     * @param lensFacing 镜头方向
     * @return 检测结果，无人脸返回 null
     */
    fun detectPhoto(bitmap: Bitmap, lensFacing: Int): FaceDetectionResult?

    /**
     * 切换检测引擎模式
     */
    fun setEngineMode(mode: EngineType)

    /**
     * 获取最近一次检测耗时（ms）
     */
    fun getLastProcessTimeMs(): Long

    /**
     * 获取最近一次检测来源
     */
    fun getLastDetectionSource(): FaceDetectionSource

    /**
     * 更新检测流水线配置（ROI + Landmark 检测器组合）
     */
    fun updatePipelineConfig(config: DetectionPipelineConfig)

    /**
     * 释放所有检测资源
     */
    fun release()
}
