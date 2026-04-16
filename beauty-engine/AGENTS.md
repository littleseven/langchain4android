# Beauty Engine 模块技术实现规范 (Beauty Engine Technical Implementation)

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `beauty-engine` 独立库模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 大美丽 渲染链路、容灾回退、冷却恢复与观测指标：见 `docs/BIG_BEAUTY_TECH_SPEC.md`。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：`beauty-engine` 是 PicMe 美颜引擎的独立 Android Library 模块，承载 OpenGL ES + EGL 渲染管线（大美丽主引擎）和 GPUPixel 实验性集成，对外暴露稳定 API，对内封装 GPU 加速实现。长期演进为可独立发布的视觉能力基础库。

**主要维护者**：[RD] 全栈工程师

**阅读对象**：CO、PM、RD、CR、QA、AI Agent

---

## 1. 核心产品逻辑 (Core Product Logic)

- **[PERF] 零拷贝数据流**：CameraX → SurfaceTexture → OpenGL ES → SurfaceView，全流程 GPU 内完成，禁止 CPU 回读
- **[PERF] 单帧处理 ≤ 16ms**：目标 60fps；低端机保底 30fps
- **[PERF] 参数响应延迟 < 100ms**：美颜参数通过 `uniform` 实时传递，无需重新编译 Shader
- **[PRIVACY] 本地渲染**：所有图像处理在设备本地完成，严禁上传任何图像数据到云端
- **[STABILITY] 容灾降级**：引擎初始化失败或运行异常时，通过 `BeautyPreviewProvider` 向 App 层报告。详细的兜底策略与状态记录机制请参阅 `docs/BEAUTY_ENGINE_FALLBACK.md`
- **[API_STABILITY] 库化演进**：App 仅依赖 `api/` 包下的能力契约，禁止直接引用 `egl/` 内部实现类
- **[INTEGRATION] 双引擎架构**：当前保留自研 `beauty-engine`（BIG_BEAUTY）主引擎与 GPUPixel（GPUPIXEL）实验性备选；PixelFree 已于 2026-04 完全移除
- **[ROADMAP] GPUPixel 集成状态**：[GPUPixel](https://github.com/pixpark/gpupixel)（Apache 2.0、纯 C++11/OpenGL ES、无商业 SDK 绑定）已完成实验性集成，`GpupixelBeautyPreviewProvider` 实现 `BeautyPreviewEngine` 接口；CainCamera 因硬依赖 Face++ 商业 SDK 且已停止维护，已被排除

---

## 2. 技术实现规范 (Technical Implementation)

### 2.1 模块包结构规范

```
beauty-engine/src/main/java/com/picme/beauty/
├── api/                               # 对外稳定 API（能力契约层）
│   ├── BeautyParams.kt                # 美颜参数数据类
│   ├── BeautyPerfStats.kt             # 性能统计模型
│   ├── BeautyPreviewCapability.kt     # GL 能力扩展接口（FaceWarp/LipMask 等）
│   ├── BeautyPreviewProvider.kt       # 预览 Provider 基础接口
│   ├── BeautyPreviewEngine.kt         # 组合接口（Provider + Capability + getView）
│   └── BeautyPreviewProviderFactory.kt # Factory（未来 DI 扩展点）
├── egl/                               # 内部实现（GL 渲染管线层）
│   ├── BeautyPreviewView.kt           # 自定义 View（SurfaceView 封装）
│   ├── CameraPreviewRenderer.kt       # 渲染管线核心
│   ├── BeautyRenderer.kt              # 美颜 Shader 渲染器
│   ├── BeautyShaders.kt               # GLSL Shader 源码
│   ├── ShaderProgram.kt               # Shader 编译与链接
│   ├── EGLCore.kt                     # EGL 上下文与 Surface 管理
│   ├── WindowSurface.kt               # EGL Window Surface 封装
│   ├── GLRenderer.kt                  # GL 渲染通用基类
│   ├── GlBeautyPreviewProvider.kt     # Provider 接口实现（内部适配器）
│   └── GlBeautyPreviewProviderFactory.kt # GL Provider 工厂
└── gpupixel/                          # GPUPixel 实验性集成层（🧪）
    └── GpupixelBeautyPreviewProvider.kt # GPUPixel 引擎 Provider 实现
```

**依赖方向红线**：
- `api/` 包：**禁止**依赖 `egl/`、`gpupixel/`、`androidx.camera.*`、`features.*`、`data.*` 等任何实现细节
- `egl/` 包：允许实现 `api/` 接口，允许依赖 `android.*` 和 OpenGL ES 相关库
- `gpupixel/` 包：允许实现 `api/` 接口，允许依赖 GPUPixel JNI 库；**禁止**从 `egl/` 反向依赖
- App 层：只允许依赖 `beauty-engine` 的 `api/` 接口，禁止直接实例化 `egl/` 或 `gpupixel/` 内部类

### 2.2 对外 API 层 (`api/`)

#### BeautyParams
- 所有参数使用 `Float` 类型，范围统一归一化到 `0.0f ~ 1.0f`
- UI 层原始范围映射由 App 层完成，引擎内部只做归一化值处理
- 新增参数必须提供默认值 `0.0f`，保证向后兼容

#### BeautyPreviewProvider
- 接口设计遵循"一次性初始化 + 可重复绑定"原则（当前 Phase 3 库化 API）：
  - `initialize()` — 离屏初始化（EGL + Shader 编译）
  - `createPreviewSurface(): Surface` — 创建并返回给 CameraX 的输入 Surface
  - `updateFilters(params: BeautyParams)` — 实时更新美颜滤镜参数
  - `release()` — 资源释放
  - `isReady(): Boolean` — 判断引擎是否已就绪
  - `getPerfStats(): BeautyPerfStats` — 获取实时性能统计（默认返回 `EMPTY`）
- 初始化异常应通过抛出异常或状态查询供 App 层触发兜底。详见 `docs/BEAUTY_ENGINE_FALLBACK.md`

#### 未来接口扩展（ML Kit 增强）
- **FaceWarpParams 增强**：Phase 2 引入 `faceMeshPoints: List<Offset>` 字段，支持 468 点密集网格驱动精细美型和妆容贴合。
- **SegmentationMask 支持**：Phase 2-3 引入 `segmentationMaskTexture: Int` 字段，允许 Shader 通过 `sampler2D` 读取人像前景 Mask，实现背景虚化和美体边缘保护。
- **约束**：以上扩展必须通过 `updateFilters(params: BeautyParams)` 的扩展重载或新增接口实现，保持现有调用方二进制兼容。

#### BeautyPerfStats
- 统一输出字段：`fps`, `processingMs`, `delayMs`, `cpuUsage`, `nullFrames`
- 数据由 `egl/` 渲染线程每秒聚合一次，通过回调或状态流暴露给 App 层调试浮层

### 2.3 内部实现层 (`egl/`)

#### CameraPreviewRenderer
- **初始化阶段**：在主线程调用 `init()`，完成 EGL 上下文创建、外部纹理生成、SurfaceTexture 创建、Shader 编译
- **绑定阶段**：在 `setRenderSurface(surface)` 中启动独立渲染线程
- **渲染线程**：使用 `OnFrameAvailableListener + sleep(1)` 驱动，无帧时避免忙等
- **上下文共享**：主线程创建共享上下文，渲染线程创建基于共享上下文的独立上下文
- **禁止**：在构造函数中启动渲染线程；禁止在多个线程同时调用 `eglMakeCurrent`

#### BeautyPreviewView
- 继承 `FrameLayout`，内部托管 `SurfaceView`
- 输入 Surface（给 CameraX）与显示 Surface（SurfaceView）必须解耦
- `getSurfaceForCamera()` 延迟创建并缓存 `cameraSurface`，避免重复 new Surface
- `setDefaultBufferSize(width, height)` 由 CameraX 输入分辨率动态设置（默认 1280x720）

#### BeautyRenderer / BeautyShaders

**美颜算法实现规范**：

- **磨皮 (Smoothing)**：
  - **算法选择**：采用双边滤波（Bilateral Filter）快速近似，通过 9 点采样结合值域高斯权重，在平滑皮肤的同时保留边缘细节
  - **实现要点**：在 Fragment Shader 内以当前像素为中心，在 3×3 邻域内采样；根据邻域像素与中心像素的亮度差异计算值域权重 exp(-(ΔLuma)² / 2σ_r²)，并结合空间距离权重 exp(-dist² / 2σ_s²)；仅对皮肤蒙版（skinMask）区域生效
  - **参数映射**：uSmoothing (0.0~1.0) 同时控制磨皮强度与值域 σ_r 的宽度；uTexelSize 由 CameraPreviewRenderer 根据输入分辨率实时传入，确保跨分辨率下采样半径一致
  - **性能优化**：单 Pass 内完成，无需额外 FBO；共 9 次 texture2D 采样，空间 σ_s=1.8 像素，值域 σ_r=0.10~0.18，兼顾效果自然度与移动端实时性
  - **演进路线**：双边滤波（当前）→ **引导滤波 Guided Filter**（O(N) 闭式解，无梯度反转光晕，更适合移动端）→ 多尺度细节分层（工业级效果）

- **美白 (Whitening)**：
  - **色彩空间**：在 YUV 或 Lab 色彩空间调整亮度 (L) 和色度 (U/V)
  - **实现要点**：智能识别肤色区域，避免全图过曝
  - **参数映射**：UI 参数 → ΔL (亮度提升) 和 ΔU/ΔV (色度调整)

- **瘦脸 (Slim Face)**：
  - **人脸检测**：使用 ML Kit Face Detection API 获取 68/106 个 landmarks
  - **变形算法**：基于 Delaunay 三角剖分的网格变形 (Mesh Warping)
  - **实现要点**：以下颌角为中心点，向内收缩 5%-30%
  - **安全约束**：变形幅度限制在 30% 以内，保持面部比例协调

- **大眼 (Big Eyes)**：
  - **眼睛定位**：基于眼球中心点和眼眶轮廓
  - **放大算法**：径向变换 (Radial Transformation)，中心放大率最大
  - **实现要点**：保持眼神光不丢失，眼睑自然跟随
  - **安全约束**：放大比例不超过 1.3x

- **唇色 (Lip Color)**：
  - **唇部识别**：使用语义分割模型 (如 DeepLabV3) 提取唇部区域
  - **上色算法**：在 HSV 空间调整色相 (H) 和饱和度 (S)，保留明暗 (V)
  - **色号管理**：预设 12 种色号的 HSV 值
  - **实现要点**：保留唇部纹理，边缘自然过渡

- **腮红 (Blush)**：
  - **区域定位**：以眼-嘴轴构建双颊椭圆，中心必须向外、向上偏移
  - **抑制规则**：鼻梁中线与嘴部上方应设置抑制带，防止染色贴鼻贴嘴
  - **防重叠**：左右腮红在中脸区域不得明显交叠

- **身材调整 (Body Enhancement)**：
  - **人体检测**：使用 MediaPipe Pose 或 ML Kit Pose Detection 获取关键点
  - **丰胸算法**：以上半身关键点为基准，局部径向扩展
  - **长腿算法**：以下半身关键点为基准，纵向拉伸 + 透视校正
  - **安全约束**：调整幅度限制在 20% 以内，保持身体比例

**Shader 工程规范**（与实际代码对齐）：
- **当前实现**：单 Pass Shader（`FRAGMENT_SHADER_BEAUTY`）在一次 draw call 内完成磨皮/美白/大眼/瘦脸/唇色/腮红，无多 Pass FBO 切换
- **磨皮算法**：双边滤波快速近似（9 点采样 + 值域高斯权重），**非** Box Blur；早期文档中“盒式模糊”的描述是规划期草案，与当前实现不符，已纠正。演进路线：双边滤波 → 引导滤波（Phase 2）→ 多尺度分层（Phase 3）
- **参数传递**：通过 `glUniform1f`/`glUniform2f`/`glUniform1i` 实时更新，禁止在参数变化时重新编译 Shader
- **纹理类型**：相机输入使用 `GL_TEXTURE_EXTERNAL_OES`，调试 Shader 使用普通 `GL_TEXTURE_2D`
- **调试 Shader**：`FRAGMENT_SHADER_DEBUG_RED`（全红）、`FRAGMENT_SHADER_DEBUG_TEXTURE_R`（R 通道灰度）供渲染链路验证使用

**GPU 加速策略**：
- **优先使用 GPU**：所有图像处理方法必须使用 GPU 加速 (OpenGL ES / Vulkan)
- **内存管理**：避免频繁的 CPU-GPU 数据传输，使用 FBO (Framebuffer Object)
- **延迟控制**：单帧处理时间 < 16ms (60fps) 或 < 33ms (30fps)

#### EGLCore / WindowSurface
- `EGLCore` 负责：Display 连接、配置选择、上下文创建、Surface 创建与切换
- `createContext(shareContext)` 支持共享上下文；默认创建时自动共享纹理资源
- `WindowSurface` 封装 EGL Surface 与 `swapBuffers()` 调用
- 释放顺序：先释放 Surface，再释放 Context，最后终止 Display

### 2.4 零拷贝数据流

#### 大美丽 (BIG_BEAUTY) 路径

```
CameraX 预览帧
    ↓ (无拷贝)
SurfaceTexture.updateTexImage()
    ↓ (GPU 纹理 OES)
OpenGL ES Shader 处理（磨皮/美白/大眼/瘦脸）
    ↓ (普通 2D 纹理)
WindowSurface.swapBuffers()
    ↓ (直接显示)
SurfaceView Surface
```

**关键约束**：
- ❌ 禁止 `glReadPixels` 将图像读回 CPU
- ❌ 禁止多次纹理上传/下载
- ✅ 全流程在 GPU 内完成

#### GPUPixel 路径（2026-04 优化后）

```
CameraX ImageAnalysis (YUV_420_888)
    ↓
GPUPixel.YUV_420_888toI420AndRGBA()
    ├── Native 层 ExtractI420Planes (YUV → 标准 I420，第 1 次 copy)
    ├── libyuv::I420Rotate (I420 旋转，第 2 次 copy)
    ├── memcpy (rotated I420 → Y/U/V DirectByteBuffer，第 3 次 copy)
    └── libyuv::I420ToABGR (I420 → RGBA DirectByteBuffer，第 4 次 copy)
    ↓
GpupixelBeautyPreviewProvider.onYuvFrame()
    ├── 人脸检测：rgbaBuffer → FaceDetector.detect() (DirectByteBuffer 零拷贝)
    └── 渲染：yBuffer/uBuffer/vBuffer → SourceYUV.ProcessData() (DirectByteBuffer 零拷贝)
        ↓
    GPU Shader 实时 YUV→RGBA 转换 → GPUPixelSinkSurface → TextureView
```

**关键优化点**：
- `ExtractI420Planes` 针对 `pixel_stride == 1` 的 CameraX 输出走 `memcpy` 快路径，避免逐像素循环
- JNI 层使用 `DirectByteBuffer` + `GetDirectBufferAddress`，消除 `byte[]` 的潜在拷贝
- 渲染链路使用 `SourceYUV` GPU Shader 实时 YUV→RGBA 转换，无需在 CPU 侧预转 RGBA
- 人脸检测使用 RGBA `ByteBuffer` 路径；`mars-face-kit` 对 `YUV_I420` 支持不完善且会污染检测器内部状态，**严禁传入 YUV_I420**

**当前链路严格 copy 次数**：**4 次**（工程口径）

### 2.5 性能监控与告警

```kotlin
// 渲染线程内每秒聚合
if (fps < 25 || processingMs > 20) {
    Log.w("PicMe:BeautyEngine", "Performance warning: fps=$fps, processing=${processingMs}ms")
}
```

- `fps`：每秒帧计数
- `processingMs`：单帧 `updateTexImage()` 到 `swapBuffers()` 的耗时
- `nullFrames`：连续无帧计数，异常波动时触发调试日志

---

## 3. Agent 执行规约 (Execution Rules)

### 3.0 团队协作运行模式
- **角色流转**：同一会话内按 `CO -> PM -> RD -> CR -> QA` 串行执行。
- **触发口令**：`自动执行`（默认 AUTO_MAX 自动推进）、`保守执行`（关键节点确认）。
- **自愈预算**：RD 单任务最多自愈 2 次，超限必须上报并提供备选方案。
- **红线暂停**：仅在隐私风险、不可逆操作或缺失外部输入时请求确认。

### 3.1 编码规范

- **缩进**：Kotlin 4 空格
- **Lambda 规范**：显式命名参数，禁止隐式 `it`
- **导入规范**：禁止通配符导入（`*`）
- **日志标签**：统一使用 `PicMe:BeautyEngine`
- **异常处理**：EGL/GL 操作必须包在 `runCatching` 中，失败时向上层返回 `Result.failure`

### 3.2 API 变更约束

- `api/` 包内任何公开接口的变更（新增/修改/删除）必须同步：
  1. 更新本 AGENTS.md 的接口说明
  2. 更新 `docs/BIG_BEAUTY_TECH_SPEC.md` 的库化章节
  3. 通知 App 层适配（features/camera 等调用方）
- 优先使用扩展函数或新增重载，避免破坏现有接口二进制兼容

### 3.3 资源生命周期

- `BeautyPreviewProvider.release()` 必须释放：
  - WindowSurface
  - EGL Surface / Context
  - SurfaceTexture
  - OpenGL 纹理与 Shader Program
  - 渲染线程（安全中断并 join）
- 使用 `WeakReference` 持有外部回调，避免内存泄漏

### 3.4 Shader 开发规范

- 所有 GLSL 源码集中放在 `BeautyShaders.kt`
- Shader 必须声明 `precision mediump float;`
- 外部纹理 Shader 必须包含 `#extension GL_OES_EGL_image_external : require`
- 新增 Shader 必须附带性能注释（复杂度、采样次数、适用机型）

---

## 4. 常见陷阱检查清单 (Checklist)

### 4.1 架构与接口
- [ ] `api/` 包是否引入了 `egl/` 或 CameraX 的实现依赖？（严禁反向依赖）
- [ ] App 层是否直接实例化了 `egl/` 内部类？（应通过 Factory 或 DI 获取接口实现）
- [ ] 新增公开 API 是否补充了默认值与向后兼容处理？

### 4.2 EGL / OpenGL
- [ ] 渲染线程是否在 `setRenderSurface()` 之后才启动？（禁止构造函数启动）
- [ ] 是否避免了多线程同时 `eglMakeCurrent`？（串行化或线程隔离）
- [ ] EGL 上下文是否通过共享上下文机制创建？（主线程 init，渲染线程 render）
- [ ] 释放顺序是否正确？（Surface → Context → Display）
- [ ] `glReadPixels` 是否被误用？（破坏零拷贝原则）

### 4.3 性能与稳定性
- [ ] 单帧处理耗时是否 ≤ 16ms？（高端机目标）/ ≤ 33ms？（低端机保底）
- [ ] 无帧时是否使用 `sleep(1)` 避免忙等？
- [ ] 参数变化时是否仅更新 `uniform` 而未重新编译 Shader？
- [ ] `nullFrames` 异常波动是否增加了日志或告警？
- [ ] 初始化失败是否返回了明确的 `Result.failure` 供 App 层兜底？

### 4.4 资源管理
- [ ] `release()` 是否完整释放了 EGL / GL / Surface / Thread 资源？
- [ ] SurfaceTexture 是否在释放前 detach 了 GL 上下文？
- [ ] 回调是否使用了 `WeakReference` 避免内存泄漏？

### 4.5 算法效果与约束
- [ ] 瘦脸/大眼是否限制在安全范围内？（避免失真）
- [ ] 唇色是否保留唇部纹理？（避免塑料感）
- [ ] 身材调整是否保持身体比例？（避免变形）
- [ ] 磨皮是否保留边缘细节？（双边滤波或表面模糊）
- [ ] 美白是否避免全图过曝？（仅提升肤色区域亮度）

### 4.6 代码风格
- [ ] 日志是否使用了 `PicMe:BeautyEngine` 标签？
- [ ] 是否避免了通配符导入？
- [ ] Lambda 参数是否显式命名？
- [ ] Shader 源码是否集中管理并带有性能注释？

### 4.7 GPUPixel 集成专项

#### ⚠️ SetRotation 参数类型陷阱（已踩坑，2026-04）
**症状**：切换到 GPUPixel 模式后 App 立即崩溃，`SIGTRAP (Fatal signal 5)`，
崩溃栈顶为 `gpupixel::Filter::GetTextureCoordinate(RotationMode const&)`。

**根因**：
- `GPUPixelSourceRawData.SetRotation(int rotation)` 的 JNI 实现为 `(*ptr)->SetRotation((RotationMode)rotation)`
- `RotationMode` 是 C++ 枚举，合法值范围 `0~7`（见下表）
- 直接传入 CameraX 的 `rotationDegrees`（如 90 / 270）会导致枚举越界
- `GetTextureCoordinate` 用越界枚举值做数组下标 → `SIGTRAP`

**RotationMode 枚举映射表**（`gpupixel/include/gpupixel/sink/sink.h`）：
| 枚举名 | 数值 | 对应 CameraX rotationDegrees |
|---|---|---|
| `NoRotation` | 0 | 0° |
| `RotateLeft` | 1 | 270° |
| `RotateRight` | 2 | 90° |
| `FlipVertical` | 3 | — |
| `FlipHorizontal` | 4 | — |
| `RotateRightFlipVertical` | 5 | — |
| `RotateRightFlipHorizontal` | 6 | — |
| `Rotate180` | 7 | 180° |

**正确用法**（`GpupixelBeautyPreviewProvider.kt`）：
```kotlin
val rotationMode = when (rotationDegrees) {
    90  -> 2  // RotateRight
    180 -> 7  // Rotate180
    270 -> 1  // RotateLeft
    else -> 0 // NoRotation
}
sourceRawData?.SetRotation(rotationMode)
```

- [ ] 调用 `GPUPixelSourceRawData.SetRotation()` 时是否已将角度值映射为 `RotationMode` 枚举序号？（**禁止直接传入 90/270**）

#### CameraX Preview UseCase 与 GPUPixel 冲突
- [ ] GPUPixel 模式下是否避免创建 CameraX `Preview` UseCase？（`Preview` 占用 Surface 会导致 `ImageAnalysis` 无帧，引发黑屏）
- [ ] GPUPixel 模式下帧数据是否通过 `ImageAnalysis → onYuvFrame()` 路径传递，而非 `Preview.SurfaceProvider`？
- [ ] `CameraUseCasesBinder.kt` 中 GPUPixel 模式是否仅绑定 `imageCapture + imageAnalysis`？

#### GPUPixel YUV 零拷贝渲染约束
- [ ] 渲染链路是否使用 `SourceYUV` 而非 `GPUPixelSourceRawData`？（YUV 直通避免 CPU 侧 RGBA 转换）
- [ ] `yBuffer/uBuffer/vBuffer` 是否为 `DirectByteBuffer`？（确保 `GetDirectBufferAddress` 零拷贝）
- [ ] 人脸检测是否仅使用 `RGBA` 格式传入 `FaceDetector`？（`YUV_I420` 会导致 `mars-face-kit` 检测失效并污染状态）

#### GPUPixel 已接入能力清单（2026-04）

| 能力 | GPUPixel 滤镜类 | 参数 Key | 接入状态 |
|---|---|---|---|
| 磨皮 | `BeautyFaceFilter` | `skin_smoothing` [-1,1] | ✅ 已接入 |
| 美白 | `BeautyFaceFilter` | `whiteness` [-1,1] | ✅ 已接入 |
| 瘦脸 | `FaceReshapeFilter` | `thin_face` [0,0.15] + `face_landmark` | ✅ 已接入 |
| 大眼 | `FaceReshapeFilter` | `big_eye` [0,0.5] + `face_landmark` | ✅ 已接入 |
| 唇色 | `LipstickFilter` | `blend_level` [0,1] + `face_landmark` | ✅ 已接入 |
| 腮红 | `BlusherFilter` | `blend_level` [0,1] + `face_landmark` | ✅ 已接入 |
| 专业曝光 | `ExposureFilter` | `exposure` [-10,10] | ✅ 已接入 |
| 专业对比度 | `ContrastFilter` | `contrast` [0,4] | ✅ 已接入 |
| 专业饱和度 | `SaturationFilter` | `saturation` [0,2] | ✅ 已接入 |
| 专业色温 | `WhiteBalanceFilter` | `temperature` [2000,10000K] | ✅ 已接入 |
| 卡通风格 | `ToonFilter` | `threshold`, `quantizationLevels` | ✅ 已接入（P2 本期） |
| 平滑卡通 | `SmoothToonFilter` | `blurRadius`, `threshold`, `quantizationLevels` | ✅ 已接入（P2 本期） |
| 素描风格 | `SketchFilter` | `edgeStrength` | ✅ 已接入（P2 本期） |
| 色块化 | `PosterizeFilter` | `colorLevels` | ✅ 已接入（P2 本期） |
| 浮雕效果 | `EmbossFilter` | `intensity` | ✅ 已接入（P2 本期） |
| 交叉线 | `CrosshatchFilter` | `crossHatchSpacing`, `lineWidth` | ✅ 已接入（P2 本期） |
| 人脸关键点 | `FaceDetector`（内置 Mars 模型，106 点） | `detect()` → `float[]` | ✅ 已接入 |

**滤镜链拓扑（当前）**：
```
GPUPixelSourceRawData
  → LipstickFilter    (blend_level, face_landmark)
  → BlusherFilter     (blend_level, face_landmark)   ← 本期新增
  → BeautyFaceFilter  (skin_smoothing, whiteness)
  → FaceReshapeFilter (thin_face, big_eye, face_landmark)
  → GPUPixelSinkSurface
```

#### GPUPixel 腮红（BlusherFilter）接入规范

- **滤镜类**：`GPUPixelFilter.BLUSHER_FILTER`（= `"BlusherFilter"`）
- **内置资源**：C++ 层自动加载 `assets/blusher.png` 作为腮红 mask 纹理（与 `LipstickFilter` 加载 `mouth.png` 同机制）
- **参数**：
  - `blend_level`：强度 [0.0, 1.0]，映射自 `BeautyParams.blush`
  - `face_landmark`：106 点归一化坐标数组，由 `FaceDetector.detect()` 返回后同步下发
- **最佳接入位置**：紧接 `LipstickFilter` 之后（均属于 FaceMakeupFilter 派生，共享相同的 landmark 传入机制）
- **参数下发时机**：与 `lipstickFilter` 保持一致，在 `onRgbaFrame` 的每次人脸检测结果回调中同步更新
- **关闭时**：`blend_level` 设为 `0.0f`，不销毁滤镜对象
- **生命周期**：随 `GpupixelBeautyPreviewProvider.release()` 统一调用 `Destroy()`

#### GPUPixel 专业调色滤镜接入规范（P1，待开发）

> 本期（P0）不实现，规范在此预埋，下期 P1 开发时直接参考。

- **目标**：用 GPUPixel 滤镜链替代专业模式当前依赖 CameraX `CaptureRequest` 的参数调节，实现 GPU Shader 级实时预览，延迟更低、效果更可控
- **滤镜与参数**：

| 功能 | GPUPixel 滤镜类 | 参数 Key | 参数范围 |
|---|---|---|---|
| 曝光 | `ExposureFilter` | `exposure` | [-10.0, 10.0]，0 为原始 |
| 对比度 | `ContrastFilter` | `contrast` | [0.0, 4.0]，1.0 为原始 |
| 饱和度 | `SaturationFilter` | `saturation` | [0.0, 2.0]，1.0 为原始 |
| 白平衡 | `WhiteBalanceFilter` | `temperature` (K) + `tint` [-100,100] | temperature 默认 5000K |

- **滤镜链追加位置**：在 `GPUPixelSinkSurface` 之前、`FaceReshapeFilter` 之后追加
- **参数映射**（App 层 `BeautyParams` → GPUPixel 参数）：
  - 曝光：UI `[-3.0, 3.0]` 区间直接传入 `exposure`（GPUPixel 内部限制 ±10）
  - 对比度：UI `[0, 200]` 归一化为 `[0.0, 4.0]`，默认 1.0（UI 值 50）
  - 饱和度：UI `[0, 200]` 归一化为 `[0.0, 2.0]`，默认 1.0（UI 值 100）
  - 白平衡温度：UI `[2000K, 10000K]` 直接传入 `temperature`
- **关闭时**：各滤镜恢复默认值（exposure=0, contrast=1.0, saturation=1.0, temperature=5000）
- **注意**：调色滤镜对全画面生效，不依赖 FaceDetector，无需传 `face_landmark`
- [ ] P1 开发时：`BeautyParams` 是否已新增 `exposure`、`contrast`、`saturation`、`whiteBalanceTemperature` 字段？
- [ ] P1 开发时：`GpupixelBeautyPreviewStrategy.applyBeautySettings()` 是否已映射上述字段到对应滤镜？
- [ ] P1 开发时：`ProModeControls` 是否已切换到 GPUPixel 滤镜参数，并废弃 CameraX `CaptureRequest` 路径？

#### GPUPixel 风格特效滤镜接入规范（P2，2026-04 实现）

> 本规范于 2026-04 实现并记录。

**滤镜链追加位置**：`WhiteBalanceFilter` 之后、`GPUPixelSinkSurface` 之前。

**滤镜链拓扑（完整，含风格特效）**：
```
GPUPixelSourceRawData
  → LipstickFilter    (blend_level, face_landmark)
  → BlusherFilter     (blend_level, face_landmark)
  → BeautyFaceFilter  (skin_smoothing, whiteness)
  → FaceReshapeFilter (thin_face, big_eye, face_landmark)
  → ExposureFilter    (exposure)
  → ContrastFilter    (contrast)
  → SaturationFilter  (saturation)
  → WhiteBalanceFilter (temperature, tint)
  → [StyleFilter]     (互斥，每次只激活一个，NONE 时使用透传滤镜或直连 Sink)
  → GPUPixelSinkSurface
```

**互斥切换策略**：
- 维护一个 `activeStyleFilter: GPUPixelFilter?` 成员变量。
- 切换前先将前一个滤镜从链路中移除（重新接线 `WhiteBalanceFilter → SinkSurface`），再插入新滤镜。
- `STYLE_NONE` 时，直接连接 `WhiteBalanceFilter → SinkSurface`，不保留任何风格滤镜在链路中。

**支持的风格滤镜（`StyleFilter` 枚举与 GPUPixel 类名映射）**：

| StyleFilter 值 | GPUPixel 滤镜类 | 关键参数 | 推荐默认值 |
|---|---|---|---|
| `NONE` | — | — | — |
| `TOON` | `ToonFilter` | `threshold` [0,1], `quantizationLevels` | threshold=0.2, levels=10.0 |
| `SMOOTH_TOON` | `SmoothToonFilter` | `blurRadius`, `threshold`, `quantizationLevels` | blur=2.0, threshold=0.1, levels=10.0 |
| `SKETCH` | `SketchFilter` | `edgeStrength` [0,1] | edgeStrength=1.0 |
| `POSTERIZE` | `PosterizeFilter` | `colorLevels` [1,256] | colorLevels=4 |
| `EMBOSS` | `EmbossFilter` | `intensity` [0,4] | intensity=1.0 |
| `CROSSHATCH` | `CrosshatchFilter` | `crossHatchSpacing` [0,0.1], `lineWidth` | spacing=0.03, lineWidth=0.003 |

**参数传递**：
- 风格参数通过 `BeautyParams.styleFilter: StyleFilter` 字段传递，枚举名映射到对应的 `GPUPixelFilter` 类名。
- 参数使用固定推荐默认值，本期不对外暴露参数调节入口（UI 只提供开关切换）。

**生命周期**：
- 随 `GpupixelBeautyPreviewProvider.initialize()` 时不预创建风格滤镜，按需在 `applyStyleFilter()` 中动态创建。
- 随 `release()` 调用时确保当前激活的风格滤镜 `Destroy()` 被调用。

**`BeautyParams` 字段**：
- `styleFilter: StyleFilter = StyleFilter.NONE`（新增字段，默认无特效）

**注意事项**：
- [ ] 风格滤镜与调色滤镜叠加时，确保滤镜链接线顺序正确（调色在前，风格在后）。
- [ ] 切换风格滤镜时，上一个滤镜必须从链中断开后才能插入新的，否则 GPUPixel C++ 层可能双重持有节点导致崩溃。
- [ ] 非 GPUPixel 引擎（大美丽）模式下，`StyleFilter` 字段由引擎层静默忽略，不引发异常。
- [ ] UI 层在非 GPUPixel 模式下，风格特效区域整体置灰，并展示引擎提示。

---

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ 预览帧率 ≥ 30fps（理想 60fps）→ 零拷贝 GPU 管线 + `sleep(1)` 轻量轮询
- ✅ 参数响应延迟 < 100ms → `uniform` 实时传递，禁止运行时 Shader 重编译
- ✅ 本地隐私保证 → 所有处理在设备 GPU 完成，无网络交互
- ✅ 自动容灾降级 → `BeautyPreviewProvider` 接口收敛初始化/运行时异常，返回 `Result.failure`
- ✅ 长期库化目标 → `api/` 与 `egl/` 严格分层，App 仅依赖能力契约

**技术决策记录**：
- 选择 OpenGL ES 而非 Vulkan：CameraX 兼容性更好、设备覆盖率更高、开发周期更短
- 选择 `SurfaceView` 而非 `TextureView`：直接硬件合成，延迟更低，功耗更小（GPUPixel 路径使用 `TextureView`，因 SDK 接口限制）
- 输入/显示 Surface 解耦：避免 CameraX 与 View 生命周期抖动互相影响
- 磨皮使用双边滤波快速近似（9pt）而非盒式模糊：保边效果更自然，移动端单帧耗时可接受（早期文档“盒式模糊”描述已纠正）。后续评估引导滤波（O(N) 无序复杂度）作为 Phase 2 升级方向
- 单 Pass Shader 覆盖全部美颜效果：减少 FBO 切换开销，单帧延迟可控
- GPUPixel 实验性集成保留 FaceDetector 独立路径：避免与 ML Kit 人脸点位格式冲突
- `api/` 纯 Kotlin 接口层：为后续独立发布 AAR/Maven 做准备

---

## 6. 相关文档与实现入口

- `PRODUCT.md` - 产品需求规格说明书（大美丽 产品策略）
- `docs/FEATURES.md` - 功能交互规范
- `docs/BIG_BEAUTY_TECH_SPEC.md` - 大美丽 渲染链路、容灾回退、冷却恢复与观测指标
- `docs/PIXELFREE_FALLBACK_TECH_SPEC.md` - ~~PixelFreeEffects SDK 集成（备用引擎）~~ **已废弃并移除（2026-04）**
- `docs/BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md` - 大美丽 QA 独立执行清单
- `app/src/main/java/com/picme/features/camera/AGENTS.md` - Camera 模块实现规范
- `beauty-engine/src/main/java/com/picme/beauty/api/` - 对外稳定 API
- `beauty-engine/src/main/java/com/picme/beauty/egl/` - OpenGL ES 渲染管线实现
