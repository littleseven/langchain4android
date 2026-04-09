# R 计划：实时美颜完整指南

**版本**：4.1
**状态**：实施中（R Plan 主引擎 + PixelFree 稳定兜底）
**最后更新**：2026-04（按当前实现对齐）
**技术路线**：自研 GPU 加速管线 + EGL 共享上下文 + SurfaceTexture 直通 + 运行时自动回退

---

## 文档边界与导航

- 本文档聚焦 R Plan 主引擎：渲染链路、容灾回退、冷却恢复与观测指标。
- 预览比例与坐标转换细节：见 `CAMERA_PREVIEW_TECH_SPEC.md`。
- PixelFree 兜底引擎集成细节：见 `PIXELFREE_FALLBACK_TECH_SPEC.md`。
- 产品交互与验收口径：见 `FEATURES.md`。

## 0. 背景与目标

### 0.0 双引擎定位

- **主引擎（默认）**：R 计划
- **备用引擎**：PixelFreeEffects SDK
- **切换方式**：设置页「美颜引擎」配置开关
- **容灾策略**：R 计划初始化失败或运行异常时，自动回退 PixelFreeEffects
- **长期定位**：R Plan 从 App 内部能力逐步演进为独立视觉能力基础库。
- **能力范围**：基础库统一承载美颜、滤镜、妆容能力及参数协议。
- **接入方式**：App 侧通过稳定 API 接入，不直接依赖底层 OpenGL/CameraX 实现。

### 0.1 现状问题

- **性能不佳**：1080p 预览在开启美颜后明显卡顿，滑杆跟手性差
- **依赖不可控**：PixelFreeEffects SDK 占用额外内存与授权成本
- **调试困难**：问题定位需要 SDK 内部日志，排期不可控

### 0.2 目标（第一性原理）

从“用户体验”倒推技术要求：

1. **极致流畅**：预览帧率 ≥ 30fps，理想 60fps；单帧处理 ≤ 16ms
2. **零感延迟**：参数调节到画面变化的延迟 < 100ms（用户阈值）
3. **技术可控**：自研管线，快速迭代；零授权成本
4. **容错可用**：R Plan 预览链路失败时自动切换 PixelFree 兜底，并在冷却结束后自动重试 R Plan

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
┌──────────────────────────────────────────────────────────┐
│                     UI Layer (Compose)                  │
│  ┌──────────────────────────────────────────────────┐    │
│  │           BeautyPreviewView (自定义 View)        │    │
│  │  ┌──────────────────────────────────────────┐    │    │
│  │  │   SurfaceView (显示最终渲染结果)         │    │    │
│  │  └──────────────────────────────────────────┘    │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────┐
│               Rendering Layer (OpenGL ES)               │
│  ┌──────────────────────────────────────────────────┐    │
│  │    CameraPreviewRenderer (渲染管线核心)          │    │
│  │    ├─ EGLCore (EGL 管理)                         │    │
│  │    ├─ BeautyRenderer (美颜渲染器)                │    │
│  │    ├─ SurfaceTexture(相机输入纹理)               │    │
│  │    └─ WindowSurface (SurfaceView 输出目标)       │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────┐
│                 Camera Layer (CameraX)                  │
│  ┌──────────────────────────────────────────────────┐    │
│  │   Preview UseCase                                │    │
│  │   └─ SurfaceRequest → Surface (来自 R Plan)      │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
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
SurfaceView Surface
```

**关键优化点**：

- ❌ 避免从 GPU 读回 CPU（耗时 ~50ms）
- ❌ 避免多次纹理上传（内存带宽瓶颈）
- ✅ 全流程在 GPU 完成，零拷贝

### 2.3 核心组件职责

#### BeautyPreviewView

**职责**：封装 SurfaceView 与渲染管线，提供简洁 API

```kotlin
class BeautyPreviewView(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val surfaceView: SurfaceView = SurfaceView(context)
    private val renderer: CameraPreviewRenderer = CameraPreviewRenderer()

    private var cameraSurface: Surface? = null
    private var displaySurface: Surface? = null
    private var isRendererInitialized: Boolean = false

    // 美颜参数
    var smoothingStrength: Float = 0.5f
    var whiteningStrength: Float = 0.5f
    var bigEyesStrength: Float = 0f
    var slimFaceStrength: Float = 0f

    fun ensureOffscreenReady()
    fun getSurfaceForCamera(): Surface?
    fun getSurfaceTexture(): SurfaceTexture?
    fun setCameraInputBufferSize(width: Int, height: Int)
    fun setScaleMode(isFillCenter: Boolean)
}
```

**关键约束**：

- **禁止**在构造函数中启动渲染线程。
- 必须先等待 `SurfaceView` 的 `surfaceCreated/surfaceChanged` 可用后再绑定显示 Surface。
- CameraX 输入 Surface 通过 `getSurfaceForCamera()` 延迟创建并缓存，避免重复 new Surface。

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

### 3.2 难点 2：输入 Surface 与显示 Surface 解耦

**问题**：

- CameraX 需要一个稳定可用的输入 Surface。
- 渲染显示层在部分设备上存在 Surface 生命周期抖动。
- 输入输出共用 Surface 容易导致黑屏与重绑抖动。

**解决方案**：输入 Surface 与显示 Surface 分离

```kotlin
// BeautyPreviewView
private val surfaceView: SurfaceView = SurfaceView(context)
private var cameraSurface: Surface? = null

