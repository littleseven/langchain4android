# 大美丽：实时美颜完整指南

**版本**：6.0
**状态**：实施中（大美丽 BIG_BEAUTY 主引擎 + GPUPixel 实验性备选）
**最后更新**：2026-05-01（同步多 Pass 现状、容灾可观测性与文档清理）
**技术路线**：自研 GPU 加速管线 + EGL 共享上下文 + SurfaceTexture 直通 + 拍照 CPU/GPU 混合处理

---

## 文档边界与导航

- 本文档聚焦 大美丽 主引擎：渲染链路、容灾回退、冷却恢复与观测指标。
- 预览比例与坐标转换细节：见 `CAMERA_PREVIEW_TECH_SPEC.md`。
- 产品交互与验收口径：见 `FEATURES.md`。
- beauty-engine 模块实现规范：见 `beauty-engine/AGENTS.md`。

---

## 0. 背景与目标

### 0.0 引擎现状（2026-04-11）

| 引擎 | 状态 | 说明 |
|------|------|------|
| **大美丽（BIG_BEAUTY）** | ✅ 主引擎（默认） | 自研 OpenGL ES + EGL；基础美颜走主 Shader，磨皮/美白/几何美型/妆容按需切换多 Pass GPU 管线 |
| **GPUPixel（GPUPIXEL）** | 🧪 实验性备选 | 开源 C++/OpenGL ES 滤镜库，实验性集成，对应 `BeautyStrategy.GPUPIXEL` |

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

- **技术路线已收敛（已解决）**：当前仅保留大美丽与 GPUPixel 两条链路，所有旧兜底引擎实现、文档与状态引用已清理。
- **性能调优困难（持续改进中）**：多 Pass 链路覆盖磨皮/美白/大眼/瘦脸/唇色/腮红，低端机仍需控制 FBO 切换与 Shader 复杂度。
- **调试可观测性（已完善）**：渲染线程每秒聚合 `PerfStats`（fps/processingMs/delayMs/cpuUsage/nullFrames/errorCategory/errorReason），通过调试浮层实时展示。

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

| 维度 | GPUPixel（实验性） | 大美丽（自研，主引擎） |
|------|--------------------|------------------------|
| 算法可控性 | ✅ 开源 Apache 2.0 | ✅ 完全自主 |
| 性能调优 | ✅ 可针对性优化 | ✅ 可精准控制每一步 |
| 内存占用 | 目标 < 20MB | 目标 < 30MB |
| 延迟控制 | OpenGL ES 链路透明 | 可精准控制线程优先级 |
| 故障排查 | 源码可查 | 全链路日志可观测 |
| 授权成本 | Apache 2.0 零成本 | 零成本 |
| Compose 兼容性 | ✅ TextureView 嵌入 | ✅ SurfaceView/FrameLayout |
| 人脸检测 | 内建 FaceDetector | ML Kit / MediaPipe 外部提供 |

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

### 1.4 磨皮算法演进路线（2026-04）

**当前实现**：双边滤波快速近似（9 点采样 + 值域高斯权重），已落地在 `BeautyShaders.FRAGMENT_SHADER_BEAUTY`。

> ⚠️ 历史文档（包括早期 BIG_BEAUTY_TECH_SPEC 和 beauty-engine/AGENTS.md）曾记录"使用盒式模糊（Box Blur）"。这是**规划期的方案描述，与实际代码不符**。
> 正确事实：代码中磨皮使用的是双边滤波快速近似，通过 9 点采样结合值域权重 `exp(-(ΔLuma)² / 2σ_r²)` 和空间距离权重 `exp(-dist² / 2σ_s²)` 实现边缘保护磨皮。

**算法演进路线**（业界路径参考 Analysis_Report.md 5.2 节）：

| 阶段 | 方案 | 复杂度 | 边缘保持 | 状态 |
|------|------|------|------|------|
| 已放弃 | 盒式模糊（Box Blur） | O(1) | 无，失真明显 | ❌ 放弃 |
| **当前** | 双边滤波快速近似（9pt Shader 内联） | O(N·r²) 近似 | 良好，保留皮肤轮廓 | ✅ 已落地 |
| Phase 2 | **引导滤波（Guided Filter）** | **O(N)，与半径无关** | 更优，结构转移特性，无梯度反转光晕 | ⏳ 规划中 |
| Phase 3 | 多尺度细节分层（Multi-scale） | O(N·K)，K层 | 工业级，分频层独立处理后融合 | 🔭 长期目标 |

> **引导滤波选型依据**：相比双边滤波，引导滤波时间复杂度为 O(N)（闭式解，与滤波半径无关），不存在梯度反转光晕，支持天然多尺度堆叠，更适合移动端实时处理。GPUPixel 项目在磨皮滤镜中已验证该路径可行性。

### 1.5 滤镜技术演进路线（2026-04）

**当前实现**：自定义 GLSL Shader 硬编码颜色变换（LEICA_CLASSIC / FILM_GOLD / COOL / WARM）。

**演进规划**：

