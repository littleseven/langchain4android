# R 计划：实时美颜完整指南

**版本**：3.0
**状态**：规划中（中长期自主方案）
**最后更新**：2026-03-29
**技术路线**：离屏渲染（Offscreen Rendering）+ 手动 EGL 管理
**实施策略**：双轨并行 - 短期 PixelFreeEffects，中长期 R 计划自主实现

---

## 0. 实施策略与时间线

### 0.1 双轨策略

```
短期（1-2 周）          中期（1-2 月）           中长期（2-3 月）
    ↓                      ↓                        ↓
PixelFreeEffects      同时运行              R 计划自主研发
SDK 接入            → 积累数据            → 完全替代 SDK
- 快速上线           - 性能监控            - 技术可控
- 验证产品           - Shader 优化         - 定制化能力
- 用户反馈           - 算法迭代            - 零授权成本
```

### 0.2 技术借鉴方向

R 计划将借鉴 PixelFreeEffects 的以下技术方案：

1. **渲染架构**
   - GLSurfaceView + Renderer 模式
   - 纹理处理流程（Texture Input → Process → Texture Output）
   - 参数调节接口设计（PFBeautyFilterType 枚举）

2. **美颜算法结构**
   - 磨皮：盒式模糊 + 亮度提升
   - 美白：RGB 通道调整
   - 瘦脸：Vertex Shader 形变

3. **性能优化**
   - 离屏渲染（Pbuffer Surface）
   - EGL 上下文共享
   - 渲染线程优先级（MAX_PRIORITY）

### 0.3 当前实施方案

**⚠️ 注意**：当前阶段优先使用 **PixelFreeEffects SDK** 实现产品功能。

详见：`docs/PIXELFREE_INTEGRATION.md`

---

## 1. 第一性原理分析

### 1.1 问题本质
**目标**：在相机预览中实时显示美颜效果（磨皮、美白等）

**核心挑战**：
- 相机输出的原始帧 → 应用美颜算法 → 显示处理后的帧
- 整个过程必须在 **16ms 内完成**（60fps）
- 不能有可见的延迟或卡顿

### 1.2 技术本质
实时美颜预览的本质是**GPU 加速的图像流处理管道**：

```
相机传感器 → YUV 数据 → GPU 纹理 → Shader 处理 → RGB 显示
           (CameraX)   (OpenGL)  (GLSL)    (Surface)
```

**关键点**：
1. **数据流**：相机帧必须以纹理形式传递给 GPU
2. **处理流**：使用 GLSL Shader 在 GPU 上并行处理每个像素
3. **显示流**：处理后的纹理必须直接输出到屏幕

### 1.3 三大技术壁垒

1. **EGL 上下文管理**
   - OpenGL ES 需要在正确的 EGL 上下文中操作
   - 离屏渲染（Offscreen Rendering）需要手动管理多个 EGL 上下文
   - 上下文共享（Context Sharing）是性能关键

2. **SurfaceTexture 同步**
   - SurfaceTexture 是 CameraX 和 OpenGL 的桥梁
   - 必须在正确的线程、正确的上下文中更新
   - `updateTexImage()` 只能在绑定该 SurfaceTexture 的线程调用

3. **渲染管线集成**
   - CameraX 的 Preview 需要 Surface 作为输出
   - OpenGL 渲染需要 WindowSurface 作为目标
   - 两者必须共享同一个 EGL 上下文和纹理

---

## 2. 技术架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                    │
│  ┌─────────────────────────────────────────────────┐    │
│  │          BeautyPreviewView (自定义 View)          │    │
│  │  ┌─────────────────────────────────────────┐    │    │
│  │  │   TextureView (显示最终渲染结果)          │    │    │
│  │  └─────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│              Rendering Layer (OpenGL ES)                 │
│  ┌─────────────────────────────────────────────────┐    │
│  │   CameraPreviewRenderer (渲染管线核心)            │    │
│  │   ├─ EGLCore (EGL 管理)                          │    │
│  │   ├─ BeautyRenderer (美颜渲染器)                 │    │
│  │   ├─ ShaderProgram (Shader 管理)                 │    │
│  │   └─ WindowSurface (渲染目标)                    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│              Camera Layer (CameraX)                      │
│  ┌─────────────────────────────────────────────────┐    │
│  │   Preview UseCase                               │    │
│  │   └─ SurfaceProvider → Surface → SurfaceTexture │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

### 2.2 核心组件职责

#### BeautyPreviewView
**职责**：封装 TextureView 和渲染管线，提供简单 API

```kotlin
class BeautyPreviewView : FrameLayout {
    var smoothingStrength: Float  // 磨皮强度
    var whiteningStrength: Float  // 美白强度

    fun getSurfaceForCamera(): Surface?  // 供 CameraX 使用
    fun getSurfaceTexture(): SurfaceTexture?  // 获取 SurfaceTexture
}
```