fun getSurfaceForCamera(): Surface? {
    ensureRendererInitialized()
    renderer.getSurfaceTexture()?.setDefaultBufferSize(cameraInputWidth, cameraInputHeight)
    if (cameraSurface == null) {
        cameraSurface = renderer.getSurfaceForCamera()
    }
    return cameraSurface
}

private fun bindDisplaySurface(surface: Surface) {
    if (!surface.isValid) return
    renderer.setRenderSurface(surface)
}
```

**关键约束**：

- 输入 Surface（给 CameraX）与显示 Surface（SurfaceView）必须解耦。
- `setDefaultBufferSize()` 由输入分辨率动态设置（当前默认 1280x720）。
- 显示 Surface 可重建，输入 Surface 尽量复用。

### 3.3 难点 3：渲染线程同步与性能统计

**问题**：

- 渲染线程需要避免空转与抢占主线程。
- 需要持续输出可观测指标给调试浮层。
- 人脸形变参数要与纹理变换矩阵处在同一坐标空间。

**解决方案**：帧可用标记 + 轻量 sleep + 每秒聚合统计

```kotlin
@Volatile private var frameAvailable: Boolean = false
@Volatile private var latestPerfStats: PerfStats = PerfStats()

surfaceTexture?.setOnFrameAvailableListener {
    frameAvailable = true
}

