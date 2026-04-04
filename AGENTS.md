# PicMe AI Agent 系统：唯一事实来源 (SSOT)

本文件定义了 PicMe 项目所有 AI Agent 的严格操作标准。**违反此规范将被视为严重错误。**

## 1. 角色定义与层级
- **[PM] 产品经理**：`PRODUCT.md` 的权威维护者。负责业务价值、交互逻辑（UX Flow）和多语言文案（I18N）。
- **[RD] 全栈工程师**：负责从领域模型（Domain）到 UI 的完整实现。整合了 Android 框架专家职能。核心要求是具备"自愈（Self-Healing）"能力。
- **[CR] 规范守护者**：(Code Reviewer) 负责验证代码是否符合 Section 3, 4, 6 的规范。是代码"正确性"和"风格一致性"的最终裁决者。
- **[QA] 质量专家**：负责边界情况测试、性能基准测试和端到端功能验证。

## 2. 项目文档体系与关系

### 2.1 三层文档架构
```
PRODUCT.md (产品需求规格说明书)
    ↓ 引用
FEATURES.md (功能细节与业务逻辑规范)
    ↓ 指导
AGENTS.md (AI Agent 操作规范)
```

### 2.2 各文档职责与维护者

| 文档 | 定位 | 主要维护者 | 阅读对象 | 核心内容 |
|------|------|------------|----------|----------|
| **PRODUCT.md** | 产品需求 SSOT<br>(Single Source of Truth) | **[PM]** 产品经理 | PM、UI 设计师、<br>测试工程师、RD | - 产品愿景与使命<br>- 核心功能规范（相机、相册、滤镜等）<br>- 设计系统与 UX 准则<br>- 性能指标（启动<500ms、拍摄<50ms）<br>- 隐私与安全约束 |
| **FEATURES.md** | 功能交互细节规范 | **[PM]** 产品经理<br>**[RD]** 全栈工程师 | UI 设计师、<br>测试工程师、RD | - 用户交互流程（UX Flow）<br>- 体验规范和反馈规则<br>- 业务场景和判定规则（人脸分组、重复检测）<br>- 视觉风格指引（HyperOS 风格）<br>- 多语言词汇表（I18N） |
| **AGENTS.md** | AI Agent 操作规范 | **[CR]** 规范守护者 | AI Agent、RD | - 角色定义与职责<br>- 核心操作约束<br>- 架构与代码风格规范<br>- 结构化日志标准<br>- AI 执行工作流（Self-Heal Loop）<br>- 最佳实践示例 |

### 2.3 文档使用规则
- **[MUST] 单一可信源原则**：
  - 产品需求以 `PRODUCT.md` 为准
  - 交互细节以 `FEATURES.md` 为准
  - 代码规范以 `AGENTS.md` 为准
  
- **[MUST] 文档引用链**：
  - PRODUCT.md 中的功能规范会指向 FEATURES.md 的详细章节
  - FEATURES.md 中的技术实现会指向各模块的 AGENTS.md
  - AGENTS.md 中的业务逻辑会回溯到 PRODUCT.md 和 FEATURES.md

- **[MUST] 文档同步更新**：
  - 新增功能时，必须按顺序更新：PRODUCT.md → FEATURES.md → AGENTS.md
  - 修改现有功能时，必须同步更新所有相关文档
  - 严禁只修改代码而不更新文档

### 2.4 各模块 AGENTS.md
除根目录的总规范外，各功能模块还有自己的 AGENTS.md：
- `data/AGENTS.md` - 数据层实现规范
- `di/AGENTS.md` - 依赖注入规范
- `features/camera/AGENTS.md` - 相机功能实现细节
- `features/gallery/AGENTS.md` - 相册功能实现细节
- `features/editor/AGENTS.md` - 编辑器功能实现细节
- `features/settings/AGENTS.md` - 设置功能实现细节
- `features/debug/AGENTS.md` - 调试工具实现细节

