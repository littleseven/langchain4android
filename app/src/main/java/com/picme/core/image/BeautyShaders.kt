package com.picme.core.image

/**
 * R 计划 - 美颜 Shader 定义
 * 
 * 包含：
 * 1. 顶点着色器（通用）
 * 2. 磨皮算法（盒式模糊）
 * 3. 美白算法（亮度提升）
 * 4. 组合效果
 * 
 * @author RD Team
 * @version 1.0 (R 计划)
 */
object BeautyShaders {
    
    /**
     * 顶点着色器
     * 
     * 功能：
     * - 传递顶点位置
     * - 传递纹理坐标
     * - 应用纹理变换矩阵
     */
    val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uTextureTransform;
        varying vec2 vTextureCoord;
        
        void main() {
            gl_Position = aPosition;
            vTextureCoord = (uTextureTransform * aTextureCoord).xy;
        }
    """.trimIndent()
    
    /**
     * 片段着色器 - 基础美颜（磨皮 + 美白）
     * 
     * 算法说明：
     * 1. 磨皮：使用盒式模糊模拟双边模糊
     * 2. 美白：提高 RGB 亮度
     */
    val FRAGMENT_SHADER_BEAUTY = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        
        uniform samplerExternalOES uTexture;
        uniform float uSmoothing;
        uniform float uWhitening;
        varying vec2 vTextureCoord;
        
        /**
         * 盒式模糊磨皮
         * 采样周围 5 个像素取平均
         */
        vec4 smoothSkin(vec2 uv, float intensity) {
            vec4 color = texture2D(uTexture, uv);
            
            // 采样周围像素
            vec4 color1 = texture2D(uTexture, uv + vec2(0.01, 0.0));
            vec4 color2 = texture2D(uTexture, uv + vec2(-0.01, 0.0));
            vec4 color3 = texture2D(uTexture, uv + vec2(0.0, 0.01));
            vec4 color4 = texture2D(uTexture, uv + vec2(0.0, -0.01));
            
            // 计算平均值
            vec4 avgColor = (color + color1 + color2 + color3 + color4) / 5.0;
            
            // 混合原始颜色和平滑颜色
            return mix(color, avgColor, intensity);
        }
        
        /**
         * 美白算法
         * 提高 RGB 亮度
         */
        vec4 whitenSkin(vec4 color, float intensity) {
            // 提高亮度
            vec3 whitened = color.rgb * (1.0 + intensity * 0.3);
            
            // 限制在 [0, 1] 范围
            whitened = clamp(whitened, 0.0, 1.0);
            
            return vec4(whitened, color.a);
        }
        
        void main() {
            // 采样原始颜色
            vec4 color = texture2D(uTexture, vTextureCoord);
            
            // 应用磨皮
            vec4 smoothed = smoothSkin(vTextureCoord, uSmoothing);
            
            // 应用美白
            vec4 whitened = whitenSkin(smoothed, uWhitening);
            
            gl_FragColor = whitened;
        }
    """.trimIndent()
    
    /**
     * 片段着色器 - 高级美颜（带色调调整）
     * 
     * 在美颜基础上增加：
     * - 暖色调调整
     * - 对比度调整
     */
    val FRAGMENT_SHADER_BEAUTY_ADVANCED = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        
        uniform samplerExternalOES uTexture;
        uniform float uSmoothing;
        uniform float uWhitening;
        uniform float uWarmth;
        uniform float uContrast;
        varying vec2 vTextureCoord;
        
        vec4 smoothSkin(vec2 uv, float intensity) {
            vec4 color = texture2D(uTexture, uv);
            vec4 color1 = texture2D(uTexture, uv + vec2(0.01, 0.0));
            vec4 color2 = texture2D(uTexture, uv + vec2(-0.01, 0.0));
            vec4 color3 = texture2D(uTexture, uv + vec2(0.0, 0.01));
            vec4 color4 = texture2D(uTexture, uv + vec2(0.0, -0.01));
            
            vec4 avgColor = (color + color1 + color2 + color3 + color4) / 5.0;
            return mix(color, avgColor, intensity);
        }
        
        vec4 adjustTone(vec4 color, float warmth, float contrast) {
            vec3 rgb = color.rgb;
            
            // 色调调整：增加暖色
            rgb.r += warmth * 0.05;
            rgb.b -= warmth * 0.05;
            
            // 对比度调整
            rgb = (rgb - 0.5) * contrast + 0.5;
            
            // 限制范围
            rgb = clamp(rgb, 0.0, 1.0);
            
            return vec4(rgb, color.a);
        }
        
        void main() {
            vec4 color = texture2D(uTexture, vTextureCoord);
            vec4 smoothed = smoothSkin(vTextureCoord, uSmoothing);
            vec4 whitened = smoothed * (1.0 + uWhitening * 0.3);
            vec4 toned = adjustTone(whitened, uWarmth, uContrast);
            gl_FragColor = toned;
        }
    """.trimIndent()
    
    /**
     * 片段着色器 - 调试用（输出纯色）
     * 
     * 用于测试渲染管线是否正常
     */
    val FRAGMENT_SHADER_DEBUG_RED = """
        precision mediump float;
        
        void main() {
            gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
        }
    """.trimIndent()
    
    /**
     * 片段着色器 - 调试用（输出纹理 R 通道）
     * 
     * 用于测试纹理是否正确采样
     */
    val FRAGMENT_SHADER_DEBUG_TEXTURE_R = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        
        uniform samplerExternalOES uTexture;
        varying vec2 vTextureCoord;
        
        void main() {
            vec4 color = texture2D(uTexture, vTextureCoord);
            gl_FragColor = vec4(color.r, color.r, color.r, 1.0);
        }
    """.trimIndent()
}