while (isRendering && !Thread.interrupted()) {
    if (!frameAvailable) {
        Thread.sleep(1)
        continue
    }

    surfaceTexture?.updateTexImage()
    frameAvailable = false

    beautyRenderer.onRender()
    windowSurface?.swapBuffers()

    // 每秒聚合一次 FPS / processing / delay / cpu / nullFrames
    latestPerfStats = calculateStats()
}
```

**关键约束**：

- 无帧时走 `sleep(1)`，避免忙等。
- 纹理矩阵与人脸点位映射必须使用同一份 `uTextureTransform`。
- 调试指标统一由 `PerfStats(fps, processingMs, delayMs, cpuUsage, nullFrames)` 提供。

---

## 4. 当前实现快照（2026-04）

### 4.1 已落地能力

- [x] `BeautyPreviewView` 采用 `SurfaceView` 显示，显示 Surface 与 CameraX 输入 Surface 解耦。
- [x] `CameraPreviewRenderer` 在主线程完成 EGL + 外部纹理 + SurfaceTexture 初始化。
- [x] 渲染线程使用 `OnFrameAvailableListener + sleep(1)` 驱动，避免忙等。
- [x] 已实现磨皮/美白/大眼/瘦脸参数链路，支持实时更新。
- [x] 已实现人脸点位到 UV 坐标映射，保证形变参数与纹理变换一致。
- [x] 已输出 `PerfStats(fps, processingMs, delayMs, cpuUsage, nullFrames)` 供调试浮层展示。

### 4.2 双引擎容灾现状

- [x] `rememberPreviewStrategyBundle(...)` 按 `BeautyStrategy` 选择 `GlBeautyPreviewStrategy` 或 `PixelFreePreviewStrategy`。
- [x] `GlBeautyPreviewStrategy.bindPreview(...)` 成功时返回 `true`，由 `useProviderRenderView` 驱动 UI 切换到 Provider View。
- [x] GL 引擎 warm-up 失败会触发 `onGlWarmUpFallback(reason)`，并持久化回退到 `PIXEL_FREE`。
- [x] 回退状态写入 DataStore，包含恢复时间戳 `gl_engine_recovery_available_at_ms`。
- [x] 冷却窗口结束后自动触发 `triggerManualGlEngineRecovery()` 重试主引擎。
- [x] 支持 provider 绑定超时兜底：超过 `PROVIDER_VIEW_BIND_TIMEOUT_MS=1800ms` 时自动回落 `PreviewView` 并请求重绑。

### 4.3 下一步技术项（RD）

- [ ] 将自动回退结果提示与 I18N 文案在 UI 层彻底打通（当前以日志和调试态为主）。
- [ ] 补充低端机专项压测基线（720p/1080p，前后置，连续 5 分钟）。
- [ ] 针对 `nullFrames` 异常波动增加告警阈值与自动抓日志能力。
- [ ] 抽离 `beauty-core`（纯 Kotlin）：策略模型、参数映射、回退/恢复状态机。
- [ ] 持续迭代 `:beauty-engine` 模块：R Plan 渲染适配层（Surface/CameraX/OpenGL）。
- [ ] 定义库级稳定 API（含语义版本），确保 App 仅依赖能力接口。

### 4.4 三大目标驱动的重构路线（技术视角）

#### Phase 1：质量门禁与可观测性收敛（2~4 周）✅ 已完成
- 目标对齐：保障商业级稳定交付（目标 2），建立 Agent 协作闭环（目标 1）。
- 技术动作：
  - 将 P0 用例从 skeleton 升级为真实断言，并纳入 CI 阻断。
  - 固化 CR 检查项：分层越界、I18N 漏同步、回退链路失败一票否决。
  - 统一调试指标采集与日志字段，确保回归可追踪。

#### Phase 2：架构边界收敛（4~8 周）🔄 进行中（核心完成）
- 目标对齐：降低演进成本，支撑长期库化（目标 3）。
- 技术动作：
  - 按 `settings -> gallery -> camera` 顺序推进，逐步降低风险。
  - 收敛依赖方向：`features -> domain usecase -> domain repository -> data impl`。
  - 清理 domain 污染：去除对 `android.*`、`features.*` 的反向依赖。
- 已落地（2026-04）：
  - [x] `BeautyStrategy`、`ThemeMode`、`AppLanguage`、`FaceDetectIntervalProfile` 迁移至 `domain.model.UserPreferences`，features/data 层均从 domain 导入。
  - [x] `LensFacing` 去除 CameraX 直接依赖，改为纯 Kotlin 枚举。
  - [x] `OcrUseCase` 实现下沉至 `data/local/MlKitOcrProcessor`，domain 层仅保留 `OcrProcessor` 接口。
  - [x] 新增 `domain/repository/UserSettingsRepository` 接口；`UserPreferencesRepository` 实现该接口；`SettingsViewModel`、`CameraRuntimeState`、`AppContainer` 均依赖接口而非实现类。
- 剩余事项：
  - [ ] gallery 层 ViewModel 依赖审计与收敛。
  - [ ] camera 层其余直接调用 data 层的用例审计。

#### Phase 3：R Plan 库化落地（8~16 周）⏳ 待启动
- 目标对齐：形成可独立发布的视觉能力底座（目标 3）。
- 技术动作：
  - `beauty-core`：沉淀策略模型、参数协议、状态机与能力契约。
  - `:beauty-engine`：实现 R Plan 引擎适配并对接能力契约。
  - App 侧改为消费者模式：仅通过稳定 API 接入，避免直接依赖引擎实现。

#### 跨阶段验收标准
- M1：P0 自动化真实断言通过率 100%，关键链路可无人值守回归。✅
- M2：核心模块完成边界收敛，domain 层无跨层污染。🔄
- M3：R Plan 完成接口化接入，具备独立版本演进能力。⏳

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

            Log.d("PicMe:CameraPreview", "FPS: $fps, AvgFrameTime: ${avgFrameTime}ms")

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
// 当 FPS < 25 或处理耗时 > 20ms 时发出告警
if (fps < 25 || processingMs > 20) {
    Log.w("PicMe:CameraPreview", "Performance warning: fps=$fps, processing=${processingMs}ms")
}

// 预览链路异常时交给双引擎容灾链路处理
runCatching {
    bindGlEnginePreview()
}.onFailure { error ->
    BeautyEngineRuntimeState.markGlEngineFallback(error.message ?: "runtime error")
    userPreferencesRepository.persistGlEngineFallback(R_PLAN_RECOVERY_COOLDOWN_MS)
}
```

---

## 6. 降级与恢复策略（当前实现）

### 6.1 自动回退触发点

- `GlBeautyPreviewProvider.initialize()` 抛出异常。
- `createPreviewSurface()` 在重试窗口内仍未拿到可用 Surface。
- 预览 warm-up 期间 Provider 绑定失败，或超过 `PROVIDER_VIEW_BIND_TIMEOUT_MS`。

### 6.2 回退执行链路