**注意**：模块 AGENTS.md 应聚焦技术实现细节，不得包含产品需求或交互规范（这些应在 FEATURES.md 中定义）。

### 2.5 技术方案的文档化

对于复杂的技术方案，需要创建独立的技术规范文档：

#### 2.5.1 核心技术文档

| 文档 | 定位 | 阅读对象 | 核心内容 |
|------|------|----------|----------|
| **CAMERA_PREVIEW_TECH_SPEC.md** | 相机预览完整规范 | RD、UI 设计师 | - PreviewView + ScaleType 方案<br>- 传感器旋转机制<br>- 坐标系统与人脸跟踪<br>- ViewPort + UseCaseGroup 实现<br>- 常见问题解决方案 |
| **R_PLAN_TECH_SPEC.md** | 实时美颜完整规范<br>（中长期规划） | RD、架构师 | - 第一性原理分析<br>- EGL 上下文管理<br>- SurfaceTexture 生命周期<br>- 渲染线程同步<br>- 调试检查清单<br>- 降级策略 |
| **PIXELFREE_FALLBACK_TECH_SPEC.md** | PixelFreeEffects SDK 集成规范<br>（当前实施方案） | RD | - SDK 初始化流程<br>- 美颜参数设置<br>- 图像处理流程<br>- 资源管理<br>- 性能优化 |

#### 2.5.2 双轨策略说明

```
短期（1-2 周）          中期（1-2 月）           中长期（2-3 月）
    ↓                      ↓                        ↓
PixelFreeEffects      同时运行              R 计划自主研发
SDK 接入            → 积累数据            → 完全替代 SDK
- 快速上线           - 性能监控            - 技术可控
- 验证产品           - Shader 优化         - 定制化能力
- 用户反馈           - 算法迭代            - 零授权成本
```

**文档更新规则**：
- 技术方案确定后 24 小时内必须完成文档化
- 实施过程中遇到的问题必须更新到对应指南
- 文档由 [RD] 创建，[CR] 审核
- **技术路线调整后，旧文档必须标记为废弃或备选**

#### 2.5.3 文档路径快速索引

- 相机预览问题 → `docs/CAMERA_PREVIEW_TECH_SPEC.md`
- 实时美颜开发 → `docs/R_PLAN_TECH_SPEC.md`
- PixelFree SDK → `docs/PIXELFREE_FALLBACK_TECH_SPEC.md`
- 产品需求 → `PRODUCT.md`
- 交互细节 → `docs/FEATURES.md`
- 代码规范 → `AGENTS.md`（本文件）

## 3. 核心操作约束 [严格执行]
- **[PRIVACY] 隐私至上**：所有 AI 处理（人脸、OCR 等）必须 100% 本地化。严禁请求网络权限。
- **[PERF] 极致反馈**：交互反馈必须在 100ms 内。拍摄快门延迟 < 50ms。
- **[I18N] 多语言同步**：严禁硬编码。必须同步更新 `EN`、`zh-rCN` 和 `zh-rTW` 的 `strings.xml`。

## 4. 架构与代码风格规范
- **架构模式**：Clean Architecture (Domain -> Data -> Features)。
- **缩进标准**：Kotlin/Java 使用 **4 空格**；XML/JSON/MD 使用 **2 空格**。
- **Lambda 规范**：必须显式命名 lambda 参数（如 `item ->`）。**严禁使用 `it`**。
- **状态管理**：UI 状态必须使用 `Sealed Class`。
- **导入管理**：**严禁使用通配符导入 (`*`)**。

## 4.2 R 计划架构规范（当前重点实施）

### 4.2.1 第一性原理目标

从用户体验倒推技术要求：
- **极致流畅**：预览帧率 ≥ 30fps，理想 60fps；单帧处理 ≤ 16ms
- **零感延迟**：参数调节到画面变化的延迟 < 100ms
- **技术可控**：自研管线，快速迭代；零授权成本
- **容错可用**：渲染失败自动降级为离线美颜，拍照功能不受影响

