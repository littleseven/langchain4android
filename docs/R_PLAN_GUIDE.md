# R 计划：实时美颜完整指南

**版本**：4.0
**状态**：实施中（与 PixelFreeEffects 双引擎共存）
**最后更新**：2026-04
**技术路线**：自研 GPU 加速管线 + EGL 共享上下文 + SurfaceTexture 直通

---

## 0. 背景与目标

### 0.0 双引擎定位

- **主引擎（默认）**：R 计划
- **备用引擎**：PixelFreeEffects SDK
- **切换方式**：设置页「美颜引擎」配置开关
- **容灾策略**：R 计划初始化失败或运行异常时，自动回退 PixelFreeEffects

### 0.1 现状问题

- **性能不佳**：1080p 预览在开启美颜后明显卡顿，滑杆跟手性差
- **依赖不可控**：PixelFreeEffects SDK 占用额外内存与授权成本
- **调试困难**：问题定位需要 SDK 内部日志，排期不可控

### 0.2 目标（第一性原理）

从“用户体验”倒推技术要求：

1. **极致流畅**：预览帧率 ≥ 30fps，理想 60fps；单帧处理 ≤ 16ms
2. **零感延迟**：参数调节到画面变化的延迟 < 100ms（用户阈值）
3. **技术可控**：自研管线，快速迭代；零授权成本
4. **容错可用**：渲染失败自动降级为离线美颜，拍照功能不受影响

### 0.3 技术本质

实时美颜预览的本质是 **GPU 加速的图像流处理管道**：

```
相机传感器 → YUV 数据 → GPU 纹理 → Shader 处理 → RGB 显示
           (CameraX)   (OpenGL)  (GLSL)    (Surface)
```

关键约束：

- 数据流必须零拷贝（直接纹理传递）
- 处理流必须在 GPU（避免 CPU 瓶颈）
- 显示流必须直通（避免额外 Surface 切换）

---

## 1. 第一性原理拆解

### 1.1 为什么选择自研而非 SDK

| 维度 | PixelFreeEffects SDK | R 计划（自研） |
|------|----------------------|----------------|
| 性能调优 | 受限 SDK 内部实现 | 可针对性优化每一步 |
| 内存占用 | SDK + 资源 60MB+ | 目标 < 30MB |
| 延迟控制 | SDK 调度黑盒 | 可精准控制线程优先级 |
| 故障排查 | 依赖厂商支持 | 全链路日志可观测 |
| 授权成本 | 商业授权费用 | 零成本 |

### 1.2 为什么选择 OpenGL ES 而非 Vulkan

- **CameraX 兼容性**：CameraX Preview 默认输出 SurfaceTexture，天然对接 OpenGL ES
- **设备覆盖**：OpenGL ES 2.0 覆盖 99%+ Android 设备
- **开发周期**：Vulkan 学习曲线陡峭，2-3 周难以完成
- **后续优化**：可在 R 计划稳定后逐步迁移到 Vulkan

### 1.3 为什么必须 EGL 上下文共享

- **离屏初始化**：Shader 编译、资源加载需要 EGL 上下文
- **多线程渲染**：渲染线程需要独立的上下文，但共享纹理资源
- **CameraX 约束**：CameraX 的 SurfaceProvider 在主线程，渲染必须在独立线程

**错误做法**：在主线程做所有操作 → 主线程阻塞 → UI 卡顿

**正确做法**：

```
主线程：EGL 初始化 + SurfaceTexture 创建
   ↓
渲染线程：独立上下文（共享纹理） + 美颜渲染
```

---

## 2. 架构设计

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

### 2.2 数据流（零拷贝）

```
CameraX 预览帧
    ↓ (无拷贝)
SurfaceTexture.updateTexImage()
    ↓ (GPU 纹理)
OpenGL ES 外部纹理 (GL_TEXTURE_EXTERNAL_OES)
    ↓ (Shader 处理)
美颜后纹理
    ↓ (直接显示)
TextureView Surface
```

**关键优化点**：

- ❌ 避免从 GPU 读回 CPU（耗时 ~50ms）
- ❌ 避免多次纹理上传（内存带宽瓶颈）
- ✅ 全流程在 GPU 完成，零拷贝

