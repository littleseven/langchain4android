# 大美丽：实时美颜完整指南

**版本**：5.0
**状态**：实施中（大美丽 BIG_BEAUTY 主引擎 + GPUPixel 实验性备选）
**最后更新**：2026-04-11（技术路线反思与对齐，移除 PixelFree 残留描述）
**技术路线**：自研 GPU 加速管线 + EGL 共享上下文 + SurfaceTexture 直通 + GPUPixel 实验性集成

---

## 文档边界与导航

- 本文档聚焦 大美丽 主引擎：渲染链路、容灾回退、冷却恢复与观测指标。
- 预览比例与坐标转换细节：见 `CAMERA_PREVIEW_TECH_SPEC.md`。
- 产品交互与验收口径：见 `FEATURES.md`。
- beauty-engine 模块实现规范：见 `beauty-engine/src/main/java/com/picme/beauty/api/AGENTS.md`。

---

## 0. 背景与目标

### 0.0 引擎现状（2026-04-11）

| 引擎 | 状态 | 说明 |
|------|------|------|
| **大美丽（BIG_BEAUTY）** | ✅ 主引擎（默认） | 自研 OpenGL ES + EGL，单 Pass Shader，完整功能 |
| **GPUPixel（GPUPIXEL）** | 🧪 实验性备选 | 开源 C++/OpenGL ES 滤镜库，实验性集成，对应 `BeautyStrategy.GPUPIXEL` |
| ~~PixelFreeEffects~~ | ❌ 已完全移除（2026-04） | 因核心算法不可控、与 Compose 架构冲突，已于 2026-04 彻底清除 |

**切换方式**：通过 `BeautyStrategy`（`domain.model.UserPreferences`）配置，在 `CameraPreviewStrategies.kt` 中路由到对应引擎策略。

**容灾策略**：
- 大美丽初始化失败或运行异常时，由 `onGlWarmUpFallback` 收敛，回退并持久化状态。
- GPUPixel 初始化失败时，由 `GpupixelBeautyPreviewStrategy.bindPreview` 触发 `onWarmUpFallback`。
- **当前无自动恢复热切换**：两引擎均需重启预览绑定流程。

**长期定位**：
- 大美丽 从 App 内部能力逐步演进为独立视觉能力基础库（`beauty-engine` 模块库化）。
- GPUPixel 作为开源高性能 GPU 滤镜引擎评估方向，长期目标是逐步增强/替换大美丽的 Shader 管线。
- App 侧通过稳定 API 接入，不直接依赖底层 OpenGL/CameraX 实现。

### 0.1 现状问题（历史背景）

- **依赖不可控（已解决）**：PixelFreeEffects SDK 算法闭源、商业授权、与 Compose 架构冲突 → 已于 2026-04 完全移除。
- **性能调优困难（持续改进中）**：单 Pass Shader 覆盖磨皮/美白/大眼/瘦脸/唇色/腮红，GPU 负担渐重，低端机需额外调优。
- **调试可观测性（已完善）**：渲染线程每秒聚合 `PerfStats`（fps/processingMs/delayMs/cpuUsage/nullFrames），通过调试浮层实时展示。

### 0.2 目标（第一性原理）

从"用户体验"倒推技术要求：

1. **极致流畅**：预览帧率 ≥ 30fps，理想 60fps；单帧处理 ≤ 16ms
2. **零感延迟**：参数调节到画面变化的延迟 < 100ms（用户阈值）
3. **技术可控**：自研管线，快速迭代；零授权成本
4. **容错可用**：大美丽预览链路失败时触发 fallback，并支持冷却结束后重试

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

### 1.1 为什么选择自研（大美丽）而非第三方 SDK

| 维度 | PixelFreeEffects SDK（已移除） | GPUPixel（实验性） | 大美丽（自研，主引擎） |
|------|--------------------------------|--------------------|------------------------|
| 算法可控性 | ❌ 完全黑盒 | ✅ 开源 Apache 2.0 | ✅ 完全自主 |
| 性能调优 | ❌ 受限 SDK | ✅ 可针对性优化 | ✅ 可精准控制每一步 |
| 内存占用 | SDK + 资源 60MB+ | 目标 < 20MB | 目标 < 30MB |
| 延迟控制 | SDK 调度黑盒 | OpenGL ES 链路透明 | 可精准控制线程优先级 |
| 故障排查 | 依赖厂商支持 | 源码可查 | 全链路日志可观测 |
| 授权成本 | 商业授权费用 | Apache 2.0 零成本 | 零成本 |
| Compose 兼容性 | ❌ UIKit 冲突 | ✅ TextureView 嵌入 | ✅ SurfaceView/FrameLayout |
| 人脸检测 | SDK 内建 | 内建 FaceDetector | ML Kit 外部提供 |