### 4.2.2 技术本质

实时美颜预览的本质是 **GPU 加速的图像流处理管道**：

```
相机传感器 → YUV 数据 → GPU 纹理 → Shader 处理 → RGB 显示
           (CameraX)   (OpenGL)  (GLSL)    (Surface)
```

关键约束：
- 数据流必须零拷贝（直接纹理传递）
- 处理流必须在 GPU（避免 CPU 瓶颈）
- 显示流必须直通（避免额外 Surface 切换）

### 4.2.3 核心组件职责

**BeautyPreviewView**：
- 继承 `FrameLayout`，封装 TextureView 和渲染管线
- 管理 `CameraPreviewRenderer` 生命周期
- 提供简洁 API：`smoothingStrength`、`whiteningStrength`、`getSurfaceForCamera()`
- **禁止**：在构造函数中启动渲染，必须等待 CameraX 请求 Surface

**CameraPreviewRenderer**：
- 管理 EGL 上下文、SurfaceTexture、渲染线程
- 实现离屏渲染（Offscreen Rendering）
- 使用 `eglContext` 字段保存共享上下文
- **禁止**：在 `init()` 中启动渲染线程，必须等待 `setRenderSurface()` 调用

**BeautyRenderer**：
- 继承 `GLRenderer`，编译和使用美颜 Shader
- 支持实时参数调整：`updateBeautyParams(smoothing, whitening)`
- **Shader 要求**：使用盒式模糊（性能优化），禁止使用复杂的双边模糊

### 4.2.4 EGL 上下文管理

**标准流程**：
```kotlin
// 1. 创建共享上下文（主线程）
eglContext = eglCore.createContext()

// 2. 离屏初始化（Pbuffer Surface）
val pbufferSurface = eglCore.createSurface(null, 1, 1)
eglCore.makeCurrent(pbufferSurface, eglContext!!)
beautyRenderer.onInit()  // 编译 Shader

// 3. 渲染线程（WindowSurface）
val renderContext = eglCore.createContext(eglContext)  // 共享上下文
eglCore.makeCurrent(windowSurface.getEglSurface(), renderContext)
beautyRenderer.onRender()
```

**关键原则**：
- 所有上下文必须共享（通过 `eglCore.createContext()` 自动共享）
- 离屏上下文用于初始化，渲染上下文用于实际渲染
- **禁止**：在多个线程中同时调用 `eglMakeCurrent`

### 4.2.5 SurfaceTexture 生命周期

**标准流程**：
```kotlin
// BeautyPreviewView
override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    // 1. 保存 SurfaceTexture
    this.surfaceTexture = surface

    // 2. 初始化 Renderer（不启动渲染）
    renderer.init(this)

    // 3. 设置默认参数
    updateBeautyParams()

    // 4. 等待 CameraX 请求 Surface（不立即启动渲染）
}

fun getSurfaceForCamera(): Surface? {
    val st = surfaceTexture ?: return null
    // 第一次调用时才创建 Surface 并启动渲染
    if (!surfaceCreated) {
        surfaceCreated = true
        st.setDefaultBufferSize(1920, 1080)  // 关键！
        renderer.setRenderSurface(Surface(st))
    }
    return Surface(st)
}
```

**关键原则**：
- **延迟初始化**：CameraX 请求时才启动渲染
- **缓冲区大小**：必须调用 `setDefaultBufferSize()`
- **单次创建**：使用 `surfaceCreated` 标志防止重复创建

### 4.2.6 渲染线程同步

