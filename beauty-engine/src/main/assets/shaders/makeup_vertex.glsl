// FaceMakeupPass 顶点着色器
// 基于 GPUPixel FaceMakeupFilter 顶点 Shader

attribute vec2 aPosition;           // 人脸关键点位置（NDC [-1,1]）
attribute vec2 aTextureCoord;       // 妆容纹理坐标（对应 mouth.png / blusher.png）

varying vec2 vTextureCoord;         // 妆容纹理采样坐标
varying vec2 vScreenCoord;          // 屏幕/原始帧采样坐标

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    
    // 妆容纹理坐标直接使用（由预定义的标准脸 UV 提供）
    vTextureCoord = aTextureCoord;
    
    // 屏幕坐标 = 顶点位置从 NDC [-1,1] 映射到 UV [0,1]
    // 用于从原始帧纹理采样对应位置的颜色
    vScreenCoord = aPosition * 0.5 + 0.5;
}
