# PicMe 实时美颜预览技术方案 D

## 1. 技术选型

### 方案 D：纯 OpenGL ES 2.0 + GLSurfaceView + 自定义 Shader

**技术栈**：
- **渲染视图**：GLSurfaceView（Android 原生 OpenGL ES 视图）
- **渲染管线**：OpenGL ES 2.0
- **Shader 语言**：GLSL ES 1.0
- **纹理格式**：GL_TEXTURE_EXTERNAL_OES（外部纹理，用于 CameraX）
- **美颜算法**：自定义 GLSL Shader（磨皮 + 美白）

### 为什么不使用 GPUImage？

1. **API 限制**：GPUImageFilter.onDraw 需要 FBO 和完整的渲染上下文
2. **类型互操作问题**：Kotlin 的 FloatBuffer 与 Java 的 Buffer! 类型不兼容
3. **架构冲突**：GPUImage 设计用于静态图片处理，不是实时视频流
4. **性能开销**：GPUImage 的滤镜组机制引入额外的 FBO 绑定/解绑操作

### 为什么不回退到方案 C？

1. **产品体验**：用户需要实时看到美颜效果，而不是拍照后才看到
2. **技术挑战**：方案 D 是移动端实时美颜的标准解决方案
3. **技术积累**：攻克方案 D 可以为未来更多特效打下基础

## 2. 技术架构

```
┌─────────────────────────────────────┐
│   CameraX (相机帧源)                │
│      ↓ SurfaceProvider              │
└─────────────────────────────────────┘
           ↓
┌─────────────────────────────────────┐
│   SurfaceTexture                    │
│   GL_TEXTURE_EXTERNAL_OES (ID: 1)   │
└─────────────────────────────────────┘
           ↓
┌─────────────────────────────────────┐
│   GLSurfaceView.Renderer            │
│   - onSurfaceCreated (初始化)       │
│   - onSurfaceChanged (视口)         │
│   - onDrawFrame (每帧渲染)          │
└─────────────────────────────────────┘
           ↓
┌─────────────────────────────────────┐
│   Shader Program                    │
│   Vertex Shader (顶点)              │
│   Fragment Shader (片元 + 美颜)     │
└─────────────────────────────────────┘
           ↓
┌─────────────────────────────────────┐
│   GLSurfaceView 显示                │
└─────────────────────────────────────┘
```

## 3. 核心实现

### 3.1 GLSurfaceView 初始化

```kotlin
class GPUImageBeautyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    init {
        setEGLContextClientVersion(2)  // OpenGL ES 2.0
        setRenderer(this)              // 设置渲染器
        renderMode = RENDERMODE_CONTINUOUSLY  // 持续渲染
    }
}
```

### 3.2 创建外部纹理

```kotlin
private fun createExternalTextureId(): Int {
    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    val textureId = textures[0]
    
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    
    // 设置纹理参数
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_MIN_FILTER,
        GLES20.GL_LINEAR
    )
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_MAG_FILTER,
        GLES20.GL_LINEAR
    )
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_WRAP_S,
        GLES20.GL_CLAMP_TO_EDGE
    )
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_WRAP_T,
        GLES20.GL_CLAMP_TO_EDGE
    )
    
    return textureId
}
```

### 3.3 创建 SurfaceTexture 给 CameraX

```kotlin
override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    cameraTextureId = createExternalTextureId()
    cameraSurfaceTexture = SurfaceTexture(cameraTextureId)
}

fun getCameraSurfaceTexture(): SurfaceTexture? {
    return cameraSurfaceTexture
}
```

### 3.4 CameraX 绑定

```kotlin
val surfaceTexture = gpuImageBeautyView.getCameraSurfaceTexture()
val preview = Preview.Builder().build().also {
    it.setSurfaceProvider(cameraExecutor) {
        Surface(surfaceTexture)
    }
}
```

### 3.5 顶点缓冲和纹理坐标

```kotlin
private fun initBuffers() {
    // 顶点坐标（标准化设备坐标）
    val vertices = floatArrayOf(
        -1f, -1f,  // 左下
         1f, -1f,  // 右下
        -1f,  1f,  // 左上
         1f,  1f   // 右上
    )
    
    // 纹理坐标
    val textureCoords = floatArrayOf(
        0f, 1f,  // 左下
        1f, 1f,  // 右下
        0f, 0f,  // 左上
        1f, 0f   // 右上
    )
    
    // 使用直接内存（Direct Buffer），避免 JNI 拷贝
    vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    vertexBuffer?.put(vertices)?.position(0)
    
    textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    textureBuffer?.put(textureCoords)?.position(0)
}
```

### 3.6 Shader 程序

