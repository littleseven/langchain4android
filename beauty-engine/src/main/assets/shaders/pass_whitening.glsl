// Whitening Pass - 美白独立Pass
// 参考 GPUPixel BeautyFaceUnitFilter 的美白逻辑
//
// 输入：uInputTexture（磨皮后的图像）
// 输出：美白后的图像
//
// 算法：亮度提升 + 色调调整（冷白皮效果）

precision highp float;

uniform sampler2D uInputTexture;
uniform float uWhitening;      // 美白强度 0~1
uniform float uHasFace;        // 是否检测到人脸
uniform vec2 uFaceCenter;      // 人脸中心
uniform float uFaceRadius;     // 人脸半径
uniform vec2 uLeftEye;         // 左眼位置
uniform vec2 uRightEye;        // 右眼位置

varying vec2 vTextureCoord;

// 皮肤蒙版（与主Shader中的skinMask一致）
float skinMask(vec2 uv) {
    if (uHasFace < 0.5) {
        return 1.0;  // 无脸时全图生效
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

void main() {
    vec2 uv = vTextureCoord;
    vec4 color = texture2D(uInputTexture, uv);
    
    if (uWhitening < 0.001) {
        gl_FragColor = color;
        return;
    }
    
    float mask = skinMask(uv);
    if (mask < 0.01) {
        gl_FragColor = color;
        return;
    }
    
    vec3 rgb = color.rgb;
    float whitenAlpha = uWhitening * mask;
    
    // 步骤1: 亮度提升（参考GPUPixel的levelBlack/levelRangeInv逻辑）
    // 提升暗部，压缩动态范围，使皮肤更通透
    const float levelBlack = 0.0258820;
    const float levelRangeInv = 1.02657;
    vec3 leveled = clamp((rgb - vec3(levelBlack)) * levelRangeInv, 0.0, 1.0);
    
    // 步骤2: 混合原始颜色和提升后的颜色
    // 参考GPUPixel: texel = mix(color, texel, 0.5)
    vec3 brightened = mix(rgb, leveled, 0.5);
    
    // 步骤3: 应用美白强度
    vec3 whitened = mix(rgb, brightened, whitenAlpha);
    
    // 步骤4: 轻微提升蓝色通道（冷白皮效果）
    // 参考GPUPixel lookUpSkin/LUT逻辑中的色调调整
    float blueBoost = 1.0 + whitenAlpha * 0.08;
    whitened.b *= blueBoost;
    
    // 步骤5: 轻微降低红色通道（减少黄气）
    float redReduce = 1.0 - whitenAlpha * 0.05;
    whitened.r *= redReduce;
    
    // 步骤6: 轻微提升绿色通道（平衡肤色）
    float greenBoost = 1.0 + whitenAlpha * 0.02;
    whitened.g *= greenBoost;
    
    whitened = clamp(whitened, 0.0, 1.0);
    gl_FragColor = vec4(whitened, color.a);
}
