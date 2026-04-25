// Debug Pass - 输出纯红色，用于验证Pass是否执行

precision highp float;

uniform sampler2D uInputTexture;

varying vec2 vTextureCoord;

void main() {
    // 输出纯红色，不混合原图
    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
}
