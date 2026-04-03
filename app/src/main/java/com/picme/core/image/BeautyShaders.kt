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
        uniform float uBigEyes;
        uniform float uSlimFace;
        uniform vec2 uFaceCenter;
        uniform vec2 uLeftEye;
        uniform vec2 uRightEye;
        uniform float uFaceRadius;
        uniform float uHasFace;
        varying vec2 vTextureCoord;
        
        vec2 applyBigEye(vec2 uv, vec2 eyeCenter, float radius, float intensity) {
            vec2 dir = uv - eyeCenter;
            float dist = length(dir);
            if (dist >= radius) {
                return uv;
            }
            float percent = 1.0 - dist / radius;
            float factor = 1.0 - intensity * percent * 0.38;
            return eyeCenter + dir * factor;
        }
            
        vec2 applySlimFace(vec2 uv, vec2 center, float radius, float intensity) {
            vec2 dir = uv - center;
            float dist = length(dir);
            if (dist >= radius) {
                return uv;
            }

            // 使用双眼连线定义脸部“水平”方向，避免竖屏下沿错误轴形变。
            vec2 eyeAxis = normalize(uRightEye - uLeftEye);
            if (length(eyeAxis) < 0.0001) {
                eyeAxis = vec2(1.0, 0.0);
            }

            float percent = 1.0 - dist / radius;
            float strength = intensity * percent * percent * 0.45;
            float axisOffset = dot(dir, eyeAxis) / max(radius, 0.0001);
            vec2 offset = eyeAxis * axisOffset * strength * radius;
            return uv - offset;
        }
            
        vec2 warpCoord(vec2 uv) {
            if (uHasFace < 0.5) {
                return uv;
            }
            float eyeRadius = max(uFaceRadius * 0.22, 0.05);
            vec2 warped = uv;
            if (uBigEyes > 0.001) {
                warped = applyBigEye(warped, uLeftEye, eyeRadius, uBigEyes);
                warped = applyBigEye(warped, uRightEye, eyeRadius, uBigEyes);
            }
            if (abs(uSlimFace) > 0.001) {
                warped = applySlimFace(warped, uFaceCenter, uFaceRadius, uSlimFace);
            }
            return clamp(warped, 0.0, 1.0);
        }
            
        float skinMask(vec2 uv) {
            if (uHasFace < 0.5) {
                return 0.0;
            }

            // 使用椭圆区域近似人脸皮肤区域，避免整帧被磨皮。
            vec2 delta = uv - uFaceCenter;
            delta.y *= 1.22;
            float faceDist = length(delta) / max(uFaceRadius, 0.001);
            float faceArea = 1.0 - smoothstep(0.72, 1.05, faceDist);

            // 保留眼周细节，避免大眼区域被过度磨平。
            float eyeRadius = max(uFaceRadius * 0.18, 0.04);
            float leftEyeKeep = smoothstep(eyeRadius, eyeRadius * 0.55, length(uv - uLeftEye));
            float rightEyeKeep = smoothstep(eyeRadius, eyeRadius * 0.55, length(uv - uRightEye));

            return clamp(faceArea * leftEyeKeep * rightEyeKeep, 0.0, 1.0);
        }

        vec4 smoothSkin(vec2 uv, float intensity) {
            vec4 color = texture2D(uTexture, uv);
            vec4 color1 = texture2D(uTexture, uv + vec2(0.008, 0.0));
            vec4 color2 = texture2D(uTexture, uv + vec2(-0.008, 0.0));
            vec4 color3 = texture2D(uTexture, uv + vec2(0.0, 0.008));
            vec4 color4 = texture2D(uTexture, uv + vec2(0.0, -0.008));
            vec4 avgColor = (color + color1 + color2 + color3 + color4) / 5.0;
            float mask = skinMask(uv);
            return mix(color, avgColor, intensity * mask);
        }
        
        vec4 whitenSkin(vec4 color, float intensity, float mask) {
            vec3 whitened = color.rgb * (1.0 + intensity * 0.3 * mask);
            whitened = clamp(whitened, 0.0, 1.0);
            return vec4(whitened, color.a);
        }
        
        void main() {
            vec2 warpedUv = warpCoord(vTextureCoord);
            float mask = skinMask(warpedUv);
            vec4 smoothed = smoothSkin(warpedUv, uSmoothing);
            vec4 whitened = whitenSkin(smoothed, uWhitening, mask);
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
        uniform float uBigEyes;
        uniform float uSlimFace;
        uniform vec2 uFaceCenter;
        uniform vec2 uLeftEye;
        uniform vec2 uRightEye;
        uniform float uFaceRadius;
        uniform float uHasFace;
        uniform float uWarmth;
        uniform float uContrast;
        varying vec2 vTextureCoord;
        
        vec2 applyBigEye(vec2 uv, vec2 eyeCenter, float radius, float intensity) {
            vec2 dir = uv - eyeCenter;
            float dist = length(dir);
            if (dist >= radius) {
                return uv;
            }
            float percent = 1.0 - dist / radius;
            float factor = 1.0 - intensity * percent * 0.38;
            return eyeCenter + dir * factor;
        }

        vec2 applySlimFace(vec2 uv, vec2 center, float radius, float intensity) {
            vec2 dir = uv - center;
            float dist = length(dir);
            if (dist >= radius) {
                return uv;
            }

            // 使用双眼连线定义脸部“水平”方向，避免竖屏下沿错误轴形变。
            vec2 eyeAxis = normalize(uRightEye - uLeftEye);
            if (length(eyeAxis) < 0.0001) {
                eyeAxis = vec2(1.0, 0.0);
            }

            float percent = 1.0 - dist / radius;
            float strength = intensity * percent * percent * 0.45;
            float axisOffset = dot(dir, eyeAxis) / max(radius, 0.0001);
            vec2 offset = eyeAxis * axisOffset * strength * radius;
            return uv - offset;
        }

        vec2 warpCoord(vec2 uv) {
            if (uHasFace < 0.5) {
                return uv;
            }
            float eyeRadius = max(uFaceRadius * 0.22, 0.05);
            vec2 warped = uv;
            if (uBigEyes > 0.001) {
                warped = applyBigEye(warped, uLeftEye, eyeRadius, uBigEyes);
                warped = applyBigEye(warped, uRightEye, eyeRadius, uBigEyes);
            }
            if (abs(uSlimFace) > 0.001) {
                warped = applySlimFace(warped, uFaceCenter, uFaceRadius, uSlimFace);
            }
            return clamp(warped, 0.0, 1.0);
        }

        float skinMask(vec2 uv) {
            if (uHasFace < 0.5) {
                return 0.0;
            }

            vec2 delta = uv - uFaceCenter;
            delta.y *= 1.22;
            float faceDist = length(delta) / max(uFaceRadius, 0.001);
            float faceArea = 1.0 - smoothstep(0.72, 1.05, faceDist);

            float eyeRadius = max(uFaceRadius * 0.18, 0.04);
            float leftEyeKeep = smoothstep(eyeRadius, eyeRadius * 0.55, length(uv - uLeftEye));
            float rightEyeKeep = smoothstep(eyeRadius, eyeRadius * 0.55, length(uv - uRightEye));

            return clamp(faceArea * leftEyeKeep * rightEyeKeep, 0.0, 1.0);
        }

        vec4 smoothSkin(vec2 uv, float intensity) {
            vec4 color = texture2D(uTexture, uv);
            vec4 color1 = texture2D(uTexture, uv + vec2(0.008, 0.0));
            vec4 color2 = texture2D(uTexture, uv + vec2(-0.008, 0.0));
            vec4 color3 = texture2D(uTexture, uv + vec2(0.0, 0.008));
            vec4 color4 = texture2D(uTexture, uv + vec2(0.0, -0.008));
            vec4 avgColor = (color + color1 + color2 + color3 + color4) / 5.0;
            float mask = skinMask(uv);
            return mix(color, avgColor, intensity * mask);
        }
        
        vec4 adjustTone(vec4 color, float warmth, float contrast) {
            vec3 rgb = color.rgb;
            rgb.r += warmth * 0.05;
            rgb.b -= warmth * 0.05;
            rgb = (rgb - 0.5) * contrast + 0.5;
            rgb = clamp(rgb, 0.0, 1.0);
            return vec4(rgb, color.a);
        }
        
        void main() {
            vec2 warpedUv = warpCoord(vTextureCoord);
            float mask = skinMask(warpedUv);
            vec4 smoothed = smoothSkin(warpedUv, uSmoothing);
            vec4 whitened = smoothed * (1.0 + uWhitening * 0.3 * mask);
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