**标准实现**：
```kotlin
private fun startRendering(surfaceTexture: SurfaceTexture) {
    renderThread = Thread {
        // 线程绑定 SurfaceTexture
        surfaceTexture.setOnFrameAvailableListener { frameAvailable = true }

        var frameCount = 0
        var lastFpsTime = System.currentTimeMillis()

        while (isRendering && !Thread.interrupted()) {
            // 等待相机帧
            if (!frameAvailable) {
                Thread.sleep(1)
                continue
            }

            try {
                // 1. 更新 SurfaceTexture（从相机获取帧）
                surfaceTexture.updateTexImage()
                frameAvailable = false

                // 2. 获取变换矩阵
                val transformMatrix = FloatArray(16)
                surfaceTexture.getTransformMatrix(transformMatrix)

                // 3. 绑定外部纹理
                GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)

                // 4. 渲染
                beautyRenderer.setTextureTransform(transformMatrix)
                beautyRenderer.onRender()

                // 5. 交换缓冲区
                windowSurface?.swapBuffers()

                // 6. 性能监控
                frameCount++
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFpsTime >= 1000) {
                    Log.d(TAG, "FPS: $frameCount")
                    frameCount = 0
                    lastFpsTime = currentTime
                }

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
- **帧可用监听**：使用 `setOnFrameAvailableListener` 唤醒渲染线程
- **错误恢复**：捕获 `IllegalStateException` 并重试
- **性能监控**：每秒记录 FPS，用于性能告警

### 4.2.7 性能指标

**必须达到的指标**：
- 启动时间：< 500ms（从打开相机到显示预览）
- 渲染延迟：< 16ms（60fps）
- 内存占用：< 30MB（额外）
- 纹理 ID：必须是非 0 值

**性能告警**：
- FPS < 25 或帧处理时间 > 20ms 时发出警告
- FPS < 15 或帧处理时间 > 40ms 时自动降级

### 4.2.8 降级策略

**自动降级触发条件**：
- 连续 3 秒 FPS < 15
- 帧处理时间持续 > 40ms
- OpenGL 错误（纹理创建失败、Shader 编译失败）
- SurfaceTexture 不可用

**降级方案**：
```kotlin
// 方案 A：降低预览分辨率
surfaceTexture?.setDefaultBufferSize(1280, 720)

// 方案 B：关闭实时美颜，使用离线美颜
renderThread?.interrupt()
renderThread = null
useOfflineBeauty = true

// 方案 C：完全降级到 PreviewView
removeView(beautyPreviewView)
addView(previewView)
cameraXPreview.setSurfaceProvider(previewView.surfaceProvider)
```

### 4.2.9 调试检查清单

**启动阶段**：
- [ ] `onSurfaceTextureAvailable` 被调用
- [ ] `renderer.init()` 成功
- [ ] 外部纹理 ID 创建（非 0）
- [ ] SurfaceTexture 创建成功

**CameraX 绑定**：
- [ ] `getSurfaceForCamera()` 被调用
- [ ] 返回的 Surface 非 null
- [ ] `setSurfaceProvider` 被调用
- [ ] SurfaceProvider 的回调执行
- [ ] 有 "Camera bound" 日志

**渲染阶段**：
- [ ] 渲染线程启动
- [ ] `updateTexImage()` 成功
- [ ] 有 "New frame available" 回调
- [ ] FPS ≥ 30
- [ ] TextureView 显示内容

**关键日志标签**：
```
D/PicMe:BeautyPreviewView: Surface texture available
D/PicMe:CameraPreview: External texture created: X (X != 0)
D/PicMe:CameraPreview: SurfaceTexture created with texture ID: X
D/PicMe:Camera: Creating Surface for CameraX
D/PicMe:Camera: SurfaceProvider called
D/PicMe:Camera: Camera bound
D/PicMe:CameraPreview: Render thread started
D/PicMe:CameraPreview: FPS: XX
```

## 4.3 PixelFreeEffects 架构规范（将被 R 计划替代）

### 4.2.1 核心组件职责

**PixelFreeGLSurfaceView**：
- 继承 `GLSurfaceView`，实现 `GLSurfaceView.Renderer`
- 管理 PixelFree SDK 的生命周期
- 提供 OpenGL ES 2.0 渲染环境
- 支持实时美颜参数调整
- **关键**：在 `onSurfaceCreated` 中初始化 SDK

**PixelFreeBeautyEngine**：
- SDK 包装类，提供高级 API
- 封装底层 PixelFree SDK 调用
- 支持多种图像格式处理（纹理/RGBA/YUV）
- **禁止**：直接使用 PixelFree SDK 的底层 API，应通过包装类调用

### 4.2.2 SDK 初始化流程

**标准流程**：
```kotlin
// 1. 创建 PixelFreeGLSurfaceView
val pixelFreeView = PixelFreeGLSurfaceView(context)

