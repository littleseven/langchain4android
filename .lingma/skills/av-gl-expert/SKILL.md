---
name: av-gl-expert
description: PicMe 音视频与 OpenGL 渲染专家。涵盖 CameraX 集成、OpenGL ES 黑屏诊断、Shader 调试、性能优化、人脸关键点坐标映射等。Use when debugging OpenGL rendering issues, CameraX integration problems, shader compilation errors, or performance bottlenecks in the PicMe beauty engine.
---

# PicMe 音视频与 OpenGL 专家 (AV-GL Expert)

## 📋 Skill 概述

本 Skill 专为 PicMe 项目的**音视频处理**（CameraX、Media3）和 **OpenGL ES 渲染**（大美丽引擎、GPUPixel、EGL 离屏渲染）提供专家级开发和调试支持。

**核心价值**：
- 🔍 **深度诊断**：快速定位 OpenGL 黑屏、Shader 编译失败、EGL 上下文丢失等疑难问题
- 🛠️ **性能优化**：FBO 复用、PBO 异步读取、纹理内存管理、60fps 渲染管线优化
- 📊 **可视化调试**：实时 FPS、渲染耗时、空帧计数、检测来源标识
- 🎯 **最佳实践**：CameraX 集成、YUV→RGBA 转换、多 Pass 渲染、坐标映射规范

---

## 🎯 适用场景

### 何时使用本 Skill

1. **OpenGL 渲染问题**
   - 黑屏/白屏/花屏
   - Shader 编译/链接失败
   - 纹理坐标映射错误（画面倒置、拉伸、偏移）
   - FBO 采样异常

2. **EGL 上下文问题**
   - EGL 初始化失败
   - 上下文丢失/共享失败
   - 离屏渲染崩溃

3. **CameraX 集成问题**
   - Preview Surface 绑定失败
   - ImageAnalysis YUV 数据流中断
   - 前后置摄像头切换卡顿

4. **性能瓶颈**
   - 预览掉帧（< 30fps）
   - 拍照后处理慢（> 500ms）
   - 内存泄漏（Texture/FBO 未释放）

5. **美颜效果问题**
   - 磨皮/美白参数不生效
   - 人脸关键点偏移
   - 妆容贴图错位

---

## 🏗️ 项目技术架构总览

### 渲染引擎双轨制

```
┌─────────────────────────────────────────────────────┐
│              用户层：BeautyPreviewView               │
│         （统一入口，隐藏底层引擎差异）                 │
└──────────────┬──────────────────┬───────────────────┘
               │                  │
        大美丽模式           兼容模式 (GPUPixel)
     (默认主引擎)          (回退校验/兼容验证)
               │                  │
    ┌──────────▼──────────┐      │
    │  BeautyRenderer     │      │
    │  (Kotlin + GLSL)    │      │
    │                     │      │
    │  • 主 Shader        │      │
    │  • FaceMakeupPass   │      │
    │  • StyleEffectPass  │      │
    │  • OffscreenRender  │      │
    └──────────┬──────────┘      │
               │                  │
    ┌──────────▼──────────┐      │
    │   EGLCore           │      │
    │  (EGL 上下文管理)    │      │
    └─────────────────────┘      │
                                 │
                    ┌────────────▼────────────┐
                    │  GPUPixel Engine (C++)  │
                    │                         │
                    │  • SourceYUV            │
                    │  • Filter Chain         │
                    │  • SinkSurface          │
                    │  • FaceDetector (Mars)  │
                    └─────────────────────────┘
```

### 关键组件索引

| 组件 | 路径 | 职责 |
|------|------|------|
| **CameraPreviewRenderer** | `beauty-engine/src/main/java/com/picme/beauty/egl/CameraPreviewRenderer.kt` | 相机预览渲染器，管理 EGL 上下文和渲染线程 |
| **BeautyRenderer** | `beauty-engine/src/main/java/com/picme/beauty/egl/BeautyRenderer.kt` | 大美丽核心渲染器，实现多 Pass Shader 链 |
| **OffscreenRenderer** | `beauty-engine/impl/src/main/java/com/picme/beauty/internal/OffscreenRenderer.kt` | 离屏渲染器，用于拍照后处理 |
| **EGLCore** | `beauty-engine/src/main/java/com/picme/beauty/egl/EGLCore.kt` | EGL 上下文封装 |
| **GPUPixel Provider** | `beauty-engine/src/main/java/com/picme/beauty/gpupixel/GpupixelBeautyPreviewProvider.kt` | GPUPixel 引擎 Kotlin 桥接 |
| **CameraUseCasesBinder** | `app/src/main/java/com/picme/features/camera/CameraUseCasesBinder.kt` | CameraX UseCase 绑定 |