### 2.3 核心组件职责

#### BeautyPreviewView

**职责**：封装 TextureView 和渲染管线，提供简洁 API

```kotlin
class BeautyPreviewView(
    context: Context,
    attributeSet: AttributeSet? = null
) : FrameLayout(context, attributeSet) {

    private val textureView: TextureView
    private val renderer: CameraPreviewRenderer

    // 美颜参数（响应式）
    var smoothingStrength: Float by mutableStateOf(0f)
    var whiteningStrength: Float by mutableStateOf(0f)

    // 供 CameraX 使用
    fun getSurfaceForCamera(): Surface?

    // 获取 SurfaceTexture（用于调试）
    fun getSurfaceTexture(): SurfaceTexture?
}
```

**关键约束**：

- **禁止**在构造函数中启动渲染
- 必须等待 `TextureView.SurfaceTextureListener.onSurfaceTextureAvailable`
- 使用 `surfaceCreated` 标志防止重复创建 Surface

#### CameraPreviewRenderer

**职责**：管理完整的渲染管线

```kotlin
class CameraPreviewRenderer {
    private val eglCore: EGLCore
    private var eglContext: EGLContext? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var windowSurface: WindowSurface? = null
    private var textureId: Int = 0

    private val beautyRenderer: BeautyRenderer

    // 初始化（在主线程调用）
    fun init(view: View) {
        // 1. 初始化 EGL
        eglCore.init()
        eglContext = eglCore.createContext()

        // 2. 创建外部纹理
        textureId = createExternalTexture()

        // 3. 创建 SurfaceTexture
        surfaceTexture = SurfaceTexture(textureId)

        // 4. 离屏初始化 BeautyRenderer
        val pbufferSurface = eglCore.createSurface(null, 1, 1)
        eglCore.makeCurrent(pbufferSurface, eglContext!!)
        beautyRenderer.onInit()
    }

    // 启动渲染（在 CameraX 请求 Surface 时调用）
    fun setRenderSurface(surface: Surface) {
        windowSurface = WindowSurface(surface, eglCore)
        startRendering()
    }
}
```

**关键约束**：

- `init()` 不启动渲染线程
- `setRenderSurface()` 才启动渲染
- 渲染线程使用独立的共享上下文

#### BeautyRenderer

**职责**：执行美颜渲染

**Shader 设计原则**：

- **性能优先**：使用盒式模糊（Box Blur），避免双边模糊
- **分步处理**：磨皮 → 美白 → 输出，每个步骤独立 Shader
- **参数实时更新**：通过 `uniform` 传递参数，无需重新编译

**磨皮 Shader（简化版）**：

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uTexture;
uniform float uSmoothing;  // 0.0 - 1.0
varying vec2 vTextureCoord;

void main() {
    vec4 color = texture2D(uTexture, vTextureCoord);

    if (uSmoothing > 0.0) {
        // 盒式模糊（3x3）
        vec4 sum = vec4(0.0);
        float kernel[9];
        kernel[0] = 1.0; kernel[1] = 2.0; kernel[2] = 1.0;
        kernel[3] = 2.0; kernel[4] = 4.0; kernel[5] = 2.0;
        kernel[6] = 1.0; kernel[7] = 2.0; kernel[8] = 1.0;

        float step = 0.003;  // 采样步长

        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                vec2 coord = vTextureCoord + vec2(float(i) * step, float(j) * step);
                sum += texture2D(uTexture, coord) * kernel[(i + 1) * 3 + (j + 1)];
            }
        }

        vec4 smoothed = sum / 16.0;

        // 混合原图和模糊图
        color = mix(color, smoothed, uSmoothing * 0.5);
    }

    gl_FragColor = color;
}
```

**美白 Shader**：

```glsl
precision mediump float;

uniform sampler2D uTexture;
uniform float uWhitening;  // 0.0 - 1.0
varying vec2 vTextureCoord;