### 1.2 为什么选择 OpenGL ES 而非 Vulkan

- **CameraX 兼容性**：CameraX Preview 默认输出 SurfaceTexture，天然对接 OpenGL ES
- **设备覆盖**：OpenGL ES 2.0 覆盖 99%+ Android 设备
- **开发周期**：Vulkan 学习曲线陡峭，2-3 周难以完成
- **后续优化**：可在 大美丽稳定后逐步评估迁移到 Vulkan

### 1.3 为什么必须 EGL 上下文共享

- **离屏初始化**：Shader 编译、资源加载需要 EGL 上下文
- **多线程渲染**：渲染线程需要独立的上下文，但共享纹理资源
- **CameraX 约束**：CameraX 的 SurfaceProvider 在主线程，渲染必须在独立线程

**正确做法**：

```
主线程：EGL 初始化 + SurfaceTexture 创建
   ↓
渲染线程：独立上下文（共享纹理） + 美颜渲染
```

### 1.4 磨皮算法选型决策记录（2026-04）

**当前实现**：双边滤波快速近似（9 点采样 + 值域高斯权重），已落地在 `BeautyShaders.FRAGMENT_SHADER_BEAUTY`。

> ⚠️ 历史文档（包括早期 BIG_BEAUTY_TECH_SPEC 和 beauty-engine/AGENTS.md）曾记录"使用盒式模糊（Box Blur）"。这是**规划期的方案描述，与实际代码不符**。
> 正确事实：代码中磨皮使用的是双边滤波快速近似，通过 9 点采样结合值域权重 `exp(-(ΔLuma)² / 2σ_r²)` 和空间距离权重 `exp(-dist² / 2σ_s²)` 实现边缘保护磨皮。

| 方案 | 优点 | 缺点 | 决策 |
|------|------|------|------|
| 盒式模糊（Box Blur） | 复杂度低、采样次数少 | 无法保留边缘，失真明显 | ❌ 放弃 |
| 双边滤波快速近似（9pt） | 保边效果好、算法自然 | 相对盒式略慢 | ✅ 当前实现 |
| 完整双边滤波（多 Pass） | 效果最佳 | 移动端性能不可接受 | ⏳ 未来 GPUPixel 方向 |

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
│  │   └─ SurfaceRequest → Surface (来自 大美丽)      │    │
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
    ↓ (Shader 处理：磨皮/美白/大眼/瘦脸/唇色/腮红)
美颜后纹理
    ↓ (直接显示)
SurfaceView Surface
```

**关键优化点**：

- ❌ 避免从 GPU 读回 CPU（耗时 ~50ms）
- ❌ 避免多次纹理上传（内存带宽瓶颈）
- ✅ 全流程在 GPU 完成，零拷贝

### 2.3 引擎策略路由（2026-04 当前实现）

```
BeautyStrategy（domain.model.UserPreferences）
    ↓
CameraPreviewStrategies.rememberPreviewStrategyBundle()
    ├── BeautyStrategy.BIG_BEAUTY → GlBeautyPreviewStrategy
    │       → GlBeautyPreviewProvider（beauty-engine/egl）
    │           → BeautyPreviewView → CameraPreviewRenderer → BeautyRenderer
    └── BeautyStrategy.GPUPIXEL → GpupixelBeautyPreviewStrategy
            → GpupixelBeautyPreviewProvider（beauty-engine/gpupixel）
                → GPUPixel 滤镜链（C++ JNI）