### 技术文档索引

| 文档 | 路径 | 内容 |
|------|------|------|
| **相机预览技术规格** | `docs/CAMERA_PREVIEW_TECH_SPEC.md` | 坐标转换、Viewport 计算、十字星定位 |
| **大美丽技术规格** | `docs/BIG_BEAUTY_TECH_SPEC.md` | Shader 架构、多 Pass 渲染、性能优化 |
| **离屏渲染 ADR** | `docs/ADR-002-opengl-offscreen-unified-pipeline.md` | 拍照 GPU 化方案 |
| **引擎容灾降级** | `docs/BEAUTY_ENGINE_FALLBACK.md` | 自动回退策略 |
| **GPUPixel 移植计划** | `docs/BIG_BEAUTY_GPUPixel_PORTING_PLAN.md` | 风格特效移植路线 |

---

## 🔧 核心功能

### 1. OpenGL 黑屏诊断 (Black Screen Diagnosis)

**触发命令**：`diagnose-black-screen` 或 `排查黑屏`

**常见原因及排查步骤**：

#### Step 1: 检查 EGL 上下文状态

```kotlin
// 在 CameraPreviewRenderer.kt 中添加诊断日志
Log.d(TAG, "EGL Context Status:")
Log.d(TAG, "  - eglContext: ${eglContext != null}")
Log.d(TAG, "  - surfaceTexture: ${surfaceTexture != null}")
Log.d(TAG, "  - textureId: $textureId")
Log.d(TAG, "  - isRendering: $isRendering")

// 验证 EGL 上下文是否有效
val currentContext = EGL14.eglGetCurrentContext()
Log.d(TAG, "  - Current EGL Context: ${currentContext != EGL14.EGL_NO_CONTEXT}")
```

**预期输出**：
```
✅ eglContext: true
✅ surfaceTexture: true
✅ textureId: 1
✅ isRendering: true
✅ Current EGL Context: true
```

**如果任一为 false**：
- `eglContext == null` → EGL 初始化失败，检查 `EGLCore.init()`
- `surfaceTexture == null` → SurfaceTexture 创建失败，检查 `createExternalTexture()`
- `textureId == 0` → 纹理生成失败，检查 `glGenTextures` 返回值
- `isRendering == false` → 渲染线程未启动，检查 `startRendering()`

#### Step 2: 检查 Shader 编译状态

```kotlin
// 在 BeautyRenderer.kt 中添加 Shader 编译检查
private fun checkShaderCompilation(programId: Int, shaderType: String): Boolean {
    val compileStatus = IntArray(1)
    GLES20.glGetShaderiv(programId, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
    
    if (compileStatus[0] == 0) {
        val infoLen = IntArray(1)
        GLES20.glGetShaderiv(programId, GLES20.GL_INFO_LOG_LENGTH, infoLen, 0)
        val infoLog = CharArray(infoLen[0])
        GLES20.glGetShaderInfoLog(programId, infoLen[0], null, infoLog)
        Log.e(TAG, "$shaderType 编译失败: ${String(infoLog)}")
        return false
    }
    
    Log.d(TAG, "$shaderType 编译成功")
    return true
}
```

**常见 Shader 编译错误**：
```glsl
❌ 错误 1: Uniform 声明但未使用
uniform float uUnused;  // 编译器会优化掉，导致 glGetUniformLocation 返回 -1

❌ 错误 2: 精度限定符缺失（ES 2.0）
float value = 1.0;  // ❌ 应改为 mediump float value = 1.0;

❌ 错误 3: 纹理采样器类型不匹配
uniform sampler2D uTexture;  // 但绑定的是 GL_TEXTURE_EXTERNAL_OES
// 应改为 uniform samplerExternalOES uTexture;
```

#### Step 3: 检查纹理绑定

```kotlin
// 验证纹理是否正确绑定
GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

val error = GLES20.glGetError()
if (error != GLES20.GL_NO_ERROR) {
    Log.e(TAG, "纹理绑定失败: glGetError() = 0x${error.toString(16)}")
} else {
    Log.d(TAG, "纹理绑定成功: textureId=$textureId")
}
```

