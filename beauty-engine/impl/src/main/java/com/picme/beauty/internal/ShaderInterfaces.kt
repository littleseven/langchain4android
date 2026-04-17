package com.picme.beauty.internal

/**
 * 磨皮 Shader 接口
 * 使用双边滤波算法，保留边缘细节
 */
interface SmoothingShader {
    /**
     * 渲染磨皮效果
     * @param inputTexture 输入纹理 ID
     * @param intensity 磨皮强度 0-100
     * @return 输出纹理 ID（可能是新的纹理，也可能是输入纹理）
     */
    fun render(inputTexture: Int, intensity: Int): Int
    
    /**
     * 设置强度参数
     */
    fun setIntensity(intensity: Int)
    
    /**
     * 释放资源
     */
    fun release()
}

/**
 * 美白 Shader 接口
 * 基于 YUV/Lab 色彩空间亮度调整
 */
interface WhiteningShader {
    fun render(inputTexture: Int, intensity: Int): Int
    fun setIntensity(intensity: Int)
    fun release()
}

/**
 * 瘦脸 Shader 接口
 * 基于人脸关键点的 FaceWarp 网格变形
 */
interface SlimFaceShader {
    /**
     * @param intensity 瘦脸强度 -50~+50（负值为丰满脸型）
     */
    fun render(inputTexture: Int, faceData: FaceData, intensity: Float): Int
    fun setIntensity(intensity: Float)
    fun setFaceData(faceData: FaceData)
    fun release()
}

/**
 * 大眼 Shader 接口
 * 基于眼球中心的径向放大变换
 */
interface BigEyesShader {
    fun render(inputTexture: Int, faceData: FaceData, intensity: Int): Int
    fun setIntensity(intensity: Int)
    fun setFaceData(faceData: FaceData)
    fun release()
}

/**
 * 唇色 Shader 接口
 * HSV 色彩空间色相/饱和度调整
 */
interface LipColorShader {
    /**
     * @param intensity 唇色强度 0-100
     * @param colorIndex 色号索引 0-11
     */
    fun render(inputTexture: Int, faceData: FaceData, intensity: Int, colorIndex: Int): Int
    fun setIntensity(intensity: Int)
    fun setColorIndex(colorIndex: Int)
    fun setFaceData(faceData: FaceData)
    fun release()
}

/**
 * 腮红 Shader 接口
 * 双颊椭圆区域自然红润
 */
interface BlushShader {
    fun render(inputTexture: Int, faceData: FaceData, intensity: Int): Int
    fun setIntensity(intensity: Int)
    fun setFaceData(faceData: FaceData)
    fun release()
}

/**
 * 滤镜 Shader 接口
 * 基于 ColorMatrix 的色调调整
 */
interface ColorMatrixShader {
    /**
     * 渲染滤镜效果
     * @param inputTexture 输入纹理 ID
     * @param outputTexture 输出纹理 ID（必须绑定到 FBO）
     * @param filterType 滤镜类型
     */
    fun renderToOutput(inputTexture: Int, outputTexture: Int, filterType: FilterType)
    fun setFilterType(filterType: FilterType)
    fun release()
}