**顶点着色器**：
```glsl
uniform mat4 uTextureTransform;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;

void main() {
    gl_Position = aPosition;
    vTextureCoord = (uTextureTransform * aTextureCoord).xy;
}
```

**片段着色器（带磨皮和美白）**：
```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform float uSmoothing;
uniform float uWhitening;
varying vec2 vTextureCoord;

// 简单的磨皮算法：盒式模糊
vec4 smoothSkin(vec2 uv, float intensity) {
    vec4 color = texture2D(uTexture, uv);
    vec4 color1 = texture2D(uTexture, uv + vec2(0.01, 0.0));
    vec4 color2 = texture2D(uTexture, uv + vec2(-0.01, 0.0));
    vec4 color3 = texture2D(uTexture, uv + vec2(0.0, 0.01));
    vec4 color4 = texture2D(uTexture, uv + vec2(0.0, -0.01));
    
    vec4 avgColor = (color + color1 + color2 + color3 + color4) / 5.0;
    return mix(color, avgColor, intensity);
}

// 美白算法：提高亮度
vec4 whitenSkin(vec4 color, float intensity) {
    vec3 whitened = color.rgb * (1.0 + intensity * 0.3);
    return vec4(whitened, color.a);
}

void main() {
    vec4 color = texture2D(uTexture, vTextureCoord);
    vec4 smoothed = smoothSkin(vTextureCoord, uSmoothing);
    vec4 whitened = whitenSkin(smoothed, uWhitening);
    gl_FragColor = whitened;
}
```

### 3.7 编译 Shader

```kotlin
private fun loadShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] != GLES20.GL_TRUE) {
        val error = GLES20.glGetShaderInfoLog(shader)
        android.util.Log.e("PicMe:GPUImageBeautyView", "Shader compile error: $error")
        GLES20.glDeleteShader(shader)
        return 0
    }
    
    return shader
}

private fun createProgram(): Int {
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
    val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)
    
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    
    val linkStatus = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] != GLES20.GL_TRUE) {
        val error = GLES20.glGetProgramInfoLog(program)
        android.util.Log.e("PicMe:GPUImageBeautyView", "Shader link error: $error")
        GLES20.glDeleteProgram(program)
        return -1
    }
    
    return program
}
```

### 3.8 渲染循环

```kotlin
override fun onDrawFrame(gl: GL10?) {
    // 清除屏幕
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    
    if (cameraTextureId != -1 && programId != -1) {
        // 1. 更新 SurfaceTexture（获取最新相机帧）
        cameraSurfaceTexture?.updateTexImage()
        
        // 2. 使用 Shader 程序
        GLES20.glUseProgram(programId)
        
        // 3. 绑定外部纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(uTextureLocation, 0)
        
        // 4. 设置美颜参数
        GLES20.glUniform1f(uSmoothingLocation, smoothingStrength / 100f)
        GLES20.glUniform1f(uWhiteningLocation, whiteningStrength / 100f)
        
        // 5. 启用顶点数组
        GLES20.glEnableVertexAttribArray(aPositionLocation)
        GLES20.glEnableVertexAttribArray(aTextureCoordLocation)
        
        // 6. 设置顶点坐标
        val vb: java.nio.Buffer = vertexBuffer as java.nio.Buffer
        vb.position(0)
        GLES20.glVertexAttribPointer(
            aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vb
        )
        
        // 7. 设置纹理坐标
        val tb: java.nio.Buffer = textureBuffer as java.nio.Buffer
        tb.position(0)
        GLES20.glVertexAttribPointer(
            aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 0, tb
        )
        
        // 8. 绘制四边形
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // 9. 禁用顶点数组
        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
        
        // 10. 解绑纹理
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }
}
```

## 4. 关键技术点

### 4.1 Direct Buffer 的类型问题

**问题**：Kotlin 的 `FloatBuffer` 与 Java 的 `Buffer!` 类型不兼容

**解决方案**：使用显式的 `java.nio.Buffer` 类型声明
```kotlin
val vb: java.nio.Buffer = vertexBuffer as java.nio.Buffer
GLES20.glVertexAttribPointer(..., vb)
```

### 4.2 SurfaceTexture 的生命周期

- **创建**：在 `onSurfaceCreated` 中创建
- **更新**：在 `onDrawFrame` 中调用 `updateTexImage()`
- **销毁**：在 `onPause` 或 `onDestroy` 中 release

### 4.3 Shader 性能优化

1. **避免复杂数学运算**：磨皮使用简单的盒式模糊，而不是高斯模糊
2. **减少纹理采样**：只采样 5 个像素（中心 + 上下左右）
3. **使用 mediump 精度**：在移动设备上比 highp 快