// 2. 在 onSurfaceCreated 中自动初始化
pixelFreeView.setRenderer(object : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        pixelFreeView.initPixelFree()
    }
})

// 3. 加载授权文件（如果有）
val authData = pixelFreeView.readBundleFile(context, "pixelfreeAuth.lic")
if (authData != null) {
    pixelFreeView.auth(context, authData, authData.size)
}

// 4. 加载滤镜资源
val filterData = pixelFreeView.readBundleFile(context, "filter_model.bundle")
if (filterData != null) {
    pixelFreeView.createBeautyItemFormBundle(
        filterData,
        filterData.size,
        PFSrcType.PFSrcTypeFilter
    )
}
```

**关键原则**：
- **必须在 GL 上下文中初始化**：在 `onSurfaceCreated` 中调用
- **授权文件可选**：如果没有授权文件，SDK 仍可正常使用
- **资源文件必须提前加载**：在应用启动时加载所有资源

### 4.2.3 美颜参数设置

**标准调用**：
```kotlin
// 磨皮（范围：0.0-1.0）
pixelFreeView.setBeautyParam(
    PFBeautyFilterType.PFBeautyFiterTypeFaceBlurStrength, 
    0.5f
)

// 美白（范围：0.0-1.0）
pixelFreeView.setBeautyParam(
    PFBeautyFilterType.PFBeautyFiterTypeFaceWhitenStrength, 
    0.3f
)

// 大眼（范围：0.0-1.0）
pixelFreeView.setBeautyParam(
    PFBeautyFilterType.PFBeautyFiterTypeFace_EyeStrength, 
    0.3f
)

// 瘦脸（范围：0.0-1.0）
pixelFreeView.setBeautyParam(
    PFBeautyFilterType.PFBeautyFiterTypeFace_thinning, 
    0.3f
)
```

**参数范围**：
- 所有美颜参数范围：`0.0` - `1.0`
- 推荐值：`0.3` - `0.7`（自然美观）
- **禁止**：超过 `0.8`（会导致不自然）

### 4.2.4 图像处理流程

**实时预览（纹理模式）**：
```kotlin
// 1. CameraX 生成 OpenGL 外部纹理
val textureId = cameraXTextureId

// 2. 调用 PixelFree SDK 处理纹理
val processedTextureId = pixelFreeView.processTexture(
    textureId, 
    width, 
    height
)

// 3. 渲染到屏幕
// （可以使用默认的 GLSurfaceView 渲染，或自定义渲染）
```

**拍照处理（RGBA 模式）**：
```kotlin
// 1. 从 CameraX 获取 RGBA 数据
val rgbaData = cameraXImageProxy.toByteBuffer()

// 2. 调用 PixelFree SDK 处理
val processedData = pixelFreeEngine.processRGBA(
    rgbaData, 
    width, 
    height
)

// 3. 保存为照片
val bitmap = processedData.toBitmap(width, height)
savePhoto(bitmap)
```

**关键原则**：
- **纹理模式性能最优**：适合实时预览
- **RGBA 模式灵活**：适合拍照后处理
- **YUV 模式最复杂**：不推荐使用

### 4.2.5 资源管理

**资源类型**：
- **授权文件**：`pixelfreeAuth.lic`（可选）
- **滤镜资源**：`filter_model.bundle`（必选）
- **美妆资源**：`makeup_name.bundle`（可选）

**加载时机**：
- **应用启动时**：加载所有资源（推荐）
- **按需加载**：首次使用时加载（节省内存）

**释放时机**：
- **Activity onDestroy**：调用 `release()`
- **切换场景**：先调用 `clearMakeup()`，再加载新资源

### 4.2.6 性能优化

**渲染模式**：
```kotlin
// 设置为手动渲染（按需渲染）
pixelFreeView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