**关键原则**：
- **禁止**在构造函数中启动渲染，必须等待 CameraX 请求 Surface
- 使用 `surfaceCreated` 标志防止重复创建

#### CameraPreviewRenderer
**职责**：管理完整的渲染管线

```kotlin
fun init(view: View) {
    // 1. 初始化 EGL
    eglCore.init()
    eglContext = eglCore.createContext()

    // 2. 创建外部纹理（用于接收相机帧）
    createExternalTexture()

    // 3. 创建 SurfaceTexture（绑定到外部纹理）
    surfaceTexture = SurfaceTexture(textureId)

    // 4. 初始化 BeautyRenderer（离屏渲染）
    val pbufferSurface = eglCore.createSurface(null, 1, 1)
    eglCore.makeCurrent(pbufferSurface, eglContext!!)
    beautyRenderer.onInit()
}

fun setRenderSurface(surface: Surface) {
    // 创建 WindowSurface（用于显示）
    windowSurface = WindowSurface(surface, eglCore)

    // 启动渲染线程
    startRendering()
}
```

**关键原则**：
- **禁止**在 `init()` 中启动渲染线程，必须等待 `setRenderSurface()` 调用
- 使用 `eglContext` 字段保存共享上下文

#### BeautyRenderer
**职责**：执行美颜渲染

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uTexture;  // 相机纹理
uniform float uSmoothing;              // 磨皮强度
uniform float uWhitening;              // 美白强度
varying vec2 vTextureCoord;

void main() {
    // 1. 从外部纹理采样
    vec4 color = texture2D(uTexture, vTextureCoord);

    // 2. 磨皮处理（盒式模糊）
    vec4 smoothed = smoothSkin(vTextureCoord, uSmoothing);

    // 3. 美白处理（RGB 亮度提升）
    vec4 whitened = whitenSkin(smoothed, uWhitening);

    gl_FragColor = whitened;
}
```

**关键原则**：
- **Shader 要求**：使用盒式模糊（性能优化），禁止使用复杂的双边模糊
- 支持实时参数调整：`updateBeautyParams(smoothing, whitening)`

---

## 3. 核心技术难点与解决方案

### 3.1 难点 1：EGL 上下文管理

**解决方案**：EGL 上下文共享

```kotlin
// 1. 创建共享上下文
eglContext = eglCore.createContext()

// 2. 离屏渲染上下文（用于编译 Shader、初始化资源）
val pbufferSurface = eglCore.createSurface(null, 1, 1)
eglCore.makeCurrent(pbufferSurface, eglContext!!)
beautyRenderer.onInit()  // 初始化 Shader

// 3. 渲染线程中的上下文（用于实际渲染）
val renderContext = eglCore.createContext()
eglCore.makeCurrent(windowSurface.getEglSurface(), renderContext)
```

**关键点**：
- 所有上下文共享纹理和资源
- **禁止**：在多个线程中同时调用 `eglMakeCurrent`

### 3.2 难点 2：SurfaceTexture 生命周期

**解决方案**：延迟初始化策略

```kotlin
// BeautyPreviewView 中
private var surfaceCreated = false

fun getSurfaceForCamera(): Surface? {
    val surfaceTexture = renderer.getSurfaceTexture()
    if (surfaceTexture == null) return null

    // 第一次调用时才创建 Surface 并启动渲染
    if (!surfaceCreated) {
        surfaceCreated = true
        surfaceTexture.setDefaultBufferSize(1920, 1080)  // 关键！
        renderer.setRenderSurface(Surface(surfaceTexture))
    }

    return Surface(surfaceTexture)
}
```

**关键原则**：
- **延迟初始化**：CameraX 请求时才启动渲染
- **缓冲区大小**：必须调用 `setDefaultBufferSize()`
- **单次创建**：使用 `surfaceCreated` 标志防止重复创建

### 3.3 难点 3：渲染线程同步

**解决方案**：基于 SurfaceTexture 的帧可用监听 + 错误恢复

```kotlin
private fun startRendering() {
    renderThread = Thread {
        var frameCount = 0
        while (isRendering && !Thread.interrupted()) {
            try {
                // 1. 更新 SurfaceTexture（从相机获取帧）
                surfaceTexture?.updateTexImage()

                // 2. 获取变换矩阵
                val transformMatrix = FloatArray(16)
                surfaceTexture?.getTransformMatrix(transformMatrix)

                // 3. 绑定外部纹理
                GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)

                // 4. 渲染
                beautyRenderer.setTextureTransform(transformMatrix)
                beautyRenderer.onRender()

                // 5. 交换缓冲区
                windowSurface?.swapBuffers()

                frameCount++
                if (frameCount % 30 == 0) {
                    Log.d(TAG, "Rendered $frameCount frames")
                }

                Thread.sleep(16)  // ~60fps

            } catch (e: IllegalStateException) {
                // 错误恢复：SurfaceTexture 未就绪
                Log.e(TAG, "Render error: ${e.message}")
                Thread.sleep(100)
            }
        }
    }.apply {
        name = "CameraPreviewRender"
        priority = Thread.MAX_PRIORITY
        start()
    }
}
```

**关键原则**：
- **错误恢复**：捕获 `IllegalStateException` 并重试
- **帧率控制**：`Thread.sleep(16)` 锁定 60fps
- **日志记录**：每 30 帧记录一次，避免日志泛滥

---

## 4. 完整实现流程

### 4.1 初始化流程

```
1. BeautyPreviewView 创建
   ↓