```

### 2.4 核心组件职责

#### BeautyPreviewView

**职责**：封装 SurfaceView 与渲染管线，提供简洁 API

**关键约束**：

- **禁止**在构造函数中启动渲染线程。
- 必须先等待 `SurfaceView` 的 `surfaceCreated/surfaceChanged` 可用后再绑定显示 Surface。
- CameraX 输入 Surface 通过 `getSurfaceForCamera()` 延迟创建并缓存，避免重复 new Surface。

#### CameraPreviewRenderer

**职责**：管理完整的渲染管线

**关键约束**：

- `init()` 不启动渲染线程
- `setRenderSurface()` 才启动渲染
- 渲染线程使用独立的共享上下文
- 渲染线程优先级设为 `Thread.MAX_PRIORITY`

#### BeautyRenderer

**职责**：执行美颜渲染，管理所有 uniform 参数

**当前支持的美颜参数（单 Pass Shader）**：

| 参数 | uniform | 类型 | 范围 |
|------|---------|------|------|
| 磨皮 | `uSmoothing` | Float | 0.0~1.0 |
| 美白 | `uWhitening` | Float | 0.0~1.0 |
| 大眼 | `uBigEyes` | Float | 0.0~1.0 |
| 瘦脸 | `uSlimFace` | Float | -1.0~1.0 |
| 唇色强度 | `uLipColor` | Float | 0.0~1.0 |
| 唇色色号 | `uLipColorIndex` | Int | 0~11 |
| 腮红强度 | `uBlush` | Float | 0.0~1.0 |
| 腮红色系 | `uBlushColorFamily` | Int | 0=粉/1=橙/2=梅 |
| 人脸中心 | `uFaceCenter` | Vec2 | 纹理 UV |
| 双眼位置 | `uLeftEye/uRightEye` | Vec2 | 纹理 UV |
| 嘴部关键点 | `uMouthLeft/Right/Center` | Vec2 | 纹理 UV |
| 唇部中心 | `uUpperLipCenter/uLowerLipCenter` | Vec2 | 纹理 UV |
| 唇部外轮廓 | `uLipOuterContourPoints[20]` | Vec2[] | 纹理 UV |
| 唇部内轮廓 | `uLipInnerContourPoints[20]` | Vec2[] | 纹理 UV |
| 人脸半径 | `uFaceRadius` | Float | 0.08~0.45 |
| 是否有脸 | `uHasFace` | Float | 0.0 / 1.0 |
| 纹素大小 | `uTexelSize` | Vec2 | 1/width, 1/height |

---

## 3. 核心技术难点与解决方案

### 3.1 难点 1：EGL 上下文管理

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

**解决方案**：输入 Surface 与显示 Surface 分离

**关键约束**：

- 输入 Surface（给 CameraX）与显示 Surface（SurfaceView）必须解耦。
- `setDefaultBufferSize()` 由输入分辨率动态设置（当前默认 1280x720）。
- 显示 Surface 可重建，输入 Surface 尽量复用。

### 3.3 难点 3：渲染线程同步与性能统计

**解决方案**：帧可用标记 + 轻量 sleep + 每秒聚合统计

```kotlin
@Volatile private var frameAvailable: Boolean = false

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
- 纹理矩阵与人脸点位映射必须使用同一份 `uTextureTransform`（通过 `mapViewNormalizedToUv` 转换）。
- 调试指标统一由 `BeautyPerfStats(fps, processingMs, delayMs, cpuUsage, nullFrames)` 提供。

### 3.4 难点 4：View 归一化坐标 → 纹理 UV 坐标映射

**背景**：ML Kit 输出的人脸坐标是屏幕归一化坐标（View 空间），而 Shader 中人脸变形参数需要在纹理 UV 空间中使用。

**解决方案**：`CameraPreviewRenderer.mapViewNormalizedToUv()` 四步映射

```
归一化 View 坐标 (0~1, 以预览容器为参考)
    ↓ Step 1: 映射到 viewport 内的像素坐标
    ↓ Step 2: 反转 Y 轴（OpenGL 纹理坐标原点在左下）
    ↓ Step 3: 归一化为 pre-transform UV（未应用纹理变换矩阵）
    ↓ Step 4: 乘以 SurfaceTexture 变换矩阵（uTextureTransform）
输出：Shader 可用的 UV 坐标
```

**约束**：
- `transformFaceCoordinateSimple()`（ML Kit → 屏幕像素）与 `mapViewNormalizedToUv()`（屏幕 → UV）是串联关系，不能跳过任何一步。
- 前置摄像头镜像已在 `transformFaceCoordinateSimple` 中处理，`mapViewNormalizedToUv` 中不重复处理。

