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
    // 注意：OpenGL ES 纹理坐标原点在左下角，但相机预览纹理可能在左上角
    // 需要 Y 轴翻转来正确采样原始帧
    vScreenCoord = vec2(aPosition.x * 0.5 + 0.5, -aPosition.y * 0.5 + 0.5);
}