**常见纹理错误**：
- `GL_INVALID_ENUM` → 纹理类型错误（应使用 `GL_TEXTURE_EXTERNAL_OES`）
- `GL_INVALID_VALUE` → textureId 无效（未生成或已删除）
- `GL_INVALID_OPERATION` → 上下文不正确

#### Step 4: 检查 FBO 状态

```kotlin
// 在渲染前检查 FBO 完整性
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)

if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
    Log.e(TAG, "FBO 不完整: status=0x${status.toString(16)}")
    when (status) {
        GLES20.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> 
            Log.e(TAG, "  - 附件不完整")
        GLES20.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> 
            Log.e(TAG, "  - 缺少附件")
        GLES20.GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS -> 
            Log.e(TAG, "  - 附件尺寸不匹配")
        GLES20.GL_FRAMEBUFFER_UNSUPPORTED -> 
            Log.e(TAG, "  - 格式不支持")
    }
} else {
    Log.d(TAG, "FBO 完整")
}
```

#### Step 5: 检查 Viewport 设置

```kotlin
// 验证 Viewport 是否正确设置
GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
Log.d(TAG, "Viewport: [${viewportArray[0]}, ${viewportArray[1]}, ${viewportArray[2]}x${viewportArray[3]}]")

// 预期输出（1080x1920 竖屏）：
// Viewport: [0, 0, 1080, 1920]
```

**常见问题**：
- Viewport 为 `[0, 0, 0, 0]` → 未调用 `glViewport`
- Viewport 尺寸与屏幕不符 → 宽高比计算错误

---

### 2. Shader 调试工具集 (Shader Debug Toolkit)

**触发命令**：`debug-shader` 或 `调试 Shader`

#### 工具 1: 红色测试 Shader（验证渲染链路）

```glsl
// FRAGMENT_SHADER_DEBUG_RED
precision mediump float;
varying vec2 vTextureCoord;

void main() {
    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);  // 纯红色
}
```

**用途**：如果屏幕显示纯红色，说明渲染链路正常，问题在纹理采样或 Shader 逻辑。

#### 工具 2: 纹理 R 通道灰度显示

```glsl
// FRAGMENT_SHADER_DEBUG_TEXTURE_R
precision mediump float;
varying vec2 vTextureCoord;
uniform samplerExternalOES uTexture;

void main() {
    vec4 color = texture2D(uTexture, vTextureCoord);
    float gray = color.r;  // 仅显示 R 通道
    gl_FragColor = vec4(gray, gray, gray, 1.0);
}
```

**用途**：验证纹理是否正确加载，R 通道应有明暗变化。

#### 工具 3: UV 坐标可视化

```glsl
// FRAGMENT_SHADER_DEBUG_UV
precision mediump float;
varying vec2 vTextureCoord;

void main() {
    // R 通道 = U 坐标，G 通道 = V 坐标
    gl_FragColor = vec4(vTextureCoord.x, vTextureCoord.y, 0.0, 1.0);
}
```

**用途**：检查纹理坐标是否正确映射。预期效果：
- 左上角：黑色 (0, 0)
- 右下角：黄色 (1, 1)
- 渐变过渡平滑

#### 工具 4: Uniform 值打印

```kotlin
// 在 BeautyRenderer.kt 中添加 Uniform 验证
fun debugUniforms() {
    val smoothingLoc = shaderProgram.getUniformLocation("uSmoothing")
    val whiteningLoc = shaderProgram.getUniformLocation("uWhitening")
    
    if (smoothingLoc >= 0) {
        val values = FloatArray(1)
        GLES20.glGetUniformfv(shaderProgram.programId, smoothingLoc, values, 0)
        Log.d(TAG, "uSmoothing = ${values[0]}")
    }
    
    if (whiteningLoc >= 0) {
        val values = FloatArray(1)
        GLES20.glGetUniformfv(shaderProgram.programId, whiteningLoc, values, 0)
        Log.d(TAG, "uWhitening = ${values[0]}")
    }
}
```

---

### 3. 性能分析与优化 (Performance Profiling)

**触发命令**：`profile-performance` 或 `性能分析`

#### 指标 1: 渲染 FPS 监控

```kotlin
// 在 CameraPreviewRenderer.kt 中添加 FPS 统计
private var frameCount = 0L
private var lastFpsUpdateTime = 0L
private var currentFps = 0

fun updateFpsStats() {
    frameCount++
    val currentTime = System.currentTimeMillis()
    
    if (currentTime - lastFpsUpdateTime >= 1000) {
        currentFps = frameCount
        frameCount = 0
        lastFpsUpdateTime = currentTime
        
        Log.d(TAG, "FPS: $currentFps")
        
        // 更新性能统计
        latestPerfStats = latestPerfStats.copy(fps = currentFps)
    }
}
```