2. onSurfaceTextureAvailable 回调
   ↓
3. CameraPreviewRenderer.init()
   ├─ 初始化 EGL
   ├─ 创建外部纹理 (ID=1)
   ├─ 创建 SurfaceTexture (绑定纹理 ID=1)
   └─ 初始化 BeautyRenderer (离屏)
   ↓
4. 等待 CameraX 请求 Surface
   ↓
5. getSurfaceForCamera() 被调用
   ├─ 创建 WindowSurface
   └─ 启动渲染线程
   ↓
6. CameraX 绑定到 Surface
   ↓
7. 相机开始输出帧
   ↓
8. SurfaceTexture 接收帧
   ↓
9. 渲染线程更新纹理并渲染
   ↓
10. TextureView 显示结果
```

### 4.2 渲染流程（每帧）

```
1. 相机输出 YUV 帧
   ↓
2. CameraX 写入 Surface
   ↓
3. SurfaceTexture 更新（内部完成 YUV→RGB 转换）
   ↓
4. 触发 OnFrameAvailableListener
   ↓
5. 渲染线程唤醒
   ↓
6. updateTexImage() 更新纹理
   ↓
7. 获取纹理变换矩阵
   ↓
8. 绑定外部纹理到 GL_TEXTURE0
   ↓
9. 使用美颜 Shader 渲染
   ├─ 顶点 Shader：变换坐标
   └─ 片段 Shader：应用美颜
   ↓
10. 渲染到 WindowSurface
   ↓
11. swapBuffers() 交换缓冲区
   ↓
12. TextureView 显示到屏幕
```

---

## 5. 调试检查清单

### 5.1 启动阶段
- [ ] `onSurfaceTextureAvailable` 被调用
- [ ] `renderer.init()` 成功
- [ ] 外部纹理 ID 创建（非 0）
- [ ] SurfaceTexture 创建成功

### 5.2 CameraX 绑定
- [ ] `getSurfaceForCamera()` 被调用
- [ ] 返回的 Surface 非 null
- [ ] `setSurfaceProvider` 被调用
- [ ] SurfaceProvider 的回调执行
- [ ] 有 "Camera bound" 日志

### 5.3 渲染阶段
- [ ] 渲染线程启动
- [ ] `updateTexImage()` 成功
- [ ] 有 "New frame available" 回调
- [ ] 渲染每 16ms 执行一次
- [ ] TextureView 显示内容

### 5.4 关键日志标签

```
D/PicMe:BeautyPreviewView: Surface texture available
D/PicMe:CameraPreview: External texture created: X (X != 0)
D/PicMe:CameraPreview: SurfaceTexture created with texture ID: X
D/PicMe:Camera: Creating Surface for CameraX
D/PicMe:Camera: SurfaceProvider called
D/PicMe:Camera: Camera bound
D/PicMe:CameraPreview: Render thread started
D/PicMe:CameraPreview: Rendered X frames
```

---

## 6. 常见问题与解决方案

### 问题 1：External texture created: 0
**原因**：纹理 ID 为 0，说明纹理创建失败
**解决**：检查 EGL 上下文是否正确初始化

### 问题 2：SurfaceTexture 为 null
**原因**：初始化顺序错误
**解决**：确保先保存 SurfaceTexture，再初始化 Renderer

### 问题 3：没有 "Camera bound" 日志
**原因**：CameraX 绑定失败
**解决**：检查 SurfaceProvider 是否正确配置，Surface 是否有效

### 问题 4：渲染线程启动但立即停止
**原因**：`updateTexImage()` 持续失败
**解决**：添加重试机制，检查相机是否开始输出帧

---

## 7. 降级策略

### 7.1 检测失败并降级

```kotlin
// 在 CameraScreen 中
var useFallbackPreview by remember { mutableStateOf(false) }

LaunchedEffect(Unit) {
    delay(5000)  // 等待 5 秒
    if (!isRenderingSuccessfully) {
        Log.e("PicMe:Camera", "R plan failed, switching to fallback")
        useFallbackPreview = true
    }
}