// 在需要渲染时调用
pixelFreeView.requestRender()
```

**分辨率选择**：
- **实时预览**：720p（1280x720）- 性能优先
- **拍照处理**：1080p（1920x1080）- 质量优先
- **专业模式**：根据设备性能动态调整

**内存管理**：
- **基础占用**：~20MB（SDK 本身）
- **滤镜资源**：~5-10MB
- **美妆资源**：~10-20MB
- **总占用**：控制在 60MB 以内

**实时预览性能自适应规范（2026-04）**：
- **参数联动**：滑杆参数变更时，必须确保美颜总开关状态与参数有效性一致（有参数即启用，清零可自动关闭）。
- **人脸检测模式**：默认 `Landmark`，仅在明确需要高精度轮廓时切换到 `Contour`。
- **动态检测间隔**：仅在瘦脸/大眼启用时生效，推荐区间 `280~450ms`。
- **强度档位建议**：
  - 保守：`320~520ms`，温控优先。
  - 平衡：`280~450ms`，默认推荐。
  - 激进：`220~360ms`，效果跟手优先。
- **可观测性**：必须输出 FPS、处理耗时、延迟、CPU、空帧、检测间隔等核心指标日志。

### 4.2.7 技术借鉴与 R 计划衔接

**R 计划将借鉴 PixelFreeEffects 的以下设计**：

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

**自主替代目标**：
- ✅ **零授权成本**：无需购买商业 SDK
- ✅ **完全可控**：可定制化开发特殊效果
- ✅ **技术积累**：构建团队核心技术能力
- ⚠️ **时间周期**：预计需要 2-3 个月研发

## 4.3 R 计划架构规范（中长期规划）

### 4.3.1 核心组件职责

**BeautyPreviewView**：
- 继承 `FrameLayout`，封装 TextureView 和渲染管线
- 管理 `CameraPreviewRenderer` 生命周期
- 提供简单 API：`smoothingStrength`、`whiteningStrength`、`getSurfaceForCamera()`
- **禁止**：在构造函数中启动渲染，必须等待 CameraX 请求 Surface

**CameraPreviewRenderer**：
- 管理 EGL 上下文、SurfaceTexture、渲染线程
- 实现离屏渲染（Offscreen Rendering）
- **关键**：使用 `eglContext` 字段保存共享上下文
- **禁止**：在 `init()` 中启动渲染线程，必须等待 `setRenderSurface()` 调用

**BeautyRenderer**：
- 继承 `GLRenderer`，编译和使用美颜 Shader
- 支持实时参数调整：`updateBeautyParams(smoothing, whitening)`
- **Shader 要求**：使用盒式模糊（性能优化），禁止使用复杂的双边模糊

### 4.3.2 EGL 上下文管理

**标准流程**：
```kotlin
// 1. 创建共享上下文
eglContext = eglCore.createContext()

// 2. 离屏初始化（Pbuffer Surface）
val pbufferSurface = eglCore.createSurface(null, 1, 1)
eglCore.makeCurrent(pbufferSurface, eglContext!!)
beautyRenderer.onInit()  // 编译 Shader

// 3. 渲染线程（WindowSurface）
val renderContext = eglCore.createContext()
eglCore.makeCurrent(windowSurface.getEglSurface(), renderContext)
beautyRenderer.onRender()
```

**关键原则**：
- 所有上下文必须共享（通过 `eglCore.createContext()` 自动共享）
- 离屏上下文用于初始化，渲染上下文用于实际渲染
- **禁止**：在多个线程中同时调用 `eglMakeCurrent`

### 4.3.3 SurfaceTexture 生命周期

**标准流程**：
```kotlin
// BeautyPreviewView
override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    // 1. 保存 SurfaceTexture
    this.surfaceTexture = surface
    
    // 2. 初始化 Renderer（不启动渲染）
    renderer.init(this)
    
    // 3. 设置默认参数
    updateBeautyParams()
    
    // 4. 等待 CameraX 请求 Surface（不立即启动渲染）
}