**性能标准**：
- ✅ **优秀**: ≥ 55 fps
- ⚠️ **合格**: 45-54 fps
- ❌ **不合格**: < 45 fps

#### 指标 2: 单帧渲染耗时

```kotlin
// 在 BeautyRenderer.onDrawFrame() 中添加耗时统计
fun onDrawFrame() {
    val startTime = System.nanoTime()
    
    // ... 渲染逻辑 ...
    
    val endTime = System.nanoTime()
    val renderTimeMs = (endTime - startTime) / 1_000_000.0
    
    latestPerfStats = latestPerfStats.copy(
        renderTimeMs = renderTimeMs.toFloat()
    )
    
    if (renderTimeMs > 16.67) {  // 超过 60fps 预算
        Log.w(TAG, "渲染超时: ${renderTimeMs}ms (>16.67ms)")
    }
}
```

**耗时分解**：
```
总耗时 12ms:
├─ Shader 执行: 3ms (25%)
├─ 纹理上传: 2ms (17%)
├─ FBO 切换: 1ms (8%)
├─ 人脸检测: 4ms (33%)
└─ 其他: 2ms (17%)
```

#### 指标 3: 空帧计数 (Null Frames)

```kotlin
// 统计 SurfaceTexture 无新帧的情况
var statsNullFrames = 0

if (!frameAvailable) {
    statsNullFrames++
    Log.w(TAG, "空帧 #$statsNullFrames: SurfaceTexture 无新数据")
} else {
    surfaceTexture?.updateTexImage()
    frameAvailable = false
}
```

**正常范围**：
- ✅ 空帧率 < 5%（偶尔发生）
- ⚠️ 空帧率 5-15%（需优化 CameraX 配置）
- ❌ 空帧率 > 15%（严重性能问题）

#### 优化技巧 1: FBO 复用

```kotlin
// ❌ 错误：每帧创建新 FBO
fun onDrawFrame() {
    val fboId = IntArray(1)
    GLES20.glGenFramebuffers(1, fboId, 0)  // 频繁分配/释放
    // ...
    GLES20.glDeleteFramebuffers(1, fboId, 0)
}

// ✅ 正确：初始化时创建，按需调整尺寸
private var fboId: Int = 0
private var fboWidth: Int = 0
private var fboHeight: Int = 0

fun ensureFBO(width: Int, height: Int) {
    if (fboId == 0 || fboWidth != width || fboHeight != height) {
        // 释放旧 FBO
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
        }
        
        // 创建新 FBO
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]
        
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        fboTextureId = textures[0]
        
        // 绑定纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 
                           width, height, 0, GLES20.GL_RGBA, 
                           GLES20.GL_UNSIGNED_BYTE, null)
        
        // 绑定 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, 
                                     GLES20.GL_COLOR_ATTACHMENT0,
                                     GLES20.GL_TEXTURE_2D, fboTextureId, 0)
        
        fboWidth = width
        fboHeight = height
        
        Log.d(TAG, "FBO 创建: ${width}x${height}")
    }
}
```

#### 优化技巧 2: PBO 异步读取

```kotlin
// OffscreenRenderer.kt 中的 PBO 实现
private fun readPixelsWithPBO(width: Int, height: Int): Bitmap {
    val pixelCount = width * height
    val bufferSize = pixelCount * 4  // RGBA
    
    // 初始化 PBO 双缓冲
    if (pboIds == null) {
        val pbos = IntArray(PBO_COUNT)
        GLES20.glGenBuffers(PBO_COUNT, pbos, 0)
        pboIds = pbos
        
        for (i in 0 until PBO_COUNT) {
            GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, pbos[i])
            GLES20.glBufferData(GLES20.GL_PIXEL_PACK_BUFFER, bufferSize, 
                               null, GLES20.GL_DYNAMIC_READ)
        }
        GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, 0)
    }
    
    // 异步读取（当前帧）
    val readPboIndex = pboIndex
    val nextPboIndex = (pboIndex + 1) % PBO_COUNT
    
    GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, pboIds!![readPboIndex])
    GLES20.glReadPixels(0, 0, width, height, 
                       GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
    
    // 上一帧数据已就绪，映射到 CPU
    GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, pboIds!![nextPboIndex])
    val buffer = GLES20.glMapBufferRange(
        GLES20.GL_PIXEL_PACK_BUFFER,
        0, bufferSize,
        GLES20.GL_MAP_READ_BIT
    ) as ByteBuffer
    
    // 创建 Bitmap
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    buffer.rewind()
    bitmap.copyPixelsFromBuffer(buffer)
    
    GLES20.glUnmapBuffer(GLES20.GL_PIXEL_PACK_BUFFER)
    GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, 0)
    
    pboIndex = nextPboIndex
    return bitmap
}
```