void main() {
    vec4 color = texture2D(uTexture, vTextureCoord);

    if (uWhitening > 0.0) {
        // 亮度提升（保持色调）
        float brightness = 1.0 + uWhitening * 0.2;
        color.rgb = color.rgb * brightness;

        // 限制高光溢出
        color.rgb = min(color.rgb, vec3(1.0));
    }

    gl_FragColor = color;
}
```

---

## 3. 核心技术难点与解决方案

### 3.1 难点 1：EGL 上下文管理

**问题**：

- OpenGL ES 操作必须在 EGL 上下文中
- 主线程和渲染线程需要共享纹理资源
- 上下文创建/切换有严格顺序要求

**解决方案**：EGL 上下文共享

```kotlin
// 1. 主线程：创建共享上下文
val shareContext = eglCore.createContext()

// 2. 离屏初始化（Pbuffer Surface）
val pbufferSurface = eglCore.createSurface(null, 1, 1)
eglCore.makeCurrent(pbufferSurface, shareContext)
beautyRenderer.onInit()  // 编译 Shader

// 3. 渲染线程：创建共享上下文
val renderContext = eglCore.createContext(shareContext)
eglCore.makeCurrent(windowSurface.getEglSurface(), renderContext)
```

**关键约束**：

- 所有上下文必须通过 `eglCore.createContext()` 创建（自动共享）
- **禁止**在多个线程同时调用 `eglMakeCurrent`
- 渲染线程必须有自己的上下文

### 3.2 难点 2：SurfaceTexture 生命周期

**问题**：

- SurfaceTexture 绑定到特定线程
- `updateTexImage()` 只能在创建它的线程调用
- 相机帧和渲染帧需要同步

**解决方案**：延迟初始化 + 线程绑定

```kotlin
private var surfaceCreated = false
private var renderThread: Thread? = null

fun getSurfaceForCamera(): Surface? {
    val st = surfaceTexture ?: return null

    if (!surfaceCreated) {
        surfaceCreated = true
        st.setDefaultBufferSize(1920, 1080)  // 关键！

        // 启动渲染线程
        startRenderThread(st)
    }

    return Surface(st)
}

private fun startRenderThread(st: SurfaceTexture) {
    renderThread = Thread {
        // 线程绑定 SurfaceTexture
        st.setOnFrameAvailableListener { frameAvailable = true }

        while (isRendering) {
            if (frameAvailable) {
                st.updateTexImage()  // 必须在创建线程调用
                renderFrame()
                frameAvailable = false
            }
        }
    }.apply {
        name = "CameraPreviewRender"
        priority = Thread.MAX_PRIORITY
        start()
    }
}
```

**关键约束**：

- `setDefaultBufferSize()` 必须在 CameraX 绑定前调用
- `updateTexImage()` 只能在渲染线程调用
- 使用 `OnFrameAvailableListener` 唤醒渲染线程

### 3.3 难点 3：渲染线程同步

**问题**：

- 相机帧率可能 > 渲染帧率（丢帧）
- 渲染帧率可能 > 相机帧率（空帧）
- 参数变更需要线程安全传递

**解决方案**：帧可用监听 + 自适应帧率

```kotlin
private var frameAvailable = false
private val frameLock = Object()

// 渲染线程循环
while (isRendering) {
    synchronized(frameLock) {
        while (!frameAvailable) {
            frameLock.wait(50)  // 等待相机帧
        }
        frameAvailable = false
    }

    // 更新纹理
    surfaceTexture?.updateTexImage()

    // 渲染
    beautyRenderer.updateParams(smoothing, whitening)
    beautyRenderer.onRender()

    // 交换缓冲区
    windowSurface?.swapBuffers()
}

