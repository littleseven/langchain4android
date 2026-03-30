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
    
    /** 暖色调强度 (0.0 - 1.0) */
    private var warmthStrength: Float = 0.0f
    
    /** 对比度 (0.5 - 1.5, 1.0 为原始) */
    private var contrast: Float = 1.0f
    
    /** Uniform 位置缓存 */
    private var uSmoothingLocation: Int = -1
    private var uWhiteningLocation: Int = -1
    private var uWarmthLocation: Int = -1
    private var uContrastLocation: Int = -1
    
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
    fun updateBeautyParams(smoothing: Float, whitening: Float) {
        smoothingStrength = smoothing.coerceIn(0f, 1f)
        whiteningStrength = whitening.coerceIn(0f, 1f)
        Log.v(TAG, "Beauty params updated: smoothing=$smoothingStrength, whitening=$whiteningStrength")
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
        
        // 设置 Uniform 值
        when (renderMode) {
            MODE_BEAUTY -> {
                shaderProgram.setFloat("uSmoothing", smoothingStrength)
                shaderProgram.setFloat("uWhitening", whiteningStrength)
            }
            MODE_ADVANCED -> {
                shaderProgram.setFloat("uSmoothing", smoothingStrength)
                shaderProgram.setFloat("uWhitening", whiteningStrength)
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
        uWarmthLocation = shaderProgram.getUniformLocation("uWarmth")
        uContrastLocation = shaderProgram.getUniformLocation("uContrast")
    }
    
    override fun release() {
        Log.d(TAG, "Releasing BeautyRenderer")
        super.release()
    }
}