**性能提升**：
- 直接 `glReadPixels`: ~50ms（同步阻塞）
- PBO 异步读取: ~15ms（重叠执行）

---

### 4. CameraX 集成调试 (CameraX Integration Debug)

**触发命令**：`debug-camerax` 或 `调试 CameraX`

#### 问题 1: Preview Surface 绑定失败

**症状**：
```
E/PicMe:CameraPreview: Surface not ready after 120 attempts
```

**排查步骤**：

```kotlin
// 1. 检查 BeautyPreviewView 初始化顺序
fun initializeCamera() {
    // ✅ 正确：先初始化 Renderer，再获取 Surface
    beautyPreviewView.initialize()  // 触发 EGL 初始化和 SurfaceTexture 创建
    
    // 等待 Surface 就绪
    repeat(120) { attempt ->
        val surface = beautyPreviewView.getSurfaceForCamera()
        if (surface != null && surface.isValid) {
            Log.i(TAG, "Surface ready on attempt ${attempt + 1}")
            bindCamera(surface)
            return
        }
        Thread.sleep(30)
    }
    
    throw IllegalStateException("Surface not ready")
}

// 2. 检查 EGL 上下文是否在正确的线程创建
class CameraPreviewRenderer(context: Context) {
    private val eglCore = EGLCore()
    
    fun init(view: View) {
        // ✅ 必须在渲染线程中初始化
        if (!eglCore.init()) {
            throw RuntimeException("EGL init failed")
        }
        
        // 创建 Pbuffer Surface 用于离线纹理生成
        val pbufferSurface = eglCore.createSurface(null, 1, 1)
        eglCore.makeCurrent(pbufferSurface, eglContext!!)
        
        // 创建外部纹理
        createExternalTexture()
        
        eglCore.clearCurrent()
    }
}
```

#### 问题 2: ImageAnalysis YUV 数据流中断

**症状**：
```
W/PicMe:Camera: ImageProxy closed before processing
```

**解决方案**：

```kotlin
// CameraUseCasesBinder.kt
imageAnalysis.setAnalyzer(Dispatchers.IO.asExecutor()) { imageProxy ->
    try {
        // ✅ 立即提取数据，避免 ImageProxy 过早关闭
        val mediaImage = imageProxy.image ?: return@setAnalyzer
        
        // 转换为 GPUPixel 所需格式
        val buffers = GPUPixel.YUV_420_888toI420AndRGBA(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        
        if (buffers != null) {
            gpupixelProvider.onYuvFrame(
                buffers[0],  // Y
                buffers[1],  // U
                buffers[2],  // V
                rotatedWidth,
                rotatedHeight,
                0,
                buffers[3]   // RGBA for face detection
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "YUV conversion error", e)
    } finally {
        // ✅ 确保释放
        imageProxy.close()
    }
}
```

#### 问题 3: 前后置摄像头切换卡顿

**优化方案**：

```kotlin
// 预初始化双摄像头 Surface
class DualCameraManager {
    private var frontSurface: Surface? = null
    private var backSurface: Surface? = null
    
    fun switchCamera(lensFacing: Int) {
        val targetSurface = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            frontSurface ?: createSurfaceForLens(lensFacing).also { 
                frontSurface = it 
            }
        } else {
            backSurface ?: createSurfaceForLens(lensFacing).also { 
                backSurface = it 
            }
        }
        
        // 快速切换，无需重新初始化
        cameraControl.unbindAll()
        bindCamera(targetSurface!!)
    }
}
```

---

### 5. 人脸关键点坐标映射 (Landmark Coordinate Mapping)

**触发命令**：`debug-landmarks` 或 `调试人脸关键点`

#### 坐标系转换流程

**重要说明**：本文档中所有"左/右"描述均基于**图像坐标系**（观察者视角），详见 [COORDINATE_SYSTEM_STANDARD.md](../../docs/COORDINATE_SYSTEM_STANDARD.md)。