| 阶段 | 方案 | 优势 | 状态 |
|------|------|------|------|
| **当前** | 自定义 GLSL Shader | 完全可控，无额外依赖 | ✅ 已落地 |
| Phase 2 | **3D LUT（颜色查找表）** | 预计算 64×64×64 网格，运行时三线性插值，接近零计算开销，支持专业调色风格动态扩展 | ⏳ 规划中 |
| Phase 3 | GPUPixel Filter Chain | 滤镜模块化可插拔，与 GPUPixel 引擎深度集成 | 🔭 长期目标 |

> **3D LUT 选型依据**：专业图像处理领域（Lightroom、DaVinci Resolve）的标准颜色变换技术，在运行时以极低计算开销应用复杂色彩风格，适合扩展「徕卡色」「胶片色」等高级调色预设。

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

### 2.4 拍照处理架构（2026-04 新增）

#### 设计决策：方案 B 变种（长期向方案 A 演进）

**背景**：预览和拍照效果不一致是行业难题。预览使用 GPU Shader 实时渲染，拍照后处理使用 CPU Canvas，算法实现不同导致效果差异。

**竞品路线参考**：
- 美图/B612/抖音：**方案 A**（全 GPU 管线，效果 100% 一致）
- 轻颜/小米：**方案 B**（预览 GPU + 拍照 CPU 精修，略有差异）

**PicMe 当前路线**：

```
当前（方案 B 变种）：
预览: CameraX → SurfaceTexture → OpenGL ES Shader → SurfaceView
拍照: CameraX → ImageCapture → Bitmap → CPU 处理(Canvas) → 保存

长期目标（方案 A）：
预览: CameraX → SurfaceTexture → OpenGL ES Shader → SurfaceView
拍照: CameraX → ImageCapture → GPU 离屏渲染 → 保存
```

#### 方案 B 变种核心设计

**目标**：在保持 CPU 处理灵活性的前提下，最大化复用预览阶段的参数和算法逻辑，减少预览/拍照差异。

**关键优化点**：

1. **人脸检测复用**
   - 预览阶段 ML Kit 检测结果缓存
   - 拍照时直接使用缓存的 `FaceWarpParams`，避免重新检测
   - 减少检测差异导致的美颜效果不一致

2. **参数统一转换**
   - 提取 `BeautySettings` → `BeautyParams` 公共转换逻辑
   - 预览和拍照共用同一套参数转换，避免数值差异

3. **算法核心抽象**
   - 磨皮/美白/瘦脸等算法的核心数值计算抽象为纯函数
   - GPU Shader 和 CPU Canvas 共用同一套数值逻辑

4. **效果对比调试**
   - 开发模式下同时保存"预览截图"和"拍照结果"
   - 便于对比差异，持续优化

#### 架构分层

```
┌─────────────────────────────────────────────────────────┐
│  UI Layer (CameraScreen)                                │
│  ├─ 预览: BeautyPreviewView (OpenGL ES 实时渲染)        │
│  └─ 拍照: ImageProcessor.takePhoto() (CPU 后处理)       │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│  公共层 (beauty-core，待提取)                            │
│  ├─ BeautyParamsConverter (参数统一转换)                │
│  ├─ FaceWarpParamsCache (人脸检测结果缓存)              │
│  └─ BeautyAlgorithmCore (算法数值核心)                  │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│  实现层                                                  │
│  ├─ 预览: BeautyRenderer (GPU Shader 实现)              │
│  └─ 拍照: GpuBeautyProcessor (CPU Canvas 实现)          │
└─────────────────────────────────────────────────────────┘
```

#### 演进路线

| 阶段 | 时间 | 目标 | 关键动作 | 状态 |
|------|------|------|----------|------|
| **当前** | 2026-04 | 方案 B 变种落地 | 人脸检测复用、参数统一转换 | ✅ 已完成 |
| **Phase 1** | 2-4 周 | 减少差异 | 提取公共算法核心、效果对比调试 | 🔄 进行中 |
| **Phase 2** | 4-8 周 | 方案 A 准备 | GPU 离屏渲染基础设施 | ⏳ 待启动 |
| **Phase 3** | 8-12 周 | 方案 A 落地 | 拍照迁移到 GPU，全管线统一 | ⏳ 待启动 |

---

### 2.5 核心组件职责

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

**当前支持的美颜参数（基础主 Shader + 妆容多 Pass）**：

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
- 调试指标统一由 `BeautyPerfStats(fps, processingMs, delayMs, cpuUsage, nullFrames, errorCategory, errorReason)` 提供。

### 3.4 难点 4：View 归一化坐标 → 纹理 UV 坐标映射

**背景**：`FaceWarpParams` 输出的人脸坐标是屏幕归一化坐标（View 空间），而 Shader 中人脸变形参数需要在纹理 UV 空间中使用。

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

### 3.5 难点 5：MediaPipe 468 点 → 106 点映射

**背景**：当前主分析链路使用 MediaPipe Face Landmarker（468 点），需将 468 点映射为火山引擎 106 点标准格式，供大美丽链路与双模式调试复用。