fun getSurfaceForCamera(): Surface? {
    // 第一次调用时才创建 Surface 并启动渲染
    if (!surfaceCreated) {
        surfaceCreated = true
        st.setDefaultBufferSize(1920, 1080)  // 关键！
        renderer.setRenderSurface(Surface(st))
    }
    return Surface(st)
}
```

**关键原则**：
- **延迟初始化**：CameraX 请求时才启动渲染
- **缓冲区大小**：必须调用 `setDefaultBufferSize()`
- **单次创建**：使用 `surfaceCreated` 标志防止重复创建

### 4.3.4 渲染线程同步

**标准实现**：
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

### 4.3.5 性能指标

**必须达到的指标**：
- 启动时间：< 500ms（从打开相机到显示预览）
- 渲染延迟：< 16ms（60fps）
- 内存占用：< 50MB（额外）
- 纹理 ID：必须是非 0 值

**监控方法**：
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

### 4.3.6 调试检查清单

**启动阶段**：
- [ ] `onSurfaceTextureAvailable` 被调用
- [ ] `renderer.init()` 成功
- [ ] 外部纹理 ID 创建（非 0）
- [ ] SurfaceTexture 创建成功

**CameraX 绑定**：
- [ ] `getSurfaceForCamera()` 被调用
- [ ] 返回的 Surface 非 null
- [ ] `setSurfaceProvider` 被调用
- [ ] SurfaceProvider 的回调执行
- [ ] 有 "Camera bound" 日志

**渲染阶段**：
- [ ] 渲染线程启动
- [ ] `updateTexImage()` 成功
- [ ] 有 "New frame available" 回调
- [ ] 渲染每 16ms 执行一次
- [ ] TextureView 显示内容

**关键日志标签**：
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

### 4.3.7 降级策略

**检测到失败时的降级**：
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

**离线美颜实现**：
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

## 4.1 Import 最佳实践 [CR 重点检查]

### ✅ 正确做法

**1. 按功能模块分组排序**
```kotlin
// 第一组：Compose 核心库 (按字母顺序)
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

// 第二组：Material 组件 (按字母顺序)
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

// 第三组：Foundation 基础组件 (按字母顺序)
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row

// 第四组：UI 工具类 (Modifier、颜色、图形等)
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 第五组：第三方库
import coil.compose.AsyncImage

// 第六组：项目内部类 (按层级排序)
import com.picme.R
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.CameraAspectRatio
```

**2. 使用 IDE 自动导入后手动整理**
```kotlin
// ✅ 步骤:
// 1. 编写代码时使用 Android Studio 的 "Optimize Imports" (Ctrl+Alt+O)
// 2. 手动调整导入顺序，确保同类库在一起
// 3. 删除未使用的导入 (IDE 通常会自动完成)
// 4. 最后检查是否有遗漏的必要导入
```

**3. 新增功能时的导入流程**
```kotlin
// ✅ 当需要使用新类时:
// 1. 先写类名，让 IDE 提示导入
// 2. 按 Alt+Enter 添加导入
// 3. 运行 "Optimize Imports"
// 4. 手动调整到新位置 (保持分组有序)

// 示例：添加 AnimatedVisibility
AnimatedVisibility(visible = true) { }  // 输入后按 Alt+Enter
// IDE 自动添加：import androidx.compose.animation.AnimatedVisibility
// 然后手动将其移动到 Compose 核心库分组的顶部
```

### ❌ 错误做法

**1. 导入顺序混乱**
```kotlin
// ❌ 错误：不同库混在一起，难以查找
import androidx.compose.runtime.mutableStateOf
import com.picme.R
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Column
```

**2. 遗漏必要导入**
```kotlin
// ❌ 错误：使用了 mutableStateOf 但未导入
@Composable
fun MyComponent() {
    var state by remember { mutableStateOf(0) }  // 编译错误!
}