```
MediaPipe 468 点 (3D NDC)
    ↓ 468→106 语义映射
InsightFace/GPUPixel 106 点 (2D 图像坐标)
    ↓ 旋转校正 (rotationDegrees)
旋转后图像坐标
    ↓ 归一化 (0.0~1.0)
归一化坐标
    ↓ 镜像翻转 (前置摄像头)
镜像后归一化坐标
    ↓ Viewport 映射
屏幕像素坐标
    ↓ UV 映射
OpenGL 纹理坐标
```

**关键理解**：
- **图像左侧** = 观察者看到的左边 = x 坐标较小的一侧
- **图像右侧** = 观察者看到的右边 = x 坐标较大的一侧
- 前置摄像头镜像后：图像左侧对应被拍摄者的右脸，图像右侧对应被拍摄者的左脸

#### 关键函数解析

```kotlin
// Stage 1: 图像坐标 → 归一化坐标
fun normalizeLandmark(
    x: Float, y: Float,
    imageWidth: Int, imageHeight: Int
): Pair<Float, Float> {
    val normX = x / imageWidth
    val normY = y / imageHeight
    return Pair(normX, normY)
}

// Stage 2: 旋转校正
fun rotateLandmark(
    normX: Float, normY: Float,
    rotationDegrees: Int,
    imageWidth: Int, imageHeight: Int
): Pair<Float, Float> {
    val rotatedWidth = if (rotationDegrees % 180 == 0) imageWidth else imageHeight
    val rotatedHeight = if (rotationDegrees % 180 == 0) imageHeight else imageWidth
    
    return when (rotationDegrees) {
        90 -> Pair(normY, 1f - normX)
        180 -> Pair(1f - normX, 1f - normY)
        270 -> Pair(1f - normY, normX)
        else -> Pair(normX, normY)
    }
}

// Stage 3: 镜像翻转（前置摄像头）
// 注意：镜像后，图像左侧的点会变成被拍摄者右脸的点
fun mirrorLandmark(
    normX: Float, normY: Float,
    lensFacing: Int
): Pair<Float, Float> {
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        // 前置摄像头：x 轴翻转
        // 原本在图像左侧的点（x 小）会翻转到图像右侧（x 大）
        1f - normX
    } else {
        normX
    }
    return Pair(mirroredX, normY)
}

// Stage 4: 屏幕坐标映射
fun toScreenCoordinate(
    normX: Float, normY: Float,
    previewWidth: Int, previewHeight: Int
): Offset {
    val screenX = normX * previewWidth
    val screenY = normY * previewHeight
    return Offset(screenX, screenY)
}

// Stage 5: UV 坐标映射（考虑 Viewport）
fun toUVCoordinate(
    normX: Float, normY: Float,
    outputWidth: Int, outputHeight: Int,
    cameraInputWidth: Int, cameraInputHeight: Int,
    isFillCenter: Boolean
): Pair<Float, Float> {
    val rawSourceAspect = cameraInputWidth.toFloat() / cameraInputHeight
    val rotatedSourceAspect = if (isFillCenter) rawSourceAspect else 1f / rawSourceAspect
    val outputAspect = outputWidth.toFloat() / outputHeight
    
    val uvX: Float
    val uvY: Float
    
    if (isFillCenter) {
        if (rotatedSourceAspect > outputAspect) {
            // 宽度填满，高度裁剪
            val scale = outputAspect / rotatedSourceAspect
            uvX = normX
            uvY = (normY - 0.5f) / scale + 0.5f
        } else {
            // 高度填满，宽度裁剪
            val scale = rotatedSourceAspect / outputAspect
            uvX = (normX - 0.5f) / scale + 0.5f
            uvY = normY
        }
    } else {
        // FIT_CENTER：保持比例，可能有黑边
        uvX = normX
        uvY = normY
    }
    
    return Pair(uvX.coerceIn(0f, 1f), uvY.coerceIn(0f, 1f))
}
```

#### 调试可视化

