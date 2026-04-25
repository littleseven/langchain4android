// BoxBlur Pass - GPUPixel 风格均值模糊
// 生成磨皮所需的 meanColor 纹理
// 使用 9-tap 采样近似高斯模糊

precision highp float;

uniform sampler2D uInputTexture;
uniform float uWidthOffset;
uniform float uHeightOffset;

varying vec2 vTextureCoord;

void main() {
    vec2 uv = vTextureCoord;

    // 9-tap 采样（3x3 近似高斯）
    vec4 sum = texture2D(uInputTexture, uv) * 0.25;
    sum += texture2D(uInputTexture, uv + vec2(-uWidthOffset, 0.0)) * 0.125;
    sum += texture2D(uInputTexture, uv + vec2(uWidthOffset, 0.0)) * 0.125;
    sum += texture2D(uInputTexture, uv + vec2(0.0, -uHeightOffset)) * 0.125;
    sum += texture2D(uInputTexture, uv + vec2(0.0, uHeightOffset)) * 0.125;
    sum += texture2D(uInputTexture, uv + vec2(uWidthOffset, uHeightOffset)) * 0.0625;
    sum += texture2D(uInputTexture, uv + vec2(-uWidthOffset, -uHeightOffset)) * 0.0625;
    sum += texture2D(uInputTexture, uv + vec2(-uWidthOffset, uHeightOffset)) * 0.0625;
    sum += texture2D(uInputTexture, uv + vec2(uWidthOffset, -uHeightOffset)) * 0.0625;

    gl_FragColor = sum;
}