### 4.4 美颜参数范围

- **磨皮**：0-100（0 = 无效果，100 = 最强）
- **美白**：0-100（0 = 无效果，100 = 最亮）
- **瘦脸/大眼**：保留接口，未来实现

## 5. 编译和调试

### 编译命令
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/picme-debug.apk
```

### 日志查看
```bash
adb logcat | grep "PicMe:GPUImageBeautyView"
```

### 常见问题

1. **黑屏**：
   - 检查 Shader 编译是否成功
   - 检查纹理绑定是否正确
   - 检查 `glVertexAttribPointer` 的参数

2. **颜色异常**：
   - 检查纹理坐标是否正确
   - 检查 Shader 中的 `samplerExternalOES` 是否声明

3. **性能问题**：
   - 使用 `adb shell dumpsys SurfaceTextureClient` 查看帧率
   - 优化 Shader 复杂度

## 6. 未来扩展

### 6.1 更高级的美颜算法

- **双边模糊**：更好的磨皮效果
- **高通滤波**：锐化
- **色调分离**：美白牙齿

### 6.2 瘦脸和大眼

需要人脸检测（ML Kit Face Detection）获取人脸关键点，然后在 Shader 中进行网格变形。

### 6.3 滤镜效果

可以扩展支持：
- 复古滤镜
- 黑白滤镜
- 暖色/冷色滤镜

## 7. 参考资料

- [OpenGL ES 2.0 官方文档](https://www.khronos.org/opengles/)
- [Android GLSurfaceView 文档](https://developer.android.com/reference/android/opengl/GLSurfaceView)
- [GPUImage 源码](https://github.com/cyberagent/android-gpuimage)
- [GLSL 实时美颜教程](https://www.learnopengl.com/)

## 8. 项目文件清单

```
app/src/main/java/com/picme/core/image/
├── GPUImageBeautyView.kt      # 核心渲染 View
├── BeautyPreviewProcessor.kt  # 美颜处理器（可选）
└── GpuImageBeautyPreviewProvider.kt  # 预览提供者（可选）

app/src/main/java/com/picme/features/camera/
└── CameraScreen.kt            # 相机界面
```

## 9. 技术债务

1. **Shader 精度**：当前使用 mediump，可能在某些设备上精度不够
2. **磨皮算法**：盒式模糊较简单，可以升级为双边模糊
3. **性能监控**：缺少 FPS 监控和性能分析工具

## 10. 当前进展与下一步计划

### 10.1 已完成的工作（✅ 2026-03-29）

1. **纯 OpenGL ES 渲染管线**
   - ✅ GLSurfaceView 初始化成功
   - ✅ Renderer 实现完成
   - ✅ 渲染循环稳定运行（60fps）
   - ✅ Shader 编译和链接成功

2. **CameraX 集成**
   - ✅ SurfaceTexture 创建成功
   - ✅ Surface 创建成功
   - ✅ CameraX SurfaceProvider 被调用
   - ✅ 相机绑定成功

3. **美颜参数传递**
   - ✅ smoothing 参数可传递到 Shader
   - ✅ whitening 参数可传递到 Shader

4. **代码实现**
   - ✅ GPUImageBeautyView 核心类完成
   - ✅ 顶点缓冲和纹理坐标缓冲初始化
   - ✅ Shader 程序编译
   - ✅ CameraX 绑定逻辑

### 10.2 当前问题

**现象**：渲染循环正常，但屏幕显示黑色

**可能原因**：
1. SurfaceTexture 更新时机问题
2. 纹理坐标方向问题
3. CameraX 帧格式与 Shader 不匹配
4. GLSurfaceView 的 EGL 配置问题

**调试日志**：
```
SurfaceTexture ready: true
Binding Preview to GPUImageBeautyView surface
SurfaceProvider called, returning our surface
Camera bound: lensFacing=1
Frame rendered successfully (60fps)
```

### 10.3 下一步计划

#### 方案 D1：继续调试 OpenGL ES（高难度）
- 检查纹理坐标方向
- 添加更多调试日志
- 尝试使用 EGL 直接渲染

#### 方案 D2：使用 MediaCodec + OpenGL ES（推荐）
- 使用 MediaCodec 编码相机帧
- 使用 OpenGL ES 解码并渲染
- 优点：性能更好，可控性更高

#### 方案 D3：集成第三方美颜 SDK（最快）
- FaceUnity
- PerfectCorp
- 商汤

#### 方案 C：回退到 PreviewView + 拍照美颜（保底）

---

**文档版本**：1.0  
**最后更新**：2026-03-29  
**维护者**：RD 团队
