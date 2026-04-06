package com.picme.core.image

import android.opengl.GLES20
import android.util.Log

/**
 * R 计划 - 美颜渲染器
 * 
 * 功能：
 * 1. 使用美颜 Shader 渲染相机预览帧
 * 2. 支持实时调整磨皮和美白强度
 * 3. 支持高级美颜（色调、对比度）
 * 
 * @author RD Team
 * @version 1.0 (R 计划)
 */
class BeautyRenderer : GLRenderer() {
    companion object {
        private const val TAG = "PicMe:BeautyRenderer"
        
        /** 调试模式：输出红色 */
        const val MODE_DEBUG_RED = 0
        
        /** 调试模式：输出纹理 R 通道 */
        const val MODE_DEBUG_TEXTURE_R = 1
        
        /** 正常模式：基础美颜 */
        const val MODE_BEAUTY = 2
        
        /** 高级模式：美颜 + 色调调整 */
        const val MODE_ADVANCED = 3
    }
    
    /** 当前渲染模式 */
    private var renderMode: Int = MODE_BEAUTY
    
    /** 磨皮强度 (0.0 - 1.0) */
    private var smoothingStrength: Float = 0.5f
    
    /** 美白强度 (0.0 - 1.0) */
    private var whiteningStrength: Float = 0.5f
    
    /** 大眼强度 (0.0 - 1.0) */
    private var bigEyesStrength: Float = 0f

    /** 瘦脸强度 (-1.0 - 1.0) */
    private var slimFaceStrength: Float = 0f

    /** 唇色强度 (0.0 - 1.0) */
    private var lipColorStrength: Float = 0f

    /** 唇色色号索引 (0 - 11) */
    private var lipColorIndex: Int = 0

    /** 人脸中心与眼睛关键点（归一化坐标） */
    private var faceCenterX: Float = 0.5f
    private var faceCenterY: Float = 0.5f
    private var leftEyeX: Float = 0.4f
    private var leftEyeY: Float = 0.45f
    private var rightEyeX: Float = 0.6f
    private var rightEyeY: Float = 0.45f
    private var mouthCenterX: Float = 0.5f
    private var mouthCenterY: Float = 0.62f
    private var mouthLeftX: Float = 0.42f
    private var mouthLeftY: Float = 0.62f
    private var mouthRightX: Float = 0.58f
    private var mouthRightY: Float = 0.62f
    private var upperLipCenterX: Float = 0.5f
    private var upperLipCenterY: Float = 0.60f
    private var lowerLipCenterX: Float = 0.5f
    private var lowerLipCenterY: Float = 0.66f
    private var faceRadius: Float = 0.18f
    private var hasFace: Float = 0f

    /** 暖色调强度 (0.0 - 1.0) */
    private var warmthStrength: Float = 0.0f
    
    /** 对比度 (0.5 - 1.5, 1.0 为原始) */
    private var contrast: Float = 1.0f
    
    /** 渲染帧计数器 */
    private var renderFrameCount: Long = 0

    /** Uniform 位置缓存 */
    private var uSmoothingLocation: Int = -1
    private var uWhiteningLocation: Int = -1
    private var uBigEyesLocation: Int = -1
    private var uSlimFaceLocation: Int = -1
    private var uFaceCenterLocation: Int = -1
    private var uLeftEyeLocation: Int = -1
    private var uRightEyeLocation: Int = -1
    private var uFaceRadiusLocation: Int = -1
    private var uHasFaceLocation: Int = -1
    private var uMouthCenterLocation: Int = -1
    private var uMouthLeftLocation: Int = -1
    private var uMouthRightLocation: Int = -1
    private var uUpperLipCenterLocation: Int = -1
    private var uLowerLipCenterLocation: Int = -1
    private var uWarmthLocation: Int = -1
    private var uContrastLocation: Int = -1
    private var uLipColorLocation: Int = -1
    private var uLipColorIndexLocation: Int = -1
    
    /**
     * 设置渲染模式
     */
    fun setRenderMode(mode: Int) {
        if (renderMode != mode) {
            Log.d(TAG, "Render mode changed: $renderMode -> $mode")
            renderMode = mode
            
            // 重新编译 Shader
            isInitialized = false
            shaderProgram.release()
            
            if (onCompileShader()) {
                aPositionLocation = shaderProgram.getAttribLocation("aPosition")
                aTextureCoordLocation = shaderProgram.getAttribLocation("aTextureCoord")
                uTextureLocation = shaderProgram.getUniformLocation("uTexture")
                uTextureTransformLocation = shaderProgram.getUniformLocation("uTextureTransform")
                
                initUniformLocations()
                isInitialized = true
            }
        }
    }
    