---

## 4. 当前实现快照（2026-04-11）

### 4.1 已落地能力

- [x] `BeautyPreviewView` 采用 `SurfaceView` 显示，显示 Surface 与 CameraX 输入 Surface 解耦。
- [x] `CameraPreviewRenderer` 在主线程完成 EGL + 外部纹理 + SurfaceTexture 初始化。
- [x] 渲染线程使用 `OnFrameAvailableListener + sleep(1)` 驱动，避免忙等。
- [x] 单 Pass Shader 覆盖磨皮（双边滤波近似）/美白/大眼/瘦脸/唇色（含轮廓多边形）/腮红，支持实时 uniform 更新。
- [x] 已实现人脸点位到 UV 坐标映射（`mapViewNormalizedToUv`），保证形变参数与纹理变换一致。
- [x] 已输出 `BeautyPerfStats(fps, processingMs, delayMs, cpuUsage, nullFrames)` 供调试浮层展示。
- [x] `BeautyStrategy.GPUPIXEL` 实验性集成落地，`GpupixelBeautyPreviewProvider` 封装 GPUPixel C++ 滤镜链。
- [x] `BeautyPreviewEngine` 组合接口统一 `BeautyPreviewProvider` + `BeautyPreviewCapability`，App 层通过接口访问。
- [x] `BeautyStrategy`、`FaceDetectIntervalProfile` 等枚举迁移至 `domain.model.UserPreferences`，实现分层解耦。

### 4.2 双引擎现状

#### 大美丽（BIG_BEAUTY）— 主引擎
- **触发条件**：`BeautyStrategy.BIG_BEAUTY`（默认值）
- **路由类**：`GlBeautyPreviewStrategy`
- **Provider**：`GlBeautyPreviewProvider` → `BeautyPreviewView` → `CameraPreviewRenderer`
- **人脸检测**：依赖 ML Kit（外部注入 FaceWarpParams + LipMaskPoints）
- **容灾**：warm-up 失败调用 `onGlWarmUpFallback(reason)` 上报，由 `CameraRuntimeState` 持久化

#### GPUPixel（GPUPIXEL）— 实验性备选
- **触发条件**：`BeautyStrategy.GPUPIXEL`（用户手动切换）
- **路由类**：`GpupixelBeautyPreviewStrategy`
- **Provider**：`GpupixelBeautyPreviewProvider` → GPUPixel C++ JNI（`TextureView` 显示）
- **人脸检测**：GPUPixel 内建 `FaceDetector`，忽略 ML Kit 传入的 FaceWarpParams
- **当前限制**：
  - `createPreviewSurface()` 使用 TextureView（非 SurfaceView），功耗略高
  - `bindPreview` 中 `request.willNotProvideSurface()`，预览绑定逻辑尚待完善
  - 不支持腮红、眉毛等大美丽独有效果
  - `getPerfStats()` 返回 `EMPTY`，暂无性能指标

### 4.3 下一步技术项（RD，优先级排序）

#### 🔴 P0 — 稳定性与文档一致性
- [ ] 将自动回退结果提示与 I18N 文案在 UI 层彻底打通（当前以日志和调试态为主）。
- [ ] `beauty-engine/AGENTS.md` 中磨皮算法描述（"Box Blur"）与实际代码对齐，更新为"双边滤波快速近似"。

#### 🟡 P1 — 性能与可观测性
- [ ] 补充低端机专项压测基线（720p/1080p，前后置，连续 5 分钟）。
- [ ] 针对 `nullFrames` 异常波动增加告警阈值与自动抓日志能力。
- [ ] GPUPixel 路径补充性能指标采集（`getPerfStats` 实现）。

#### 🟢 P2 — GPUPixel 实验性路径完善
- [ ] `GpupixelBeautyPreviewStrategy.bindPreview` 完善 Surface 绑定逻辑（当前 `willNotProvideSurface`，未实际接入 CameraX 帧）。
- [ ] 评估 GPUPixel 在中端机型上的实际帧率与延迟数据。
- [ ] 决策：GPUPixel 是否升级为正式备选引擎，或仅作为 Shader 增强参考。

