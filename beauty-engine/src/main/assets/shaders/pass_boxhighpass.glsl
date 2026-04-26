// BoxHighPass Pass - GPUPixel 风格高频方差图
// 生成磨皮所需的 varColor 纹理
// varColor = |原图 - BoxBlur均值图|

precision highp float;

uniform sampler2D uInputTexture;    // 原图
uniform sampler2D uMeanTexture;     // BoxBlur后的均值图（直接输入，无需再次模糊）
uniform float uDelta;               // 高通阈值（默认0.0）

varying vec2 vTextureCoord;

void main() {
    vec2 uv = vTextureCoord;

    vec4 iColor = texture2D(uInputTexture, uv);
    vec4 meanColor = texture2D(uMeanTexture, uv);

    // 计算方差 = |原图 - 均值图|
    vec4 diff = abs(iColor - meanColor);

    // 应用delta阈值
    vec4 varColor = max(diff - uDelta, 0.0);

    gl_FragColor = varColor;
}