```kotlin
// CameraRuntimeState.rememberGlRecoveryState
val onGlWarmUpFallback: (String) -> Unit = { reason ->
    BeautyEngineRuntimeState.markGlEngineFallback(reason)
    if (!persistedFallback) {
        persistedFallback = true
        persistedFallbackReason = reason
        coroutineScope.launch {
            userPreferencesRepository.persistGlEngineFallback(R_PLAN_RECOVERY_COOLDOWN_MS)
        }
    }
}
```

- 回退目标为 `PIXEL_FREE`（稳定兜底引擎），不是离线拍照后处理。
- 回退由 `onGlWarmUpFallback(reason)` 统一收敛，避免多处写策略状态。
- 调试浮层展示 fallback 状态、剩余冷却时间与失败原因。

### 6.3 持久化冷却与自动恢复

```kotlin
// UserPreferencesRepository
suspend fun persistGlEngineFallback(cooldownMs: Long) {
    preferences[BEAUTY_STRATEGY] = BeautyStrategy.PIXEL_FREE.name
    preferences[GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = now + cooldownMs
}

suspend fun triggerManualGlEngineRecovery() {
    preferences[BEAUTY_STRATEGY] = BeautyStrategy.R_PLAN.name
    preferences[GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = 0L
}
```

- 当前冷却窗口：`R_PLAN_RECOVERY_COOLDOWN_MS = 3 * 60 * 1000L`。
- 冷却结束后自动触发 `triggerManualGlEngineRecovery()`，重新尝试主引擎。
- 若重试再次失败，继续按同链路回退并进入下一轮冷却。

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

### 7.4 QA 与回归检查

为避免 `R_PLAN_TECH_SPEC.md` 过长，QA 相关内容已提取到独立文档：

- `docs/R_PLAN_QA_EXECUTION_CHECKLIST.md`

该文档包含：
- 测试基线（功能 / 性能 / 兼容性）
- RD 技术验收清单（按类/函数）
- 容灾回归步骤（手工）
- QA 执行版（P0/P1）与执行记录模板

---

## 8. 风险与应对

### 风险 1：设备兼容性

**风险**：部分设备 OpenGL ES 实现有 Bug

**应对**：

- 建立"兼容性问题库"，记录已知问题
- 针对特定设备禁用高级特性
- 自动回退到 PixelFree 兜底，并在冷却后自动重试 R Plan

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

## 9. 相关文档与实现入口

- `PRODUCT.md` - 产品需求规格说明书（R Plan 产品策略）
- `FEATURES.md` - 功能交互规范（重点：`1.3.5` R Plan 性能与验收）
- `AGENTS.md` - AI Agent 操作规范与双引擎约束
- `CAMERA_PREVIEW_TECH_SPEC.md` - 相机预览与坐标系统规范
- `PIXELFREE_FALLBACK_TECH_SPEC.md` - PixelFreeEffects SDK 集成（备用引擎）
- `R_PLAN_QA_EXECUTION_CHECKLIST.md` - R Plan QA 独立执行清单
- `app/src/main/java/com/picme/core/image/BeautyPreviewView.kt` - R Plan 预览视图实现
- `app/src/main/java/com/picme/core/image/CameraPreviewRenderer.kt` - R Plan 渲染主链路
- `app/src/main/java/com/picme/core/image/gl/GlBeautyPreviewProvider.kt` - GL 引擎 Provider 封装（App 层适配器）
- `beauty-engine/src/main/java/com/picme/beauty/egl/` - GL 渲染管线核心实现
- `app/src/main/java/com/picme/features/camera/CameraScreen.kt` - 预览绑定、容灾回退与调试浮层

---

## 10. 总结

R 计划的核心是**构建一个高性能、可观测、可降级的 GPU 加速图像流处理管道**：

1. **零拷贝数据流**：CameraX → SurfaceTexture → OpenGL → SurfaceView
2. **共享上下文**：主线程初始化，渲染线程独立处理
3. **线程同步**：帧可用监听 + `sleep(1)` 轻量轮询
4. **性能监控**：通过 `PerfStats` 实时暴露 FPS、处理耗时、延迟、CPU、空帧
5. **自动回退**：R Plan 异常时切换 PixelFree，并进入冷却后自动重试

**关键成功因素**：

- ✅ 正确的初始化顺序
- ✅ 合适的 Surface 创建时机
- ✅ EGL 上下文的正确管理
- ✅ 渲染线程与相机帧的同步
- ✅ 完善的降级策略

**预期结果**：

- 成功：实现 30-60fps 实时美颜预览，零授权成本
- 失败：自动切换 PixelFree 兜底，并在冷却窗口结束后自动重试 R Plan

这是一个技术难度高但收益显著的方案，成功实施后将彻底解决性能问题，为后续扩展打下坚实基础。