#### 🟢 P3 — 大美丽库化落地（8~16 周）
- [ ] `beauty-core`：沉淀策略模型、参数协议、状态机与能力契约。
- [ ] 定义库级稳定 API（含语义版本），确保 App 仅依赖能力接口。

### 4.4 三大目标驱动的重构路线（技术视角）

#### Phase 1：质量门禁与可观测性收敛（2~4 周）✅ 已完成
- 固化 CR 检查项：分层越界、I18N 漏同步、回退链路失败一票否决。
- 统一调试指标采集与日志字段，确保回归可追踪。

#### Phase 2：架构边界收敛（4~8 周）🔄 进行中（核心完成）
- 已落地（2026-04）：
  - [x] `BeautyStrategy`、`ThemeMode`、`AppLanguage`、`FaceDetectIntervalProfile` 迁移至 `domain.model.UserPreferences`。
  - [x] `LensFacing` 去除 CameraX 直接依赖，改为纯 Kotlin 枚举。
  - [x] `OcrUseCase` 实现下沉至 `data/local/MlKitOcrProcessor`，domain 层仅保留 `OcrProcessor` 接口。
  - [x] 新增 `domain/repository/UserSettingsRepository` 接口，ViewModel 依赖接口而非实现类。
- 剩余事项：
  - [ ] gallery 层 ViewModel 依赖审计与收敛。
  - [ ] camera 层其余直接调用 data 层的用例审计。

#### Phase 3：大美丽 库化落地（8~16 周）⏳ 待启动
- `beauty-core`：沉淀策略模型、参数协议、状态机与能力契约。
- `:beauty-engine`：实现 大美丽 引擎适配并对接能力契约。
- App 侧改为消费者模式：仅通过稳定 API 接入，避免直接依赖引擎实现。

#### 跨阶段验收标准
- M1：P0 自动化真实断言通过率 100%，关键链路可无人值守回归。✅
- M2：核心模块完成边界收敛，domain 层无跨层污染。🔄
- M3：大美丽 完成接口化接入，具备独立版本演进能力。⏳

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

### 5.2 性能告警

```kotlin
// 当 FPS < 25 或处理耗时 > 20ms 时发出告警
if (fps < 25 || processingMs > 20) {
    Log.w("PicMe:BeautyEngine", "Performance warning: fps=$fps, processing=${processingMs}ms")
}
```

### 5.3 性能分级目标（设备分级）

| 设备等级 | 代表机型 | 目标帧率 | 分辨率策略 |
|----------|----------|----------|------------|
| 低端机 | 骁龙 660 | ≥ 30fps | 720p 输入，可降至 480p |
| 中端机 | 骁龙 778G | ≥ 50fps | 1080p 输入 |
| 高端机 | 骁龙 8 Gen2 | ≥ 55fps | 1080p/2K 输入 |

---

## 6. 降级与恢复策略（当前实现）

### 6.1 自动回退触发点