    /**
     * 更新美颜参数
     */
    fun updateBeautyParams(
        smoothing: Float,
        whitening: Float,
        bigEyes: Float = 0f,
        slimFace: Float = 0f,
        lipColor: Float = 0f,
        lipColorIndex: Int = 0
    ) {
        smoothingStrength = smoothing.coerceIn(0f, 1f)
        whiteningStrength = whitening.coerceIn(0f, 1f)
        bigEyesStrength = bigEyes.coerceIn(0f, 1f)
        slimFaceStrength = slimFace.coerceIn(-1f, 1f)
        lipColorStrength = lipColor.coerceIn(0f, 1f)
        this.lipColorIndex = lipColorIndex.coerceIn(0, 11)
        Log.d(
            TAG,
            "Beauty params updated: smoothing=$smoothingStrength, whitening=$whiteningStrength, " +
                "bigEyes=$bigEyesStrength, slimFace=$slimFaceStrength, lipColor=$lipColorStrength, " +
                "lipColorIndex=${this.lipColorIndex}"
        )
    }
    
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
    ) {
        this.faceCenterX = faceCenterX.coerceIn(0f, 1f)
        this.faceCenterY = faceCenterY.coerceIn(0f, 1f)
        this.leftEyeX = leftEyeX.coerceIn(0f, 1f)
        this.leftEyeY = leftEyeY.coerceIn(0f, 1f)
        this.rightEyeX = rightEyeX.coerceIn(0f, 1f)
        this.rightEyeY = rightEyeY.coerceIn(0f, 1f)
        this.mouthCenterX = mouthCenterX.coerceIn(0f, 1f)
        this.mouthCenterY = mouthCenterY.coerceIn(0f, 1f)
        this.mouthLeftX = mouthLeftX.coerceIn(0f, 1f)
        this.mouthLeftY = mouthLeftY.coerceIn(0f, 1f)
        this.mouthRightX = mouthRightX.coerceIn(0f, 1f)
        this.mouthRightY = mouthRightY.coerceIn(0f, 1f)
        this.upperLipCenterX = upperLipCenterX.coerceIn(0f, 1f)
        this.upperLipCenterY = upperLipCenterY.coerceIn(0f, 1f)
        this.lowerLipCenterX = lowerLipCenterX.coerceIn(0f, 1f)
        this.lowerLipCenterY = lowerLipCenterY.coerceIn(0f, 1f)
        this.faceRadius = faceRadius.coerceIn(0.08f, 0.45f)
        this.hasFace = if (hasFace) 1f else 0f
        Log.d(
            TAG,
            "FaceWarp params received: center=(${this.faceCenterX}, ${this.faceCenterY}), " +
                "mouth=(${this.mouthCenterX}, ${this.mouthCenterY}), " +
                "mouthL=(${this.mouthLeftX}, ${this.mouthLeftY}), mouthR=(${this.mouthRightX}, ${this.mouthRightY}), " +
                "upper=(${this.upperLipCenterX}, ${this.upperLipCenterY}), lower=(${this.lowerLipCenterX}, ${this.lowerLipCenterY}), " +
                "radius=${this.faceRadius}, hasFace=${this.hasFace}, slimFace=$slimFaceStrength, bigEyes=$bigEyesStrength"
        )
    }

    /**
     * 更新高级参数
     */
    fun updateAdvancedParams(warmth: Float, contrast: Float) {
        warmthStrength = warmth.coerceIn(0f, 1f)
        this.contrast = contrast.coerceIn(0.5f, 1.5f)
        Log.v(TAG, "Advanced params updated: warmth=$warmthStrength, contrast=${this.contrast}")
    }
    
    override fun onCompileShader(): Boolean {
        val vertexShader = BeautyShaders.VERTEX_SHADER
        
        val fragmentShader = when (renderMode) {
            MODE_DEBUG_RED -> BeautyShaders.FRAGMENT_SHADER_DEBUG_RED
            MODE_DEBUG_TEXTURE_R -> BeautyShaders.FRAGMENT_SHADER_DEBUG_TEXTURE_R
            MODE_ADVANCED -> BeautyShaders.FRAGMENT_SHADER_BEAUTY_ADVANCED
            else -> BeautyShaders.FRAGMENT_SHADER_BEAUTY
        }
        
        Log.d(TAG, "Compiling shader: mode=$renderMode")
        return shaderProgram.compile(vertexShader, fragmentShader)
    }
    
    override fun onBeforeRender() {
        super.onBeforeRender()
        
        // 初始化 Uniform 位置
        initUniformLocations()
        
        // 关键日志：每60帧输出一次当前状态
        if (renderFrameCount % 60 == 0L) {
            Log.d(TAG, "onBeforeRender: hasFace=$hasFace, slimFace=$slimFaceStrength, bigEyes=$bigEyesStrength, " +
                "center=($faceCenterX, $faceCenterY), radius=$faceRadius")
        }
        renderFrameCount++

        // 设置 Uniform 值
        when (renderMode) {
            MODE_BEAUTY -> {
                shaderProgram.setFloat("uSmoothing", smoothingStrength)
                shaderProgram.setFloat("uWhitening", whiteningStrength)
                shaderProgram.setFloat("uBigEyes", bigEyesStrength)
                shaderProgram.setFloat("uSlimFace", slimFaceStrength)
                shaderProgram.setFloat("uFaceRadius", faceRadius)
                shaderProgram.setFloat("uHasFace", hasFace)
                shaderProgram.setFloat("uLipColor", lipColorStrength)
                shaderProgram.setInt("uLipColorIndex", lipColorIndex)
                shaderProgram.setVec2("uFaceCenter", faceCenterX, faceCenterY)
                shaderProgram.setVec2("uMouthCenter", mouthCenterX, mouthCenterY)
                shaderProgram.setVec2("uMouthLeft", mouthLeftX, mouthLeftY)
                shaderProgram.setVec2("uMouthRight", mouthRightX, mouthRightY)
                shaderProgram.setVec2("uUpperLipCenter", upperLipCenterX, upperLipCenterY)
                shaderProgram.setVec2("uLowerLipCenter", lowerLipCenterX, lowerLipCenterY)
                shaderProgram.setVec2("uLeftEye", leftEyeX, leftEyeY)
                shaderProgram.setVec2("uRightEye", rightEyeX, rightEyeY)
            }
            MODE_ADVANCED -> {
                shaderProgram.setFloat("uSmoothing", smoothingStrength)
                shaderProgram.setFloat("uWhitening", whiteningStrength)
                shaderProgram.setFloat("uBigEyes", bigEyesStrength)
                shaderProgram.setFloat("uSlimFace", slimFaceStrength)
                shaderProgram.setFloat("uFaceRadius", faceRadius)
                shaderProgram.setFloat("uHasFace", hasFace)
                shaderProgram.setFloat("uLipColor", lipColorStrength)
                shaderProgram.setInt("uLipColorIndex", lipColorIndex)
                shaderProgram.setVec2("uFaceCenter", faceCenterX, faceCenterY)
                shaderProgram.setVec2("uMouthCenter", mouthCenterX, mouthCenterY)
                shaderProgram.setVec2("uMouthLeft", mouthLeftX, mouthLeftY)
                shaderProgram.setVec2("uMouthRight", mouthRightX, mouthRightY)
                shaderProgram.setVec2("uUpperLipCenter", upperLipCenterX, upperLipCenterY)
                shaderProgram.setVec2("uLowerLipCenter", lowerLipCenterX, lowerLipCenterY)
                shaderProgram.setVec2("uLeftEye", leftEyeX, leftEyeY)
                shaderProgram.setVec2("uRightEye", rightEyeX, rightEyeY)
                shaderProgram.setFloat("uWarmth", warmthStrength)
                shaderProgram.setFloat("uContrast", contrast)
            }
        }
        
        // 绑定纹理单元
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTextureLocation, 0)
    }
    
    /**
     * 初始化 Uniform 位置
     */
    private fun initUniformLocations() {
        uSmoothingLocation = shaderProgram.getUniformLocation("uSmoothing")
        uWhiteningLocation = shaderProgram.getUniformLocation("uWhitening")
        uBigEyesLocation = shaderProgram.getUniformLocation("uBigEyes")
        uSlimFaceLocation = shaderProgram.getUniformLocation("uSlimFace")
        uFaceCenterLocation = shaderProgram.getUniformLocation("uFaceCenter")
        uLeftEyeLocation = shaderProgram.getUniformLocation("uLeftEye")
        uRightEyeLocation = shaderProgram.getUniformLocation("uRightEye")
        uFaceRadiusLocation = shaderProgram.getUniformLocation("uFaceRadius")
        uHasFaceLocation = shaderProgram.getUniformLocation("uHasFace")
        uMouthCenterLocation = shaderProgram.getUniformLocation("uMouthCenter")
        uMouthLeftLocation = shaderProgram.getUniformLocation("uMouthLeft")
        uMouthRightLocation = shaderProgram.getUniformLocation("uMouthRight")
        uUpperLipCenterLocation = shaderProgram.getUniformLocation("uUpperLipCenter")
        uLowerLipCenterLocation = shaderProgram.getUniformLocation("uLowerLipCenter")
        uWarmthLocation = shaderProgram.getUniformLocation("uWarmth")
        uContrastLocation = shaderProgram.getUniformLocation("uContrast")
        uLipColorLocation = shaderProgram.getUniformLocation("uLipColor")
        uLipColorIndexLocation = shaderProgram.getUniformLocation("uLipColorIndex")
    }
    
    override fun release() {
        Log.d(TAG, "Releasing BeautyRenderer")
        super.release()
    }
}