```kotlin
// 在 CameraDebugOverlay.kt 中添加关键点绘制
@Composable
fun LandmarkDebugOverlay(
    landmarks: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // 绘制 106 个点
        landmarks.forEachIndexed { index, (x, y) ->
            val color = when {
                index < 33 -> Color.Red      // 轮廓
                index < 43 -> Color.Green    // 左眉
                index < 53 -> Color.Blue     // 右眉
                index < 61 -> Color.Yellow   // 左眼
                index < 69 -> Color.Cyan     // 右眼
                index < 77 -> Color.Magenta  // 鼻子
                else -> Color.White          // 嘴巴
            }
            
            drawCircle(
                color = color,
                radius = 3.dp.toPx(),
                center = Offset(x, y)
            )
            
            // 绘制索引号（仅关键点）
            if (index % 10 == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "$index", x + 5, y - 5, Paint().apply {
                        textSize = 20f
                        color = android.graphics.Color.WHITE
                    }
                )
            }
        }
    }
}
```

---

## 📋 执行检查清单

### OpenGL 渲染问题排查清单

- [ ] EGL 上下文是否成功创建？
- [ ] SurfaceTexture 是否有效？
- [ ] 纹理 ID 是否非零？
- [ ] Shader 编译是否成功？（检查 `glGetShaderInfoLog`）
- [ ] Shader 链接是否成功？（检查 `glGetProgramInfoLog`）
- [ ] Uniform 位置是否有效？（`>= 0`）
- [ ] 纹理是否正确绑定？（`GL_TEXTURE_EXTERNAL_OES`）
- [ ] FBO 是否完整？（`glCheckFramebufferStatus`）
- [ ] Viewport 是否正确设置？
- [ ] 是否有 `glGetError()` 报错？

### 性能优化检查清单

- [ ] FBO 是否复用（避免每帧创建/销毁）？
- [ ] 是否使用 PBO 异步读取（拍照路径）？
- [ ] 纹理是否及时释放（`glDeleteTextures`）？
- [ ] Shader 是否预编译（避免运行时编译）？
- [ ] Uniform 更新是否批量进行？
- [ ] 渲染线程优先级是否设置为 `MAX_PRIORITY`？
- [ ] 是否避免在渲染线程执行耗时操作（文件 IO、网络请求）？
- [ ] FPS 是否稳定在 55+？
- [ ] 单帧渲染耗时是否 < 16.67ms？
- [ ] 空帧率是否 < 5%？

### CameraX 集成检查清单

- [ ] Preview Surface 是否在渲染线程初始化？
- [ ] ImageAnalysis 是否在后台线程执行？
- [ ] YUV→RGBA 转换是否高效（Native 实现）？
- [ ] ImageProxy 是否及时关闭？
- [ ] 前后置摄像头切换是否流畅（< 200ms）？
- [ ] 画幅切换（4:3 ↔ 16:9）是否无闪烁？
- [ ] 旋转角度是否正确处理（0°/90°/180°/270°）？

### 人脸关键点映射检查清单

- [ ] 468→106 映射表是否正确？
- [ ] 旋转校正是否应用？
- [ ] 前置摄像头是否镜像翻转？
- [ ] 归一化坐标范围是否为 [0.0, 1.0]？
- [ ] Viewport 计算是否考虑画幅比例？
- [ ] UV 映射是否与 Shader 期望一致？
- [ ] 调试浮层是否显示检测来源（MediaPipe/InsightFace/GPUPixel）？

---

## 🚨 常见问题与解决方案

### Q1: Shader 编译成功但画面全黑

**A**: 
1. 检查纹理是否正确绑定：
   ```kotlin
   GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
   GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
   ```
2. 检查 Uniform 是否传递：
   ```kotlin
   val loc = shaderProgram.getUniformLocation("uTexture")
   GLES20.glUniform1i(loc, 0)  // 对应 TEXTURE0
   ```
3. 使用红色测试 Shader 验证渲染链路

### Q2: 画面倒置或左右翻转

**A**:
1. 检查纹理坐标是否需要翻转：
   ```glsl
   // Y 轴翻转
   vec2 flippedUV = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
   ```
2. 检查前置摄像头镜像：
   ```kotlin
   val mirroredX = if (lensFacing == FRONT) 1f - normX else normX
   ```
3. 检查旋转角度是否正确应用

### Q3: EGL 上下文丢失导致崩溃

**A**:
1. 检查是否在正确的线程使用上下文：
   ```kotlin
   eglCore.makeCurrent(windowSurface, eglContext!!)
   ```
2. 检查 Surface 是否有效：
   ```kotlin
   if (!surface.isValid) {
       Log.w(TAG, "Surface invalid, skip rendering")
       return
   }
   ```
3. 实现上下文恢复机制：
   ```kotlin
   if (eglContextLost) {
       eglCore.release()
       eglCore.init()
       recreateTextures()
   }
   ```

