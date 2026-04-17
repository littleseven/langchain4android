package com.picme.beauty.internal

/**
 * 统一美颜 Shader 链
 * 
 * 核心设计：预览和拍照共用同一套 Shader 实例，确保 100% 一致性
 * 
 * Shader 执行顺序（按美颜管线）：
 * 1. 磨皮 (Smoothing) - 双边滤波
 * 2. 美白 (Whitening) - YUV 亮度
 * 3. 瘦脸 (SlimFace) - FaceWarp 变形
 * 4. 大眼 (BigEyes) - 径向放大
 * 5. 唇色 (LipColor) - HSV 色相调整
 * 6. 腮红 (Blush) - 椭圆染色
 * 7. 滤镜 (Filter) - ColorMatrix
 * 
 * 每个 Shader 的输出作为下一个 Shader 的输入，形成链式处理
 */
class BeautyShaderChain(
    private val smoothingShader: SmoothingShader? = null,
    private val whiteningShader: WhiteningShader? = null,
    private val slimFaceShader: SlimFaceShader? = null,
    private val bigEyesShader: BigEyesShader? = null,
    private val lipColorShader: LipColorShader? = null,
    private val blushShader: BlushShader? = null,
    private val filterShader: ColorMatrixShader? = null
) {
    /**
     * 当前美颜参数
     */
    var params: BeautyParams = BeautyParams()
        set(value) {
            field = value
            updateShaderParams(value)
        }
    
    /**
     * 人脸数据（用于瘦脸、大眼等需要关键点的效果）
     */
    var faceData: FaceData? = null
        set(value) {
            field = value
            updateFaceData(value)
        }
    
    /**
     * 执行 Shader 链渲染
     * 
     * @param inputTexture 输入纹理 ID
     * @param outputTexture 输出纹理 ID（通常绑定到 FBO）
     */
    fun render(inputTexture: Int, outputTexture: Int) {
        var currentTexture = inputTexture
        
        // 1. 磨皮（如果启用）
        if (params.smoothing > 0 && smoothingShader != null) {
            currentTexture = smoothingShader.render(currentTexture, params.smoothing)
        }
        
        // 2. 美白（如果启用）
        if (params.whitening > 0 && whiteningShader != null) {
            currentTexture = whiteningShader.render(currentTexture, params.whitening)
        }
        
        // 3. 瘦脸（如果启用且有面部数据）
        if (params.slimFace != 0f && slimFaceShader != null && faceData != null) {
            currentTexture = slimFaceShader.render(currentTexture, faceData!!, params.slimFace)
        }
        
        // 4. 大眼（如果启用且有面部数据）
        if (params.bigEyes > 0 && bigEyesShader != null && faceData != null) {
            currentTexture = bigEyesShader.render(currentTexture, faceData!!, params.bigEyes)
        }
        
        // 5. 唇色（如果启用）
        if (params.lipColorIntensity > 0 && lipColorShader != null && faceData != null) {
            currentTexture = lipColorShader.render(
                currentTexture, 
                faceData!!, 
                params.lipColorIntensity,
                params.lipColorIndex
            )
        }
        
        // 6. 腮红（如果启用）
        if (params.blush > 0 && blushShader != null && faceData != null) {
            currentTexture = blushShader.render(currentTexture, faceData!!, params.blush)
        }
        
        // 7. 滤镜（始终执行，默认无效果）
        filterShader?.renderToOutput(currentTexture, outputTexture, params.filterType)
            ?: run {
                // 如果没有滤镜 Shader，直接复制
                copyTexture(currentTexture, outputTexture)
            }
    }
    
    /**
     * 更新所有 Shader 的参数
     */
    private fun updateShaderParams(params: BeautyParams) {
        smoothingShader?.setIntensity(params.smoothing)
        whiteningShader?.setIntensity(params.whitening)
        slimFaceShader?.setIntensity(params.slimFace)
        bigEyesShader?.setIntensity(params.bigEyes)
        lipColorShader?.setIntensity(params.lipColorIntensity)
        lipColorShader?.setColorIndex(params.lipColorIndex)
        blushShader?.setIntensity(params.blush)
        filterShader?.setFilterType(params.filterType)
    }
    
    /**
     * 更新人脸数据到需要它的 Shader
     */
    private fun updateFaceData(faceData: FaceData?) {
        faceData ?: return
        slimFaceShader?.setFaceData(faceData)
        bigEyesShader?.setFaceData(faceData)
        lipColorShader?.setFaceData(faceData)
        blushShader?.setFaceData(faceData)
    }
    
    /**
     * 简单的纹理复制（当没有滤镜 Shader 时使用）
     */
    private fun copyTexture(input: Int, output: Int) {
        // TODO: 实现纹理复制，或使用默认 passthrough shader
    }
    
    /**
     * 释放所有 Shader 资源
     */
    fun release() {
        smoothingShader?.release()
        whiteningShader?.release()
        slimFaceShader?.release()
        bigEyesShader?.release()
        lipColorShader?.release()
        blushShader?.release()
        filterShader?.release()
    }
}

/**
 * 美颜参数数据类
 * 包含所有美颜效果的强度设置
 */
data class BeautyParams(
    val smoothing: Int = 0,           // 磨皮 0-100
    val whitening: Int = 0,           // 美白 0-100
    val slimFace: Float = 0f,         // 瘦脸 -50~+50
    val bigEyes: Int = 0,             // 大眼 0-100
    val lipColorIntensity: Int = 0,   // 唇色强度 0-100
    val lipColorIndex: Int = 0,       // 唇色色号 0-11
    val blush: Int = 0,               // 腮红 0-100
    val filterType: FilterType = FilterType.NONE  // 滤镜类型
)

/**
 * 滤镜类型枚举
 */
enum class FilterType {
    NONE,
    LEICA_CLASSIC,
    LEICA_VIBRANT,
    FILM_GOLD,
    FILM_FUJI,
    COOL,
    WARM,
    VINTAGE,
    LEICA_BW
}

/**
 * 人脸数据结构
 * 包含关键点、轮廓等信息
 */
data class FaceData(
    val faceId: Int,
    val boundingBox: RectF,
    val landmarks: List<PointF>,
    val leftEyeOpenProbability: Float = 1f,
    val rightEyeOpenProbability: Float = 1f,
    val smilingProbability: Float = 0f
) {
    // 常用关键点索引（基于 ML Kit 468 点 Face Mesh）
    companion object {
        const val LEFT_EYE_CENTER = 33
        const val RIGHT_EYE_CENTER = 263
        const val NOSE_TIP = 4
        const val MOUTH_CENTER = 13
        const val LEFT_CHEEK = 234
        const val RIGHT_CHEEK = 454
        const val CHIN = 152
    }
}

/**
 * 矩形区域（与 Android RectF 兼容）
 */
data class RectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * 点坐标（与 Android PointF 兼容）
 */
data class PointF(
    val x: Float,
    val y: Float
)