**关键说明**：具体映射关系以代码实现为准，本文档仅记录核心原则。

#### 3.5.1 106 点拓扑定义

| 索引范围 | 点数 | 区域 | 说明 |
|---------|------|------|------|
| 0-32 | 33 | 脸部轮廓 | 开放曲线：右脸颊 → 下巴 → 左脸颊，无额头点 |
| 33-37 | 5 | 右眉上部 | 从眉头到眉尾（画面左侧=实际右脸） |
| 38-42 | 5 | 左眉上部 | 从眉头到眉尾（画面右侧=实际左脸） |
| 43 | 1 | 眉心 | 两眉之间中点 |
| 44-46 | 3 | 鼻梁 | 从上到下 |
| 47-51 | 5 | 鼻尖 | 从左到右，严格对称 |
| 52-57 | 6 | 右眼外轮廓 | 画面左侧=实际右脸 |
| 58-63 | 6 | 左眼外轮廓 | 画面右侧=实际左脸 |
| 64-67 | 4 | 右眉下部 | 从眉头到眉尾 |
| 68-71 | 4 | 左眉下部 | 从眉尾到眉头 |
| 72-74 | 3 | 右眼内/下 | 74=右瞳孔 |
| 75-77 | 3 | 左眼内/下 | 77=左瞳孔 |
| 78-79 | 2 | 山根 | 眉心两侧 |
| 80-83 | 4 | 鼻孔 | 左右鼻孔 |
| 84-95 | 12 | 嘴巴外轮廓 | 顺时针闭合曲线 |
| 96-103 | 8 | 嘴巴内轮廓 | 顺时针闭合曲线 |
| 104-105 | 2 | 瞳孔 | 104=右瞳孔, 105=左瞳孔 |

#### 3.5.2 映射实现参考

**非轮廓 73 点（33-105）**的具体映射关系请参考：
- [MediaPipeFaceDetector.kt](../app/src/main/java/com/picme/features/camera/facedetect/MediaPipeFaceDetector.kt) - 生产环境映射
- [FaceLandmarkDebugScreen.kt](../app/src/main/java/com/picme/features/debug/FaceLandmarkDebugScreen.kt) - 调试环境映射

**映射原则**：
1. **语义优先**：每个 106 点找到 MediaPipe 中语义对应的固定点
2. **插值过渡**：在固定点之间使用 `midPoint` 插值
3. **对称性验证**：确保关键点左右对称

#### 3.5.3 轮廓 33 点生成算法

**核心约束**：
- GPUPixel 轮廓为**开放曲线**，从右脸颊开始，经下巴，到左脸颊结束
- **无额头点**，M0 和 M32 与上眼皮位置齐平
- **严格左右对称**，中心点 M16 强制 x=0.5
- 中间点均匀分布

**算法步骤**：

1. **选取 MediaPipe 左半边基础点**（12 个）：
   ```
   234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152, 377
   ```

2. **插值生成 17 个左半边点**（含中心点）：
   - 使用基于线段长度的自适应插值
   - 较长线段分配更多插值点
   - 生成点 0-16（16 为下巴中心）

3. **强制对称生成右半边**（16 个点）：
   - 右半边点 17-32 是左半边点 15-0 的水平镜像
   - `x_right = 1.0 - x_left`
   - `y_right = y_left`

4. **调整 M0 和 M32 到上眼皮位置**：
   - 强制 `y = 0.38`（上眼皮齐平）
   - 均匀分布中间点：`y_step = (chinY - 0.38) / 16`

5. **强制中心点对齐**：
   - `M16.x = 0.5`（严格居中）

**坐标验证示例**（非前置摄像头）：
```
M0=(0.119,0.380)  M1=(0.125,0.391)  ...  M16=(0.500,0.552)  ...  M31=(0.875,0.391)  M32=(0.881,0.380)
```

**对称性验证**：
- M0.y = M32.y = 0.380（上眼皮齐平）✓
- M1.y = M31.y = 0.391（对称）✓
- M16.x = 0.500（中心）✓
- 所有对称点 y 值相等 ✓

#### 3.5.4 与 GPUPixel C++ 源码对应关系

| 本项目 | GPUPixel 源码 | 说明 |
|--------|--------------|------|
| `FaceTextureCoordinates()` | `face_makeup_filter.cc:352` | 原始实现提供 111 点基准 UV 坐标 |
| `GetFaceIndexs()` | `face_makeup_filter.cc:299` | 原始实现的三角形网格索引（使用 0-110） |
| `FaceMakeupPass.FACE_TEXTURE_COORDS` | `face_makeup_filter.cc:352` | 当前仅截取前 106 点，避免在大美丽链路中补齐辅助点 |

> **注意**：GPUPixel 源码原始妆容网格包含 111 个点（索引 0-110），但 PicMe 当前大美丽链路只消费 106 点输入，不再通过均值法补齐 107-110 辅助点。

---

## 4. 当前实现快照（2026-04-11）

### 4.1 已落地能力