// SurfaceTexture 帧可用回调
surfaceTexture?.setOnFrameAvailableListener {
    synchronized(frameLock) {
        frameAvailable = true
        frameLock.notify()
    }
}
```

**关键约束**：

- 避免空转（没有帧时等待）
- 避免丢帧（帧可用时立即处理）
- 参数传递使用 `volatile` 或 `AtomicFloat`

---

## 4. 实施路线图

### Phase 1：基础架构（1 周）

**目标**：实现 CameraX → OpenGL → TextureView 的数据流

**任务**：

- [ ] EGLCore 实现（上下文管理、Surface 创建）
- [ ] ShaderProgram 实现（编译、链接、使用）
- [ ] BeautyRenderer 基础渲染（直通，无美颜）
- [ ] BeautyPreviewView 封装
- [ ] CameraX 集成测试

**验证**：

- 预览画面显示（无黑屏）
- FPS ≥ 30（无美颜）
- 内存占用 < 20MB

### Phase 2：美颜算法（1 周）

**目标**：实现磨皮、美白实时效果

**任务**：

- [ ] 磨皮 Shader 实现（盒式模糊）
- [ ] 美白 Shader 实现（亮度提升）
- [ ] 参数实时更新机制
- [ ] 性能优化（避免每帧重新编译）

**验证**：

- 参数调节实时生效
- 处理延迟 < 16ms（60fps）
- 视觉效果自然（不过度模糊）

### Phase 3：高级功能（1 周）

**目标**：实现瘦脸、大眼实时效果

**任务**：

- [ ] 瘦脸 Shader 实现（网格变形）
- [ ] 大眼 Shader 实现（局部放大）
- [ ] 人脸关键点传递（ML Kit → OpenGL）
- [ ] 性能测试与优化

**验证**：

- 瘦脸/大眼实时生效
- 人脸跟踪准确
- 无明显性能下降

### Phase 4：性能优化（1 周）

**目标**：达到性能指标，兼容性测试

**任务**：

- [ ] 帧率优化（目标 60fps）
- [ ] 内存优化（目标 < 30MB）
- [ ] 降级策略实现
- [ ] 兼容性测试（低端机型）

**验证**：

- 性能指标达标
- 降级策略生效
- 覆盖 95%+ 设备

---

## 5. 性能指标与监控

### 5.1 核心指标

| 指标 | 目标值 | 测量方法 |
|------|--------|----------|
| 预览帧率 | ≥ 30fps，理想 60fps | 每秒帧计数 |
| 处理延迟 | ≤ 16ms | 单帧处理耗时 |
| 参数响应延迟 | < 100ms | 参数变更到画面变化 |
| 内存占用 | < 30MB | Android Profiler |
| 启动时间 | < 500ms | 相机开启到预览显示 |

### 5.2 监控实现

```kotlin
class PerformanceMonitor {
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var frameTimeSum = 0L
    private var frameTimeCount = 0

    fun onFrameRendered(frameTimeMs: Long) {
        frameCount++
        frameTimeSum += frameTimeMs
        frameTimeCount++

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFpsTime >= 1000) {
            val fps = frameCount
            val avgFrameTime = if (frameTimeCount > 0) {
                frameTimeSum / frameTimeCount
            } else 0

            Log.d("R-Plan", "FPS: $fps, AvgFrameTime: ${avgFrameTimeMs}ms")

            frameCount = 0
            frameTimeSum = 0
            frameTimeCount = 0
            lastFpsTime = currentTime
        }
    }
}
```

### 5.3 性能告警

```kotlin
// 当 FPS < 25 或帧处理时间 > 20ms 时发出告警
if (fps < 25 || avgFrameTimeMs > 20) {
    Log.w("R-Plan", "Performance warning: FPS=$fps, FrameTime=${avgFrameTimeMs}ms")

    // 自动降级
    if (fps < 15 || avgFrameTimeMs > 40) {
        Log.e("R-Plan", "Performance critical, switching to fallback")
        switchToFallbackMode()
    }
}
```

---

## 6. 降级策略

### 6.1 自动降级触发条件

- 连续 3 秒 FPS < 15
- 帧处理时间持续 > 40ms
- OpenGL 错误（纹理创建失败、Shader 编译失败）
- SurfaceTexture 不可用

### 6.2 降级方案

**方案 A：降低预览分辨率**

```kotlin
// 从 1080p 降到 720p
surfaceTexture?.setDefaultBufferSize(1280, 720)
```

**方案 B：关闭实时美颜**

```kotlin
// 停止渲染线程，使用 CameraX 原生预览
renderThread?.interrupt()
renderThread = null

