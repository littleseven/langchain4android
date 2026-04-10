# Beauty Engine 模块技术实现规范 (Beauty Engine Technical Implementation)

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `beauty-engine` 独立库模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 大美丽 渲染链路、容灾回退、冷却恢复与观测指标：见 `docs/BIG_BEAUTY_TECH_SPEC.md`。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：`beauty-engine` 是 PicMe 大美丽 实时美颜引擎的独立 Android Library 模块，承载 OpenGL ES + EGL 渲染管线，对外暴露稳定 API，对内封装 GPU 加速实现。长期演进为可独立发布的视觉能力基础库。

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

---

## 2. 技术实现规范 (Technical Implementation)

### 2.1 模块包结构规范

```
beauty-engine/src/main/java/com/picme/beauty/
├── api/                          # 对外稳定 API（能力契约层）
│   ├── BeautyParams.kt           # 美颜参数数据类
│   ├── BeautyPerfStats.kt        # 性能统计模型
│   ├── BeautyPreviewCapability.kt # 能力查询与版本信息
│   └── BeautyPreviewProvider.kt  # 预览 Provider 接口
└── egl/                          # 内部实现（渲染管线层）
    ├── BeautyPreviewView.kt      # 自定义 View（SurfaceView 封装）
    ├── CameraPreviewRenderer.kt  # 渲染管线核心
    ├── BeautyRenderer.kt         # 美颜 Shader 渲染器
    ├── BeautyShaders.kt          # GLSL Shader 源码
    ├── ShaderProgram.kt          # Shader 编译与链接
    ├── EGLCore.kt                # EGL 上下文与 Surface 管理
    ├── WindowSurface.kt          # EGL Window Surface 封装
    ├── GLRenderer.kt             # GL 渲染通用基类
    └── GlBeautyPreviewProvider.kt # Provider 接口实现（内部适配器）
```

**依赖方向红线**：
- `api/` 包：**禁止**依赖 `egl/`、`androidx.camera.*`、`features.*`、`data.*` 等任何实现细节
- `egl/` 包：允许实现 `api/` 接口，允许依赖 `android.*` 和 OpenGL ES 相关库
- App 层：只允许依赖 `beauty-engine` 的 `api/` 接口，禁止直接实例化 `egl/` 内部类

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

**Shader 工程规范**：
- **性能优先**：磨皮使用盒式模糊（Box Blur），避免双边滤波带来的高复杂度
- **分步 Shader**：磨皮 → 美白 → 输出，每步独立 Shader Program
- **参数传递**：通过 `glUniform1f` 实时更新，禁止在参数变化时重新编译 Shader
- **纹理类型**：相机输入使用 `GL_TEXTURE_EXTERNAL_OES`，中间处理使用普通 `GL_TEXTURE_2D`

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
- 选择 `SurfaceView` 而非 `TextureView`：直接硬件合成，延迟更低，功耗更小
- 输入/显示 Surface 解耦：避免 CameraX 与 View 生命周期抖动互相影响
- 盒式模糊替代双边滤波：在性能与效果间取得平衡，满足实时性要求
- `api/` 纯 Kotlin 接口层：为后续独立发布 AAR/Maven 做准备

---

## 6. 相关文档与实现入口

- `PRODUCT.md` - 产品需求规格说明书（大美丽 产品策略）
- `docs/FEATURES.md` - 功能交互规范
- `docs/BIG_BEAUTY_TECH_SPEC.md` - 大美丽 渲染链路、容灾回退、冷却恢复与观测指标
- `docs/PIXELFREE_FALLBACK_TECH_SPEC.md` - PixelFreeEffects SDK 集成（备用引擎）
- `docs/BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md` - 大美丽 QA 独立执行清单
- `app/src/main/java/com/picme/features/camera/AGENTS.md` - Camera 模块实现规范
- `beauty-engine/src/main/java/com/picme/beauty/api/` - 对外稳定 API
- `beauty-engine/src/main/java/com/picme/beauty/egl/` - OpenGL ES 渲染管线实现