### Q4: 拍照后处理慢（> 1s）

**A**:
1. 启用 PBO 异步读取
2. 减小处理分辨率（先缩放再处理）
3. 使用 GPU 离屏渲染（而非 CPU Canvas）
4. 复用 FBO 和纹理资源

### Q5: 人脸关键点偏移

**A**: 
1. 检查坐标系是否统一使用**图像坐标系**（详见 [COORDINATE_SYSTEM_STANDARD.md](../../docs/COORDINATE_SYSTEM_STANDARD.md)）
2. 检查 468→106 映射表是否正确（参考 [INSIGHTFACE_106_MAPPING.md](../../docs/face-detection/INSIGHTFACE_106_MAPPING.md)）
3. 检查旋转校正是否应用：
   ```kotlin
   // 根据 rotationDegrees 调整坐标
   val rotatedX = when (rotationDegrees) {
       90 -> normY
       180 -> 1f - normX
       270 -> 1f - normY
       else -> normX
   }
   ```
4. 检查前置摄像头是否镜像翻转：
   ```kotlin
   // 前置摄像头需要 x 轴翻转
   val finalX = if (lensFacing == FRONT) 1f - rotatedX else rotatedX
   ```
5. 检查 Viewport 计算是否考虑画幅比例
6. 启用调试浮层验证关键点位置

---

## 📚 参考文档

### 内部文档
- [CAMERA_PREVIEW_TECH_SPEC.md](../CAMERA_PREVIEW_TECH_SPEC.md) - 相机预览技术规格
- [BIG_BEAUTY_TECH_SPEC.md](../BIG_BEAUTY_TECH_SPEC.md) - 大美丽引擎技术规格
- [ADR-002-opengl-offscreen-unified-pipeline.md](../ADR-002-opengl-offscreen-unified-pipeline.md) - 离屏渲染架构决策
- [BEAUTY_ENGINE_FALLBACK.md](../BEAUTY_ENGINE_FALLBACK.md) - 引擎容灾降级策略

### 外部资源
- [OpenGL ES 2.0 Reference](https://www.khronos.org/opengles/sdk/docs/man/)
- [Android CameraX Guide](https://developer.android.com/training/camerax)
- [EGL 1.4 Specification](https://www.khronos.org/registry/EGL/specs/eglspec.1.4.pdf)
- [GLSL ES 1.0 Spec](https://www.khronos.org/files/opengles_shading_language.pdf)

---

## 🎓 最佳实践

### 1. 渲染线程管理

```kotlin
// ✅ 推荐：专用渲染线程，最高优先级
renderThread = Thread {
    while (isRendering) {
        try {
            renderFrame()
        } catch (e: Exception) {
            Log.e(TAG, "Render error", e)
        }
    }
}.apply {
    name = "CameraPreviewRender"
    priority = Thread.MAX_PRIORITY  // 减少调度延迟
    start()
}
```

### 2. 错误处理

```kotlin
// ✅ 推荐：每步检查 GL 错误
fun checkGLError(operation: String) {
    var error: Int
    while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
        Log.e(TAG, "GL Error after $operation: 0x${error.toString(16)}")
    }
}

// 使用
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
checkGLError("glBindFramebuffer")
```

### 3. 资源清理

```kotlin
// ✅ 推荐：按相反顺序释放资源
fun release() {
    // 1. 停止渲染线程
    isRendering = false
    renderThread?.join(300)
    
    // 2. 释放 FBO
    if (fboId != 0) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        fboId = 0
    }
    
    // 3. 释放纹理
    if (textureId != 0) {
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
    }
    
    // 4. 释放 Surface
    surfaceTexture?.release()
    surfaceTexture = null
    
    // 5. 释放 EGL
    eglCore.release()
}
```

### 4. 日志规范

```kotlin
// ✅ 推荐：结构化日志，便于过滤
companion object {
    private const val TAG = "PicMe:BeautyRenderer"
}

// 性能日志
Log.d(TAG, "perf: fps=$currentFps render_time=${renderTimeMs}ms null_frames=$statsNullFrames")

// 错误日志
Log.e(TAG, "shader_compile: failed - $errorLog")

// 状态变更
Log.i(TAG, "engine_switched: from=$oldEngine to=$newEngine")
```

---

**Skill 版本**: 1.0  
**创建日期**: 2026-05-03  
**维护者**: [RD] 全栈工程师 + [CR] 规范守护者  
**适用范围**: PicMe 项目音视频与 OpenGL 相关开发