// ✅ 正确：确保所有使用的类都有导入
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
```

**3. 重复导入**
```kotlin
// ❌ 错误：同一个类导入两次
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf  // 重复!

// ✅ 解决：运行 "Optimize Imports" 自动清理
```

**4. 使用已废弃的导入**
```kotlin
// ❌ 错误：使用已重命名的 API
import androidx.compose.material.Divider  // 已废弃

// ✅ 正确：使用最新 API
import androidx.compose.material3.HorizontalDivider
```

### 🔧 自愈流程 (Self-Heal)

**编译错误快速定位**:
```bash
# 当遇到 "Unresolved reference" 错误时:
1. 检查错误行使用的类名
2. 搜索是否已导入该类
3. 若未导入，添加对应的 import 语句
4. 若已导入，检查类名拼写是否正确
5. 重新编译验证

# 常见错误及解决方案:
e: Unresolved reference 'mutableStateOf'
→ 添加：import androidx.compose.runtime.mutableStateOf

e: Unresolved reference 'AnimatedVisibility'
→ 添加：import androidx.compose.animation.AnimatedVisibility

e: Unresolved reference 'rotate'
→ 添加：import androidx.compose.ui.draw.rotate

e: Type 'MutableState<String?>' has no method 'setValue'
→ 检查是否正确使用委托属性 (var x by state vs val x = state)
```

### 📋 CR 检查清单

每次代码审查时必须检查:
- [ ] 所有导入都按功能模块分组
- [ ] 每组内按字母顺序排列
- [ ] 没有通配符导入 (`*`)
- [ ] 没有重复导入
- [ ] 没有未使用的导入
- [ ] 所有使用的类都已导入
- [ ] 导入顺序符合规范 (Compose → Material → Foundation → UI → Third-party → Project)
- [ ] 没有使用已废弃的 API

## 5. 结构化日志标准
- **标签格式**：`PicMe:[ModuleName]` (例如 `PicMe:Camera`, `PicMe:AI`)。
- **策略要求**：必须记录所有状态流转、核心业务节点 and 关键错误。`LogRepository` 缓存上限 500 条。

## 6. AI 执行工作流：自愈循环 (Self-Healing Loop)
1. **探索 (Explore)**：通过 `find_usages` 和 `grep` 绘制依赖地图。
2. **对齐 (Align)**：确保逻辑与 `PRODUCT.md` 和 `AGENTS.md` 100% 契合。
3. **执行 (Execute)**：使用 `replace_text` 进行原子化、精准的代码修改。
4. **自愈 (Self-Heal)**：
   - 运行 `analyze_current_file`。必须**立即修复**所有 Error 和相关 Warning。
   - 运行 `./gradlew assembleDebug`。若失败，阅读日志并自主修复，严禁打扰用户。
5. **上下文保护 (Context Protection)**：在修改大型文件前，仅读取受影响的类成员或函数块，避免一次性读取数千行代码导致上下文偏移。
6. **CR 审计**：由 CR 角色复核格式、命名和 I18N 是否完全达标。

## 7. Few-Shot 示例 (最佳实践 vs. 反面典型)

### ✅ 最佳实践 (RD)
```kotlin
// 显式命名, 4 空格缩进, 使用 Result 封装, 结构化日志
suspend fun deleteAsset(asset: MediaAsset): Result<Unit> {
    return repository.delete(asset.id).map { isSuccess ->
        Log.d("PicMe:Storage", "Successfully deleted asset: ${asset.id}")
        isSuccess
    }
}
```

### ❌ 反面典型 (RD)
```kotlin
// 隐式 'it', 通配符导入, 硬编码, 无日志
import com.picme.data.* 
fun del(it: MediaAsset) {
    db.exec("DELETE FROM media") // 危险且不规范！
}
```
