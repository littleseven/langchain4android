package com.picme.beauty.api

/**
 * GL 美颜预览专属能力扩展接口
 *
 * 对 [BeautyPreviewProvider] 的能力扩展，描述 EGL/GL 渲染引擎独有的：
 * 1. 实时人脸变形参数（FaceWarp）
 * 2. 唇妆轮廓遮罩点（LipMask）
 * 3. 相机输入分辨率配置
 * 4. 画面缩放模式（FillCenter / FitCenter）
 *
 * 此接口保持纯 Kotlin，不引入任何 Android/ML Kit 平台类。
 *
 * @since Phase 2（模块化）
 * @see BeautyPreviewProvider
 */
interface BeautyPreviewCapability {

    /**
     * 更新实时人脸变形参数
     * 所有坐标均为归一化坐标（0.0 ~ 1.0），以屏幕预览区域为参考系
     */
    fun updateFaceWarpParams(
        faceCenterX: Float,
        faceCenterY: Float,
        leftEyeX: Float,
        leftEyeY: Float,
        rightEyeX: Float,
        rightEyeY: Float,
        mouthCenterX: Float,
        mouthCenterY: Float,
        mouthLeftX: Float,
        mouthLeftY: Float,
        mouthRightX: Float,
        mouthRightY: Float,
        upperLipCenterX: Float,
        upperLipCenterY: Float,
        lowerLipCenterX: Float,
        lowerLipCenterY: Float,
        faceRadius: Float,
        hasFace: Boolean
    )

    /**
     * 更新唇妆轮廓遮罩点
     * @param outerPoints 外轮廓点列表，Pair<normX, normY>
     * @param innerPoints 内轮廓点列表，Pair<normX, normY>
     */
    fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    )

    /**
     * 设置相机输入缓冲区尺寸
     */
    fun setCameraInputBufferSize(width: Int, height: Int)

    /**
     * 设置画面缩放模式
     * @param isFillCenter true = FILL_CENTER（全屏裁剪），false = FIT_CENTER（黑边保持比例）
     */
    fun setScaleMode(isFillCenter: Boolean)
}