// 降级方案：使用普通 PreviewView（无美颜预览）
val previewView = if (useFallbackPreview) {
    PreviewView(context)
} else {
    beautyPreviewView
}
```

### 7.2 离线美颜实现

```kotlin
// 拍照时应用美颜
fun capturePhoto(beautySettings: BeautySettings) {
    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
        val bitmap = imageProxy.toBitmap()
        val processedBitmap = applyBeautyOnGPU(bitmap, beautySettings)
        saveImage(processedBitmap)
        imageProxy.close()
    }
}
```

---

## 8. 性能指标

### 8.1 必须达到的指标
- 启动时间：< 500ms（从打开相机到显示预览）
- 渲染延迟：< 16ms（60fps）
- 内存占用：< 50MB（额外）
- 纹理 ID：必须是非 0 值

### 8.2 监控方法

```kotlin
// 在渲染线程中
var lastFpsTime = System.currentTimeMillis()
var frameCount = 0

// 每帧计数
frameCount++
val currentTime = System.currentTimeMillis()
if (currentTime - lastFpsTime >= 1000) {
    Log.d(TAG, "FPS: $frameCount")
    frameCount = 0
    lastFpsTime = currentTime
}
```

---

## 9. 性能优化策略

### 9.1 内存优化
- **零拷贝**：SurfaceTexture 直接输出到 OpenGL 纹理
- **纹理复用**：只创建一个外部纹理，重复使用
- **离屏渲染**：使用 1x1 Pbuffer Surface，最小化内存占用

### 9.2 性能优化
- **渲染线程优先级**：`Thread.MAX_PRIORITY`
- **固定帧率**：`Thread.sleep(16)` 锁定 60fps
- **Shader 优化**：使用盒式模糊代替双边模糊（性能提升 10 倍）

### 9.3 延迟优化
- **及时启动**：CameraX 请求 Surface 时立即启动渲染
- **异步处理**：渲染在独立线程，不阻塞 UI
- **直接显示**：TextureView 硬件加速，无额外拷贝

---

## 10. 技术风险与应对

### 风险 1：设备兼容性
**风险**：不同厂商的 OpenGL ES 实现可能有差异
**应对**：最低支持 OpenGL ES 3.0，在低端设备上降级到离线美颜

### 风险 2：SurfaceTexture 失效
**风险**：`updateTexImage()` 可能因上下文不匹配而失败
**应对**：严格的生命周期管理 + 错误恢复机制 + 降级到 PreviewView

### 风险 3：性能瓶颈
**风险**：复杂 Shader 导致帧率下降
**应对**：使用简化的磨皮算法 + 支持性能模式（关闭美颜）

---

## 11. 实施计划

### Phase 1：基础架构（已完成）
- [x] EGLCore 实现
- [x] ShaderProgram 管理
- [x] BeautyRenderer 基础渲染
- [x] BeautyPreviewView 封装

### Phase 2：集成调试（进行中）
- [ ] CameraX 集成
- [ ] SurfaceTexture 同步
- [ ] 渲染线程优化
- [ ] 黑屏问题修复

### Phase 3：美颜算法
- [ ] 磨皮算法优化
- [ ] 美白算法优化
- [ ] 瘦脸算法集成
- [ ] 大眼算法集成

### Phase 4：性能优化
- [ ] 性能基准测试
- [ ] 内存优化
- [ ] 功耗优化
- [ ] 兼容性测试

---

## 12. 相关文档

- `PRODUCT.md` - 产品需求规格说明书
- `FEATURES.md` - 功能交互规范
- `AGENTS.md` - AI Agent 操作规范（包含 R 计划架构规范章节）
- `PIXELFREE_INTEGRATION.md` - 当前实施方案（PixelFreeEffects SDK 集成）
- `CAMERA_PREVIEW_GUIDE.md` - 相机预览完整指南

---

## 13. 总结

R 计划的核心是**构建一个高效的 GPU 加速图像流处理管道**，通过：

1. **EGL 上下文共享**实现离屏渲染和显示的统一
2. **SurfaceTexture**作为 CameraX 和 OpenGL 的桥梁
3. **GLSL Shader**实现实时美颜算法
4. **独立渲染线程**保证 60fps 流畅度

**关键成功因素**：
1. ✅ 正确的初始化顺序
2. ✅ 合适的 Surface 创建时机
3. ✅ EGL 上下文的正确管理
4. ✅ 渲染线程与相机帧的同步

**失败应对**：
- 准备降级方案（离线美颜）
- 充分的日志记录
- 快速失败和恢复机制

**预期结果**：
- 成功：实现 60fps 实时美颜预览
- 失败：降级到离线美颜，不影响拍照功能

这是一个技术难度极高但性能最优的方案，成功实施后将使 PicMe 的美颜功能达到业界领先水平。