// 拍照时应用离线美颜
useOfflineBeauty = true
```

**方案 C：完全降级到 PreviewView**

```kotlin
// 移除自定义 View，使用 CameraX PreviewView
removeView(beautyPreviewView)
addView(previewView)
cameraXPreview.setSurfaceProvider(previewView.surfaceProvider)
```

### 6.3 离线美颜实现

```kotlin
// 拍照时应用美颜
suspend fun captureWithBeauty(settings: BeautySettings): Bitmap {
    val originalBitmap = captureOriginal()

    return withContext(Dispatchers.Default) {
        var processed = originalBitmap

        // 应用磨皮
        if (settings.smoothing > 0) {
            processed = applySmoothing(processed, settings.smoothing)
        }

        // 应用美白
        if (settings.whitening > 0) {
            processed = applyWhitening(processed, settings.whitening)
        }

        // 应用瘦脸
        if (settings.slimFace != 0) {
            processed = applySlimFace(processed, settings.slimFace, faces)
        }

        // 应用大眼
        if (settings.bigEyes > 0) {
            processed = applyBigEyes(processed, settings.bigEyes, faces)
        }

        processed
    }
}
```

---

## 7. 测试与验收

### 7.1 功能测试

| 测试项 | 预期结果 |
|--------|----------|
| 打开相机 | 预览画面正常显示，无黑屏 |
| 调节磨皮 | 画面实时变化，延迟 < 100ms |
| 调节美白 | 画面实时变化，延迟 < 100ms |
| 调节瘦脸 | 画面实时变化，延迟 < 100ms |
| 调节大眼 | 画面实时变化，延迟 < 100ms |
| 拍照保存 | 照片包含美颜效果 |

### 7.2 性能测试

| 测试项 | 目标值 | 测试机型 |
|--------|--------|----------|
| 预览帧率 | ≥ 30fps | 低端（骁龙 660） |
| 预览帧率 | ≥ 50fps | 中端（骁龙 778G） |
| 预览帧率 | ≥ 55fps | 高端（骁龙 8 Gen2） |
| 内存占用 | < 30MB | 所有机型 |
| 启动时间 | < 500ms | 所有机型 |

### 7.3 兼容性测试

- Android 8.0+（API 26+）
- 主流品牌：小米、华为、OPPO、vivo、三星
- 不同分辨率：720p、1080p、2K
- 不同摄像头：前置、后置、广角

---

## 8. 风险与应对

### 风险 1：设备兼容性

**风险**：部分设备 OpenGL ES 实现有 Bug

**应对**：

- 建立"兼容性问题库"，记录已知问题
- 针对特定设备禁用高级特性
- 自动降级到离线美颜

### 风险 2：性能不达标

**风险**：低端设备无法达到 30fps

**应对**：

- 分辨率自适应（720p → 480p）
- 美颜强度自适应（降低磨皮强度）
- 提供开关让用户选择

### 风险 3：内存溢出

**风险**：纹理资源未及时释放

**应对**：

- 严格的生命周期管理
- 使用 WeakReference 避免内存泄漏
- 定期内存分析

---

## 9. 相关文档

- `PRODUCT.md` - 产品需求规格说明书
- `FEATURES.md` - 功能交互规范
- `AGENTS.md` - AI Agent 操作规范
- `CAMERA_PREVIEW_GUIDE.md` - 相机预览完整指南
- `PIXELFREE_INTEGRATION.md` - PixelFreeEffects SDK 集成（备用引擎）

---

## 10. 总结

R 计划的核心是**构建一个高性能、可观测、可降级的 GPU 加速图像流处理管道**：

1. **零拷贝数据流**：CameraX → SurfaceTexture → OpenGL → TextureView
2. **共享上下文**：主线程初始化，渲染线程独立处理
3. **线程同步**：帧可用监听 + 自适应帧率
4. **性能监控**：FPS、帧时间、内存实时监控
5. **自动降级**：性能不达标时无缝切换到离线美颜

**关键成功因素**：

- ✅ 正确的初始化顺序
- ✅ 合适的 Surface 创建时机
- ✅ EGL 上下文的正确管理
- ✅ 渲染线程与相机帧的同步
- ✅ 完善的降级策略

**预期结果**：

- 成功：实现 30-60fps 实时美颜预览，零授权成本
- 失败：自动降级到离线美颜，不影响拍照功能

这是一个技术难度高但收益显著的方案，成功实施后将彻底解决性能问题，为后续扩展打下坚实基础。

