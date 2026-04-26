// FaceMakeupPass 片段着色器
// 基于 GPUPixel FaceMakeupFilter 片段 Shader
// 支持多种混合模式：Multiply(15), Overlay(17), HardLight(22)

precision mediump float;

varying vec2 vTextureCoord;     // 妆容纹理坐标
varying vec2 vScreenCoord;      // 屏幕/原始帧坐标

uniform sampler2D uInputTexture;    // 原始帧纹理（来自前置 Pass）
uniform sampler2D uMakeupTexture;   // 妆容纹理（mouth.png / blusher.png）
uniform float uIntensity;           // 妆容强度 [0, 1]
uniform int uBlendMode;             // 混合模式

// 混合函数：HardLight
float blendHardLight(float base, float blend) {
    return blend < 0.5 
        ? (2.0 * base * blend)
        : (1.0 - 2.0 * (1.0 - base) * (1.0 - blend));
}

vec3 blendHardLight(vec3 base, vec3 blend) {
    return vec3(
        blendHardLight(base.r, blend.r),
        blendHardLight(base.g, blend.g),
        blendHardLight(base.b, blend.b)
    );
}

// 混合函数：SoftLight
float blendSoftLight(float base, float blend) {
    return (blend < 0.5) 
        ? (base + (2.0 * blend - 1.0) * (base - base * base))
        : (base + (2.0 * blend - 1.0) * (sqrt(base) - base));
}

vec3 blendSoftLight(vec3 base, vec3 blend) {
    return vec3(
        blendSoftLight(base.r, blend.r),
        blendSoftLight(base.g, blend.g),
        blendSoftLight(base.b, blend.b)
    );
}

// 混合函数：Multiply
vec3 blendMultiply(vec3 base, vec3 blend) {
    return base * blend;
}

// 混合函数：Overlay
float blendOverlay(float base, float blend) {
    return base < 0.5 
        ? (2.0 * base * blend)
        : (1.0 - 2.0 * (1.0 - base) * (1.0 - blend));
}

vec3 blendOverlay(vec3 base, vec3 blend) {
    return vec3(
        blendOverlay(base.r, blend.r),
        blendOverlay(base.g, blend.g),
        blendOverlay(base.b, blend.b)
    );
}

// 统一混合入口
vec3 blendFunc(vec3 base, vec3 blend, int blendMode) {
    if (blendMode == 0) {
        return blend;                           // 替换模式
    } else if (blendMode == 15) {
        return blendMultiply(base, blend);      // Multiply / 正片叠底
    } else if (blendMode == 17) {
        return blendOverlay(base, blend);       // Overlay / 叠加
    } else if (blendMode == 22) {
        return blendHardLight(base, blend);     // HardLight / 强光
    }
    return blend;
}

void main() {
    // 采样妆容纹理（mouth.png / blusher.png）
    vec4 makeupColor = texture2D(uMakeupTexture, vTextureCoord);
    
    // 应用强度
    makeupColor = makeupColor * uIntensity;
    
    // 采样原始帧对应位置
    vec4 bgColor = texture2D(uInputTexture, vScreenCoord);
    
    // Alpha = 0 表示该像素无妆容，直接输出原始帧
    if (makeupColor.a == 0.0) {
        gl_FragColor = bgColor;
        return;
    }
    
    // 预乘 Alpha 还原
    vec3 makeupRgb = clamp(makeupColor.rgb * (1.0 / makeupColor.a), 0.0, 1.0);
    
    // 混合妆容与原始帧
    vec3 blended = blendFunc(bgColor.rgb, makeupRgb, uBlendMode);
    
    // 最终混合：根据妆容 Alpha 混合原始帧和混合结果
    vec3 finalColor = bgColor.rgb * (1.0 - makeupColor.a) + blended.rgb * makeupColor.a;
    
    gl_FragColor = vec4(finalColor, bgColor.a);
}