- [x] `BeautyPreviewView` 采用 `SurfaceView` 显示，显示 Surface 与 CameraX 输入 Surface 解耦。
- [x] `CameraPreviewRenderer` 在主线程完成 EGL + 外部纹理 + SurfaceTexture 初始化。
- [x] 渲染线程使用 `OnFrameAvailableListener + sleep(1)` 驱动，避免忙等。
- [x] 基础主 Shader + 多 Pass 管线覆盖磨皮（双边滤波近似）/美白/大眼/瘦脸/唇色（纹理妆容）/腮红，支持实时 uniform 更新。
- [x] 已实现人脸点位到 UV 坐标映射（`mapViewNormalizedToUv`），保证形变参数与纹理变换一致。
- [x] 已输出 `BeautyPerfStats(fps, processingMs, delayMs, cpuUsage, nullFrames, errorCategory, errorReason)` 供调试浮层展示。
- [x] `BeautyStrategy.GPUPIXEL` 实验性集成落地，`GpupixelBeautyPreviewProvider` 封装 GPUPixel C++ 滤镜链。
- [x] `BeautyPreviewEngine` 组合接口统一 `BeautyPreviewProvider` + `BeautyPreviewCapability`，App 层通过接口访问。
- [x] `BeautyStrategy`、`FaceDetectIntervalProfile` 等枚举迁移至 `domain.model.UserPreferences`，实现分层解耦。
- [x] **方案 B 变种**：`FaceDetectionCache` 实现预览/拍照人脸检测复用，减少效果差异。
- [x] **滤镜一致性修复**：`FilterType` 新增 `toAndroidColorMatrix()` 方法，修复 Compose ColorMatrix 与 Android ColorMatrix 类型不兼容导致的滤镜失效问题。

### 4.2 双引擎现状

#### 大美丽（BIG_BEAUTY）— 主引擎
- **触发条件**：`BeautyStrategy.BIG_BEAUTY`（默认值）
- **路由类**：`GlBeautyPreviewStrategy`
- **Provider**：`GlBeautyPreviewProvider` → `BeautyPreviewView` → `CameraPreviewRenderer`
- **人脸检测**：使用 `MediaPipeFaceDetector` 的 468→106 点结果构建 `FaceWarpParams`，再由 `CameraPreviewRenderer.mapViewNormalizedToUv()` 映射到纹理 UV
- **容灾**：warm-up 失败调用 `onGlWarmUpFallback(reason)` 上报，由 `CameraRuntimeState` 持久化

#### GPUPixel（GPUPIXEL）— 实验性备选
- **触发条件**：`BeautyStrategy.GPUPIXEL`（用户手动切换）
- **路由类**：`GpupixelBeautyPreviewStrategy`
- **Provider**：`GpupixelBeautyPreviewProvider` → GPUPixel C++ JNI（`TextureView` 显示）
- **人脸检测**：GPUPixel 内建 `FaceDetector` 驱动滤镜链；MediaPipe 结果仅在双模式下写入 `bigBeautyLandmarks` 供调试对照
- **当前限制**：
  - `createPreviewSurface()` 使用 TextureView（非 SurfaceView），功耗略高
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
    preferences[GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = now + cooldownMs
}