- `GlBeautyPreviewProvider.initialize()` 抛出异常。
- `createPreviewSurface()` 在重试窗口内仍未拿到可用 Surface（120 次 × 30ms = 3.6s）。
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
            userPreferencesRepository.persistGlEngineFallback(BIG_BEAUTY_RECOVERY_COOLDOWN_MS)
        }
    }
}
```

- 回退状态写入 DataStore，包含恢复时间戳 `gl_engine_recovery_available_at_ms`。
- 冷却窗口结束后自动触发 `triggerManualGlEngineRecovery()` 重试主引擎。
- 当前冷却窗口：`BIG_BEAUTY_RECOVERY_COOLDOWN_MS = 3 * 60 * 1000L`（3 分钟）。

### 6.3 持久化冷却与自动恢复

```kotlin
// UserPreferencesRepository
suspend fun persistGlEngineFallback(cooldownMs: Long) {
    preferences[BEAUTY_STRATEGY] = BeautyStrategy.PIXEL_FREE.name  // 历史遗留，当前以策略枚举名保存
    preferences[GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = now + cooldownMs
}

suspend fun triggerManualGlEngineRecovery() {
    preferences[BEAUTY_STRATEGY] = BeautyStrategy.BIG_BEAUTY.name
    preferences[GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = 0L
}
```

> ⚠️ 注意：`persistGlEngineFallback` 历史实现中曾写入 `PIXEL_FREE`，当前两引擎为 `BIG_BEAUTY` 和 `GPUPIXEL`，需确认兜底策略对应关系（待 P0 修复）。

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
| 调节唇色 | 唇部精准染色，轮廓自然 |
| 调节腮红 | 双颊自然着色，无侵入眼周/鼻梁 |
| 拍照保存 | 照片包含美颜效果 |

### 7.2 性能测试（大美丽）

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

QA 相关内容已提取到独立文档：`docs/BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md`

---

## 8. 风险与应对

### 风险 1：设备兼容性

**风险**：部分设备 OpenGL ES 实现有 Bug

**应对**：
- 建立"兼容性问题库"，记录已知问题
- 针对特定设备禁用高级特性
- 大美丽 warm-up 失败通过 `onGlWarmUpFallback` 上报，触发冷却重试链路

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
- 使用 `WeakReference` 避免内存泄漏
- 定期内存分析

### 风险 4：GPUPixel 实验性路径稳定性

**风险**：GPUPixel C++ JNI 在特定机型崩溃或内存异常

**应对**：
- GPUPixel 路径仅在用户主动切换时激活，不作为默认引擎
- 同样接入 `onGlWarmUpFallback` 兜底链路
- 在正式提升为备选引擎前，需完成至少 3 款主流机型的压测

---

## 9. 相关文档与实现入口

- `PRODUCT.md` — 产品需求规格说明书（大美丽 产品策略）
- `FEATURES.md` — 功能交互规范（重点：`1.3.5` 大美丽 性能与验收）
- `AGENTS.md` — AI Agent 操作规范
- `CAMERA_PREVIEW_TECH_SPEC.md` — 相机预览与坐标系统规范
- ~~`PIXELFREE_FALLBACK_TECH_SPEC.md`~~ — **已废弃并移除（2026-04），PixelFree 已从项目完全清除**
- `BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md` — 大美丽 QA 独立执行清单
- `beauty-engine/src/main/java/com/picme/beauty/api/` — 对外稳定 API（`BeautyParams`、`BeautyPreviewProvider`、`BeautyPreviewCapability`、`BeautyPreviewEngine`）
- `beauty-engine/src/main/java/com/picme/beauty/egl/` — GL 渲染管线核心实现
- `beauty-engine/src/main/java/com/picme/beauty/gpupixel/` — GPUPixel 实验性集成
- `app/src/main/java/com/picme/features/camera/CameraScreen.kt` — 预览绑定、容灾回退与调试浮层
- `app/src/main/java/com/picme/features/camera/CameraPreviewStrategies.kt` — 引擎策略路由

---

## 10. 总结

大美丽的核心是**构建一个高性能、可观测、可降级的 GPU 加速图像流处理管道**：

1. **零拷贝数据流**：CameraX → SurfaceTexture → OpenGL → SurfaceView
2. **共享上下文**：主线程初始化，渲染线程独立处理
3. **线程同步**：帧可用监听 + `sleep(1)` 轻量轮询
4. **性能监控**：通过 `BeautyPerfStats` 实时暴露 FPS、处理耗时、延迟、CPU、空帧
5. **容灾机制**：大美丽 warm-up 失败时触发 `onGlWarmUpFallback`，进入冷却并在超时后自动重试

**当前技术路线关键决策**：

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 主引擎 | 自研 大美丽（OpenGL ES） | 可控性最高、零授权、全链路可观测 |
| 备选引擎 | GPUPixel（实验性） | 开源/Apache 2.0、C++/OpenGL ES、内建人脸检测 |
| ~~兜底引擎~~ | ~~PixelFree~~ | ❌ 已于 2026-04 完全移除 |
| 磨皮算法 | 双边滤波快速近似（9pt） | 保边效果好，移动端性能可接受 |
| 显示层 | SurfaceView | 直接硬件合成，延迟更低，功耗更小 |
| 人脸检测 | ML Kit（外部注入） | 复用现有分析流，不阻塞渲染线程 |
| 渲染参数 | 全 uniform 实时更新 | 无需重新编译 Shader，延迟 < 100ms |

**预期结果**：

- 成功：实现 30-60fps 实时美颜预览，零授权成本，用户可无感知享受效果
- 降级：大美丽 warm-up 失败进入冷却，3 分钟后自动重试；用户可手动切换 GPUPixel 实验性路径

