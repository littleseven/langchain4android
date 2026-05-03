// Copy Pass - 将外部纹理(OES)复制到内部纹理(2D)
// 用于多Pass管线的第一步：将相机输入转换为可链式处理的2D纹理

#extension GL_OES_EGL_image_external : require

precision highp float;

uniform samplerExternalOES uCameraTexture;
uniform mat4 uTextureTransform;
uniform float uIsFrontCamera;

varying vec2 vTextureCoord;

void main() {
    vec2 coord = vTextureCoord;
    if (uIsFrontCamera > 0.5) {
        coord.x = 1.0 - coord.x;
    }
    vec2 uv = (uTextureTransform * vec4(coord, 0.0, 1.0)).xy;
    gl_FragColor = texture2D(uCameraTexture, uv);
}
