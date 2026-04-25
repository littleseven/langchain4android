// GPUPixel BeautyFaceFilter - 磨皮+美白+LUT 合并Pass
// 参考 GPUPixel BeautyFaceUnitFilter 的完整实现
//
// 输入：uInputTexture（原图）
// 输入：uMeanTexture（BoxBlur后的均值图）
// 输入：uVarTexture（BoxHighPass后的方差图）
// 输入：uLookUpGray, uLookUpOrigin, uLookUpSkin, uLookUpLight（LUT查找表）
// 输出：磨皮+美白后的图像

precision highp float;

uniform sampler2D uInputTexture;    // 原图
uniform sampler2D uMeanTexture;     // BoxBlur均值图
uniform sampler2D uVarTexture;      // BoxHighPass方差图
uniform sampler2D uLookUpGray;      // 灰度LUT
uniform sampler2D uLookUpOrigin;    // 原始色调LUT
uniform sampler2D uLookUpSkin;      // 肤色LUT
uniform sampler2D uLookUpLight;     // 美白LUT

uniform float uBlurAlpha;           // 磨皮强度 0~1
uniform float uSharpen;             // 锐化强度 0~1
uniform float uWhiten;              // 美白强度 0~1
uniform float uWidthOffset;         // 1.0 / width
uniform float uHeightOffset;        // 1.0 / height

varying vec2 vTextureCoord;

// GPUPixel 原始常量
const float levelRangeInv = 1.02657;
const float levelBlack = 0.0258820;
const float alpha = 0.7;

void main() {
    vec2 uv = vTextureCoord;
    vec4 iColor = texture2D(uInputTexture, uv);
    vec4 meanColor = texture2D(uMeanTexture, uv);
    vec4 varColor = texture2D(uVarTexture, uv);

    vec3 color = iColor.rgb;

    // ========== 磨皮部分 ==========
    if (uBlurAlpha > 0.0) {
        float theta = 0.1;
        // GPUPixel 边缘保留公式
        float p = clamp((min(iColor.r, meanColor.r - 0.1) - 0.2) * 4.0, 0.0, 1.0);
        float meanVar = (varColor.r + varColor.g + varColor.b) / 3.0;
        float kMin = (1.0 - meanVar / (meanVar + theta)) * p * uBlurAlpha;
        kMin = clamp(kMin, 0.0, 1.0);
        vec3 resultColor = mix(iColor.rgb, meanColor.rgb, kMin);

        // 9-tap 锐化核（GPUPixel原始）
        vec3 sum = 0.25 * iColor.rgb;
        sum += 0.125 * texture2D(uInputTexture, uv + vec2(-uWidthOffset, 0.0)).rgb;
        sum += 0.125 * texture2D(uInputTexture, uv + vec2(uWidthOffset, 0.0)).rgb;
        sum += 0.125 * texture2D(uInputTexture, uv + vec2(0.0, -uHeightOffset)).rgb;
        sum += 0.125 * texture2D(uInputTexture, uv + vec2(0.0, uHeightOffset)).rgb;
        sum += 0.0625 * texture2D(uInputTexture, uv + vec2(uWidthOffset, uHeightOffset)).rgb;
        sum += 0.0625 * texture2D(uInputTexture, uv + vec2(-uWidthOffset, -uHeightOffset)).rgb;
        sum += 0.0625 * texture2D(uInputTexture, uv + vec2(-uWidthOffset, uHeightOffset)).rgb;
        sum += 0.0625 * texture2D(uInputTexture, uv + vec2(uWidthOffset, -uHeightOffset)).rgb;

        vec3 hPass = iColor.rgb - sum;
        color = resultColor + uSharpen * hPass * 2.0;
    }

    // ========== 美白部分（LUT） ==========
    if (uWhiten > 0.0) {
        vec3 colorEPM = color;
        // Level调整
        color = clamp((colorEPM - vec3(levelBlack)) * levelRangeInv, 0.0, 1.0);

        // lookUpGray LUT（16x1）
        vec3 texel = vec3(
            texture2D(uLookUpGray, vec2(color.r, 0.5)).r,
            texture2D(uLookUpGray, vec2(color.g, 0.5)).g,
            texture2D(uLookUpGray, vec2(color.b, 0.5)).b
        );
        texel = mix(color, texel, 0.5);
        texel = mix(colorEPM, texel, alpha);
        texel = clamp(texel, 0.0, 1.0);

        // lookUpOrigin LUT（64x64, 4x4 blocks）
        float blueColor = texel.b * 15.0;
        vec2 quad1;
        quad1.y = floor(floor(blueColor) * 0.25);
        quad1.x = floor(blueColor) - (quad1.y * 4.0);
        vec2 quad2;
        quad2.y = floor(ceil(blueColor) * 0.25);
        quad2.x = ceil(blueColor) - (quad2.y * 4.0);
        vec2 texPos2 = texel.rg * 0.234375 + 0.0078125;
        vec2 texPos1 = quad1 * 0.25 + texPos2;
        texPos2 = quad2 * 0.25 + texPos2;
        vec3 newColor1Origin = texture2D(uLookUpOrigin, texPos1).rgb;
        vec3 newColor2Origin = texture2D(uLookUpOrigin, texPos2).rgb;
        vec3 colorOrigin = mix(newColor1Origin, newColor2Origin, fract(blueColor));
        texel = mix(colorOrigin, color, alpha);

        // lookUpSkin LUT（64x64, 4x4 blocks）
        texel = clamp(texel, 0.0, 1.0);
        blueColor = texel.b * 15.0;
        quad1.y = floor(floor(blueColor) * 0.25);
        quad1.x = floor(blueColor) - (quad1.y * 4.0);
        quad2.y = floor(ceil(blueColor) * 0.25);
        quad2.x = ceil(blueColor) - (quad2.y * 4.0);
        texPos2 = texel.rg * 0.234375 + 0.0078125;
        texPos1 = quad1 * 0.25 + texPos2;
        texPos2 = quad2 * 0.25 + texPos2;
        vec3 newColor1 = texture2D(uLookUpSkin, texPos1).rgb;
        vec3 newColor2 = texture2D(uLookUpSkin, texPos2).rgb;
        color = mix(newColor1, newColor2, fract(blueColor));
        color = clamp(color, 0.0, 1.0);

        // lookUpLight LUT（512x512, 8x8 blocks）
        float blueColorCustom = color.b * 63.0;
        vec2 quad1Custom;
        quad1Custom.y = floor(floor(blueColorCustom) / 8.0);
        quad1Custom.x = floor(blueColorCustom) - (quad1Custom.y * 8.0);
        vec2 quad2Custom;
        quad2Custom.y = floor(ceil(blueColorCustom) / 8.0);
        quad2Custom.x = ceil(blueColorCustom) - (quad2Custom.y * 8.0);
        vec2 texPos1Custom;
        texPos1Custom.x = (quad1Custom.x * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.r);
        texPos1Custom.y = (quad1Custom.y * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.g);
        vec2 texPos2Custom;
        texPos2Custom.x = (quad2Custom.x * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.r);
        texPos2Custom.y = (quad2Custom.y * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.g);
        vec3 newColor1Light = texture2D(uLookUpLight, texPos1Custom).rgb;
        vec3 newColor2Light = texture2D(uLookUpLight, texPos2Custom).rgb;
        vec3 colorCustom = mix(newColor1Light, newColor2Light, fract(blueColorCustom));
        color = mix(color, colorCustom, uWhiten);
    }

    gl_FragColor = vec4(color, iColor.a);
}
