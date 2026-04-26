---
name: shader-debug
description: OpenGL ES Shader 开发调试技巧。Use when debugging GLSL shader compilation errors, black screen issues, incorrect rendering output, or texture sampling problems in the PicMe beauty engine.
---

# Shader 调试 Skill

## 黑屏排查流程

### 1. 验证 Shader 编译
```kotlin
val compiled = IntArray(1)
GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compiled, 0)
if (compiled[0] == 0) {
    val error = GLES20.glGetShaderInfoLog(shaderId)
    Log.e(TAG, "Shader compile error: $error")
}
```

### 2. 验证 Program 链接
```kotlin
val linked = IntArray(1)
GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linked, 0)
if (linked[0] == 0) {
    val error = GLES20.glGetProgramInfoLog(programId)
    Log.e(TAG, "Program link error: $error")
}
```

### 3. 强制纯色输出测试
在 fragment shader 开头添加：
```glsl
void main() {
    // 调试：强制红色输出
    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
    return;
    
    // ... 原有逻辑
}
```

## 纹理采样问题

### 检查纹理坐标
```glsl
// 在 fragment shader 中输出 UV 坐标颜色
void main() {
    vec2 uv = textureCoordinate;
    gl_FragColor = vec4(uv.x, uv.y, 0.0, 1.0);
}
```

### 检查纹理内容
```glsl
// 直接输出纹理颜色
void main() {
    vec4 tex = texture2D(inputImageTexture, textureCoordinate);
    gl_FragColor = vec4(tex.rgb, 1.0);
}
```

## 坐标系问题

### UV 坐标系方向
- OpenGL ES: 左下角 (0,0)，右上角 (1,1)
- Android Bitmap: 左上角 (0,0)，右下角 (1,1)
- **Y 轴可能需要翻转**：`uv.y = 1.0 - uv.y`

### NDC 坐标系
- 范围：[-1, 1]
- 左下角：(-1, -1)
- 右上角：(1, 1)

## 常见错误

### varying 变量不匹配
Vertex shader 和 fragment shader 的 varying 必须：
- 名称完全相同
- 类型完全相同
- 精度修饰符一致

### uniform 未绑定
```kotlin
val loc = GLES20.glGetUniformLocation(program, "uMyUniform")
if (loc < 0) {
    Log.w(TAG, "Uniform uMyUniform not found")
}
```

### 纹理单元冲突
```kotlin
GLES20.glActiveTexture(GLES20.GL_TEXTURE0) // 激活纹理单元0
GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
GLES20.glUniform1i(uniformLoc, 0) // 告诉 shader 使用纹理单元0
```

## 调试技巧

### 分步验证
1. 先验证顶点着色器：输出纯色
2. 再验证纹理加载：输出纹理坐标
3. 最后验证完整逻辑

### 使用 glGetError
```kotlin
val error = GLES20.glGetError()
if (error != GLES20.GL_NO_ERROR) {
    Log.e(TAG, "GL Error: $error")
}
```
