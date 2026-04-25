// FBO Copy Pass - 将FBO 2D纹理直接渲染到屏幕
// 用于验证FBO内容是否正确

precision highp float;

uniform sampler2D uInputTexture;

varying vec2 vTextureCoord;

void main() {
    gl_FragColor = texture2D(uInputTexture, vTextureCoord);
}