suspend fun triggerManualGlEngineRecovery() {
    preferences[GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = 0L
}
```

> 当前实现仅持久化冷却时间，不再写入任何已删除的旧兜底引擎状态。

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

## 8. 双引擎协作设计（2026-04）

> 本章系统性描述大美丽（BIG_BEAUTY）与 GPUPixel（GPUPIXEL）两个引擎的能力边界、数据流差异、
> 互补定位与协作最佳实践，是双引擎并存后的核心技术决策记录。

### 8.1 两引擎定位对比

| 维度 | 大美丽（BIG_BEAUTY） | GPUPixel（GPUPIXEL） |
|---|---|---|
| **技术栈** | 自研 OpenGL ES + EGL 渲染管线 | 开源 C++11/OpenGL ES（Apache 2.0） |
| **相机帧输入路径** | CameraX `Preview` UseCase → SurfaceTexture（OES 纹理，零拷贝） | CameraX `ImageAnalysis` → YUV → RGBA 转换 → 手动旋转 → GPUPixelSourceRawData |
| **预览显示 View** | `SurfaceView`（直接硬件合成，延迟低，功耗小） | `TextureView`（SDK 接口限制，GPU 合成路径） |
| **人脸检测** | MediaPipe 468→106 → `FaceWarpParams` → `mapViewNormalizedToUv()` | 内建 Mars 模型 FaceDetector（106 点，与滤镜链紧耦合） |
| **色调滤镜** | ✅ ColorMatrix → `colorMatrix` uniform 实时变换（OpenGL Shader 层） | ➖ 当前使用大美丽 ColorMatrix 路径；GPUPixel 3D LUT 路径为长期演进目标 |
| **美颜基础** | 双边滤波磨皮、ColorMatrix 美白、FaceWarp 瘦脸/大眼、Shader 唇色/腮红 | BeautyFaceFilter（磨皮/美白）、FaceReshapeFilter（瘦脸/大眼）、LipstickFilter、BlusherFilter |
| **专业调色** | ➖ 使用 CameraX CaptureRequest（有限） | ✅ ExposureFilter / ContrastFilter / SaturationFilter / WhiteBalanceFilter（Shader 级实时） |
| **风格特效** | ❌ 不支持 | ✅ ToonFilter / SmoothToonFilter / SketchFilter / PosterizeFilter / EmbossFilter / CrosshatchFilter |
| **参数接口** | `BeautyParams.colorMatrix`（大美丽专用字段） | `BeautyParams.gpuExposure/gpuContrast/gpuSaturation/gpuWhiteBalance/styleFilterClassName`（GPUPixel 专用字段） |
| **容灾入口** | `GlBeautyPreviewStrategy.onWarmUpFallback` → EGL warm-up 失败 | `GpupixelBeautyPreviewStrategy.onWarmUpFallback` → GPUPixel init 失败 |
| **引擎切换开销** | 需 release 旧 EGL 上下文、重建 SurfaceTexture | 需 release 所有 GPUPixel C++ 滤镜链对象 |

### 8.2 帧数据流对比

**大美丽（BIG_BEAUTY）**：
```
CameraX Preview UseCase
  └─ SurfaceRequest → Surface（来自 BeautyPreviewView.getSurfaceForCamera()）
       └─ SurfaceTexture.updateTexImage()   ← 零拷贝，OES 纹理直接传给 Shader
            └─ Beauty pass（主 Shader / 多 Pass 美颜）
                 ├─ 磨皮（双边滤波 9pt）
                 ├─ 美白（亮度 uniform）
                 ├─ 瘦脸/大眼（FaceWarp uniform）
                 ├─ FaceMakeupPass（唇色/腮红纹理妆容，按需启用）
                 └─ 色调矩阵（uColorMatrix uniform，ColorMatrix 4×5）
                      └─ WindowSurface.swapBuffers()
                           └─ SurfaceView（硬件合成显示）
```

**GPUPixel（GPUPIXEL）**：
```
CameraX ImageAnalysis UseCase（无 Preview UseCase）
  └─ ImageProxy（YUV_420_888）
       └─ GPUPixel.YUV_420_888toI420AndRGBA()
            ├─ Y/U/V DirectByteBuffer → SourceYUV.ProcessData()
            └─ RGBA DirectByteBuffer → FaceDetector.detect()
                 └─ 滤镜链（C++ GPU 处理）
                      ├─ LipstickFilter（blend_level + face_landmark）
                      ├─ BlusherFilter（blend_level + face_landmark）
                      ├─ BeautyFaceFilter（skin_smoothing / whiteness）
                      ├─ FaceReshapeFilter（thin_face / big_eye + face_landmark）
                      ├─ ExposureFilter / ContrastFilter / SaturationFilter / WhiteBalanceFilter
                      └─ [StyleFilter]（互斥切换，NONE 时直连）
                           └─ GPUPixelSinkSurface.SetSurface()
                                └─ TextureView（GPU 合成显示）
```

**关键差异**：
- 大美丽走 `Preview` UseCase，零拷贝直连 OES 纹理；GPUPixel 走 `ImageAnalysis`，存在 YUV→RGBA 格式转换与上层旋转的 CPU 开销
- 两引擎 **不可同时激活**：`CameraX` 绑定时 GPUPixel 模式会跳过 `Preview` UseCase，避免双 Surface 争抢

### 8.3 参数字段与引擎对应关系

`BeautyParams` 中各字段的引擎归属（`updateFilters()` 调用时各引擎只处理自己的字段）：

| `BeautyParams` 字段 | 大美丽处理 | GPUPixel 处理 | 备注 |
|---|---|---|---|
| `enabled` | ✅ 控制所有美颜开关 | ✅ 控制所有美颜开关 | 两引擎均响应 |
| `smoothing` | ✅ `uSmoothing` uniform | ✅ `BeautyFaceFilter.skin_smoothing` | 归一化映射不同 |
| `whitening` | ✅ `uWhitening` uniform | ✅ `BeautyFaceFilter.whiteness` | 归一化映射不同 |
| `bigEyes` | ✅ `uBigEyes` + FaceWarp | ✅ `FaceReshapeFilter.big_eye` | 人脸检测来源不同 |
| `slimFace` | ✅ `uSlimFace` + FaceWarp | ✅ `FaceReshapeFilter.thin_face` | 人脸检测来源不同 |
| `lipColor` | ✅ `FaceMakeupPass` 纹理妆容 + 主 Shader | ✅ `LipstickFilter.blend_level` | — |
| `lipColorIndex` | ✅ `uLipColorIndex` uniform | ❌ GPUPixel 不使用 | GPUPixel 内建口红颜色 |
| `blush` | ✅ `FaceMakeupPass` 纹理妆容 + 主 Shader | ✅ `BlusherFilter.blend_level` | — |
| `blushColorFamily` | ✅ `uBlushColorFamily` uniform | ❌ GPUPixel 不使用 | GPUPixel 内建腮红色系 |
| `colorMatrix` | ✅ `uCMRow0~3` + `uCMOffset` uniform | **静默忽略** | GPUPixel 调色走专用滤镜 |
| `gpuExposure` | **静默忽略** | ✅ `ExposureFilter.exposure` | 大美丽侧待 P1 实现 |
| `gpuContrast` | **静默忽略** | ✅ `ContrastFilter.contrast` | 大美丽侧待 P1 实现 |
| `gpuSaturation` | **静默忽略** | ✅ `SaturationFilter.saturation` | 大美丽侧待 P1 实现 |
| `gpuWhiteBalance` | **静默忽略** | ✅ `WhiteBalanceFilter.temperature` | 大美丽侧待 P1 实现 |
| `styleFilterClassName` | **静默忽略** | ✅ 动态滤镜链切换 | 大美丽侧无对应能力 |

> **设计原则**：各引擎只处理自己关心的字段，对不认识的字段静默忽略，不抛出异常。
> 这保证了 `BeautyParams` 作为统一参数容器的向后兼容性。

### 8.4 人脸检测协作方式

两引擎在人脸检测上采用完全不同的策略：

**大美丽路径（MediaPipe 主分析流）**：
```
ImageAnalysis Analyzer
  └─ MediaPipeFaceDetector.detect()（异步）
       └─ 468→106 映射
            └─ FaceWarpParams（View 归一化坐标）
                 └─ CameraPreviewRenderer.mapViewNormalizedToUv()
                      └─ BeautyPreviewView（Shader uniform 注入）
```
- MediaPipe 结果通过 `FaceWarpParams` 数据类跨线程传递
- 大眼/瘦脸变形需要精确的 landmark 坐标（眼球中心、嘴角、下颌等）
- 唇色/腮红通过 106 点轮廓与 `FaceMakeupPass` 三角网格共同驱动

**GPUPixel 路径（内建 FaceDetector）**：
```
onYuvFrame() / onRgbaFrame()
  └─ FaceDetector.detect()（内建 Mars 模型，106 点，同步）
       └─ face_landmark（float[]）
            ├─ faceReshapeFilter.SetProperty("face_landmark", landmarks)
            ├─ lipstickFilter.SetProperty("face_landmark", landmarks)
            └─ blusherFilter.SetProperty("face_landmark", landmarks)
```
- 每帧同步推理，GPUPixel 滤镜链自动处理 landmark 依赖
- `GpupixelBeautyPreviewStrategy.applyFaceWarpParams()` 为空实现；双模式下只额外保留 MediaPipe 的 `bigBeautyLandmarks` 供调试显示

**关键约束**：
- GPUPixel 模式下，MediaPipe 结果不参与 GPUPixel 滤镜参数下发，只用于双模式调试对照
- 如果后续补回 ML Kit 表情/状态分析流，必须与当前 MediaPipe / GPUPixel 主链路隔离，避免拖慢 `ImageAnalysis`

### 8.5 引擎切换时序

引擎切换由用户设置（`BeautyStrategy` DataStore）驱动，Composable 层通过 `remember(beautyStrategy)` 实现零竞态切换：

```
用户切换引擎设置
  └─ UserPreferencesRepository（DataStore）更新 beautyStrategy
       └─ CameraRuntimeContext.beautyStrategy（StateFlow）
            └─ rememberGlBeautyPreviewProvider(context, beautyStrategy)
                 ├─ remember(beautyStrategy) → 同帧立即创建新 Provider
                 └─ DisposableEffect(provider) → provider 被替换时异步 release 旧 Provider
                      └─ rememberPreviewStrategyBundle(beautyStrategy, previewView, glPreviewProvider)
                           ├─ remember(beautyStrategy, ...) → 同帧路由到新策略
                           └─ bindCameraUseCases（重新绑定 CameraX 用例）
```

**切换保证**：
- `remember(beautyStrategy)` 确保新 Provider 与新策略在同一 recomposition 帧内就位，避免类型不匹配强转崩溃
- `DisposableEffect` 保证旧 Provider 在从渲染路径移除后才执行 `release()`，无 EGL 资源双持
- GPUPixel 模式下 `bindCameraUseCases` 不创建 `Preview` UseCase，避免 Surface 冲突

### 8.6 两引擎最佳配合策略（推荐实践）

根据现有实现与两引擎的能力边界，推荐以下使用策略：

#### 策略一：大美丽作为稳定基础，GPUPixel 作为专业增强

| 场景 | 推荐引擎 | 原因 |
|---|---|---|
| 日常美颜（磨皮/美白/大眼/瘦脸） | **大美丽** | 主 Shader 为主，链路成熟；MediaPipe 468→106 映射精度更稳定 |
| 口红/腮红效果 | 两引擎效果相当 | 大美丽用 `FaceMakeupPass` 纹理妆容；GPUPixel 用 LipstickFilter/BlusherFilter |
| 专业调色（曝光/对比度/饱和度/色温） | **GPUPixel** | 独立 Shader 滤镜，调色精准可控；大美丽侧待 P1 实现 |
| 风格特效（卡通/素描/浮雕等） | **GPUPixel** | 大美丽侧无对应能力 |
| 色调滤镜（徕卡/胶片等） | **大美丽**（当前） | ColorMatrix uniform 直接在 Shader 层实时变换，零额外 pass |
| 低端机稳定性 | **大美丽** | 经过更多压测验证；GPUPixel C++ 层在特定机型有潜在稳定性风险 |

#### 策略二：共用 `BeautyParams` 协议，互不感知对方实现

- App 层只向 `BeautyPreviewEngine` 发送统一的 `BeautyParams`，无需感知底层是哪个引擎
- 各引擎实现 `updateFilters()` 时按字段归属处理，未知字段静默忽略
- `BeautyParamsConverter.toBeautyParams()` 在 app 层完成所有映射，引擎不参与映射逻辑

#### 策略三：长期融合路线

```
近期（P1）
  大美丽补齐专业调色：在 FRAGMENT_SHADER_BEAUTY 追加 ExposureFilter / ContrastFilter 等
  → 实现：gpuExposure/gpuContrast/gpuSaturation/gpuWhiteBalance 四个 uniform 映射

中期（P2）
  大美丽磨皮升级为引导滤波（Guided Filter）
  → 与 GPUPixel 磨皮算法路线对齐（GPUPixel 项目已验证可行性）

中期（P2）
  色调滤镜升级为 3D LUT
  → 两引擎均可受益，支持专业调色预设动态扩展

长期（P3）
  评估 GPUPixel 滤镜链能力替换/增强大美丽 Shader 管线
  → 以大美丽 API 层为门面，底层逐步引入更成熟的 GPUPixel 滤镜
```

### 8.7 当前已知限制与待办

| 限制 / 已知问题 | 影响 | 建议解法 |
|---|---|---|
| GPUPixel 存在 YUV→RGBA 格式转换 CPU 开销 | 中端机约 3~5ms/帧额外开销 | 评估 OpenGL ES PBO 或 libyuv SIMD 加速 |
| GPUPixel 上层手动旋转 RGBA 为 CPU 操作 | 90°/270° 每帧需要完整像素复制 | 评估 Shader 旋转或利用 SetRotation 修复根因 |
| 大美丽缺少专业调色滤镜（P1） | 用户在大美丽模式下无法使用曝光/对比度 | P1 在 Fragment Shader 追加调色 uniform |
| 大美丽缺少风格特效（无计划） | 大美丽模式下风格特效 UI 置灰 | 维持现状；风格特效作为 GPUPixel 特有能力 |
| 两引擎 lipColorIndex / blushColorFamily 行为不一致 | GPUPixel 内建色彩，不支持按 index 切换 | 待评估是否统一 ColorMap 传入 GPUPixel |
---

## 9. 风险与应对

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

## 10. 相关文档与实现入口

- `PRODUCT.md` — 产品需求规格说明书（大美丽 产品策略）
- `FEATURES.md` — 功能交互规范（重点：`1.3.5` 大美丽 性能与验收）
- `AGENTS.md` — AI Agent 操作规范
- `CAMERA_PREVIEW_TECH_SPEC.md` — 相机预览与坐标系统规范
- `BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md` — 大美丽 QA 独立执行清单
- `beauty-engine/src/main/java/com/picme/beauty/api/` — 对外稳定 API（`BeautyParams`、`BeautyPreviewProvider`、`BeautyPreviewCapability`、`BeautyPreviewEngine`）
- `beauty-engine/src/main/java/com/picme/beauty/egl/` — GL 渲染管线核心实现
- `beauty-engine/src/main/java/com/picme/beauty/gpupixel/` — GPUPixel 实验性集成
- `app/src/main/java/com/picme/features/camera/CameraScreen.kt` — 预览绑定、容灾回退与调试浮层
- `app/src/main/java/com/picme/features/camera/CameraPreviewStrategies.kt` — 引擎策略路由

---

## 附录 A：正向映射与反向映射（图像变形核心概念）

### A.1 概述

在图像变形（如瘦脸、大眼）实现中，**映射方向**是决定效果正确性的核心概念。大美丽引擎同时涉及两种映射方式：

- **预览（Shader）**：反向映射（Backward Mapping）
- **拍照（CPU）**：正向映射（Forward Mapping）

### A.2 正向映射（Forward Mapping）

**定义**：从源图像的像素/顶点出发，计算它在目标图像中的新位置。

```
源图像                    目标图像
┌─────┐                  ┌─────┐
│  A  │ ──映射计算──→    │  A' │
│  B  │ ──映射计算──→    │  B' │
└─────┘                  └─────┘
```

**特点**：
- 直接移动源像素/顶点到新位置
- 可能出现"空洞"（某些目标位置没有源像素映射过来）
- CPU `drawBitmapMesh` 使用此方式

**代码示例**（`GpuBeautyProcessor.kt` 瘦脸）：
```kotlin
// 遍历源图像的每个顶点
for (i in 0 until count) {
    val vx = orig[i * 2 + 0]  // 源顶点 X（像素坐标）
    val vy = orig[i * 2 + 1]  // 源顶点 Y（像素坐标）
    
    // 计算变形后的新位置
    val newX = vx + offsetX   // 正向：源位置 + 偏移 = 新位置
    val newY = vy + offsetY
    
    verts[i * 2 + 0] = newX
    verts[i * 2 + 1] = newY
}
```

### A.3 反向映射（Backward Mapping）

**定义**：从目标图像的像素出发，反向查找它在源图像中的对应位置。

```
源图像                    目标图像
┌─────┐                  ┌─────┐
│  A  │ ←──反向查找──    │  A' │
│  B  │ ←──反向查找──    │  B' │
└─────┘                  └─────┘
```

**特点**：
- 遍历目标图像的每个像素
- 不会出现空洞（每个目标像素都有来源）
- GPU Shader 使用此方式（更适合并行）

**代码示例**（`warp.glsl` 瘦脸）：
```glsl
// 遍历目标图像的每个像素（通过纹理坐标 uv）
vec2 applySlimFace(vec2 uv, vec2 center, float radius, float intensity) {
    vec2 dir = uv - center;           // 从中心指向当前像素（UV单位）
    float dist = length(dir);
    if (dist >= radius) return uv;
    
    vec2 eyeAxis = normalize(uRightEye - uLeftEye);
    float percent = 1.0 - dist / radius;
    float strength = intensity * percent * percent * 0.45;
    float axisOffset = dot(dir, eyeAxis) / max(radius, 0.0001);
    vec2 offset = eyeAxis * axisOffset * strength * radius;
    
    return uv - offset;  // 反向：目标位置 - 偏移 = 源位置
}
```

### A.4 关键差异：坐标系

| 特性 | Shader UV | CPU 像素 |
|------|-----------|----------|
| **范围** | 0.0 ~ 1.0 | 0 ~ width/height |
| **单位** | 比例（归一化） | 像素 |
| **原点** | 左上角 | 左上角 |
| **Y轴方向** | 向下递增 | 向下递增 |

### A.5 瘦脸效果中的符号差异

**核心问题**：相同的数学公式，因映射方向不同，需要相反的符号。

#### 瘦脸的数学本质
瘦脸 = 将脸部像素向眼轴方向（水平）收缩

#### 正向映射（CPU）
```kotlin
// 源顶点向眼轴方向移动（脸部变窄）
verts[i] += eyeAxis * offset  // 正确：瘦脸
verts[i] -= eyeAxis * offset  // 错误：丰脸
```

#### 反向映射（Shader）
```glsl
// 目标像素从内侧采样（脸部变窄）
return uv - offset;  // 正确：瘦脸
return uv + offset;  // 错误：丰脸
```

### A.6 符号对照表

| 效果 | 正向映射（CPU） | 反向映射（Shader） |
|------|----------------|-------------------|
| **瘦脸**（向中心收缩） | `+` | `-` |
| **丰脸**（向外扩展） | `-` | `+` |

**记忆口诀**：**正向加，反向减；方向相反效果同。**

### A.7 实际修复案例

**问题**：大美丽瘦脸预览与拍照效果相反
- 预览（Shader）：正强度 → 变胖
- 拍照（CPU）：正强度 → 变瘦

**修复**（`GpuBeautyProcessor.kt`）：
```kotlin
// 修复前（与 Shader 一致，但效果相反）
verts[i * 2 + 0] -= eyeAxisX * axisOffset * str * slimRadius
verts[i * 2 + 1] -= eyeAxisY * axisOffset * str * slimRadius

// 修复后（符号反转，效果一致）
verts[i * 2 + 0] += eyeAxisX * axisOffset * str * slimRadius
verts[i * 2 + 1] += eyeAxisY * axisOffset * str * slimRadius
```

### A.8 调试技巧

1. **小幅度测试**：先用 0.1 的小强度测试方向
2. **可视化偏移**：用颜色表示偏移方向（红色=正，蓝色=负）
3. **坐标打印**：在关键位置打印坐标，对比 Shader 和 CPU 结果

### A.9 相关文件

- `app/src/main/java/com/picme/core/image/GpuBeautyProcessor.kt` - 正向映射实现
- `beauty-engine/src/main/assets/shaders/warp.glsl` - 反向映射实现

---

## 11. 总结

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
| 磨皮算法 | 双边滤波快速近似（9pt） | 保边效果好，移动端性能可接受 |
| 显示层 | SurfaceView | 直接硬件合成，延迟更低，功耗更小 |
| 人脸检测 | MediaPipe 468→106（主链路） | 与当前预览分析流一致，直接服务 `FaceWarpParams` 与双模式调试 |
| 渲染参数 | 全 uniform 实时更新 | 无需重新编译 Shader，延迟 < 100ms |

**预期结果**：

- 成功：实现 30-60fps 实时美颜预览，零授权成本，用户可无感知享受效果
- 降级：大美丽 warm-up 失败进入冷却，3 分钟后自动重试；用户可手动切换 GPUPixel 实验性路径

