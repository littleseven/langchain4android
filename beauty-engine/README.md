# PicMe Beauty Engine

`beauty-engine` 是 PicMe 的实时美颜预览引擎，以独立 Android Library 模块存在，长期演进为可独立发布的视觉能力基础库。

---

## 模块定位

- **能力层**：对外暴露稳定的 `api/` 接口（`BeautyPreviewEngine`、`BeautyParams` 等）。
- **大美丽实现层**：内部封装 OpenGL ES + EGL 渲染管线（`egl/` 包），禁止外部直接引用。
- **GPUPixel 适配层**：`gpupixel/` 包封装 GPUPixel C++ 引擎的 Kotlin 适配，实现相同的 `BeautyPreviewEngine` 接口。
- **性能目标**：零拷贝 GPU 数据流，单帧处理 ≤ 16ms（60fps），参数响应延迟 < 100ms。

---

## 双引擎架构（2026-04）

| 引擎 | 包路径 | 技术栈 | 状态 |
|---|---|---|---|
| 大美丽（BIG_BEAUTY） | `egl/` | 自研 OpenGL ES + EGL | ✅ 默认主引擎 |
| GPUPixel（GPUPIXEL） | `gpupixel/` | GPUPixel C++ JNI（Apache 2.0） | ✅ 实验性已集成 |

> 当前模块仅维护大美丽与 GPUPixel 两条链路，历史旧兜底方案已从代码、配置与文档中清理。

---

## 包结构

```
beauty-engine/src/main/java/com/picme/beauty/
├── api/                               # 对外稳定 API（能力契约层）
│   ├── BeautyParams.kt                # 美颜参数数据类（所有参数归一化至 0.0~1.0）
│   ├── BeautyPerfStats.kt             # 性能统计模型
│   ├── BeautyPreviewCapability.kt     # GL 能力扩展接口（FaceWarp/LipMask 等）
│   ├── BeautyPreviewProvider.kt       # 预览 Provider 基础接口
│   ├── BeautyPreviewEngine.kt         # 组合接口（Provider + Capability + getView）
│   └── BeautyPreviewProviderFactory.kt
├── egl/                               # 大美丽内部实现（GL 渲染管线层）
│   ├── GlBeautyPreviewProvider.kt     # Provider 接口实现（内部适配器）
│   ├── BeautyPreviewView.kt           # 自定义 View（SurfaceView 封装）
│   ├── CameraPreviewRenderer.kt       # 渲染管线核心
│   ├── BeautyRenderer.kt              # 美颜 Shader 渲染器
│   ├── BeautyShaders.kt               # GLSL Shader 源码
│   ├── ShaderProgram.kt               # Shader 编译与链接
│   ├── EGLCore.kt                     # EGL 上下文与 Surface 管理
│   └── WindowSurface.kt               # EGL Window Surface 封装
└── gpupixel/                          # GPUPixel 实验性集成层
    └── GpupixelBeautyPreviewProvider.kt
```

**依赖方向红线**：
- `api/` 包：**禁止**依赖 `egl/`、`gpupixel/` 或任何实现细节
- App 层：只允许依赖 `api/` 接口，**禁止**直接实例化 `egl/` 或 `gpupixel/` 内部类

---

## Gradle 依赖

```kotlin
dependencies {
    implementation(project(":beauty-engine"))
}
```

---

## 最小初始化（大美丽路径）

```kotlin
// 1. 通过 GlBeautyPreviewProvider 获取实例（app 层通过 rememberGlBeautyPreviewProvider 管理）
//    实际使用中由 Composable 负责创建与释放，无需手动 new
val provider: BeautyPreviewEngine = GlBeautyPreviewProvider(context)

// 2. 初始化引擎（离屏 EGL + Shader 编译）
provider.initialize()

// 3. 设置输入分辨率并获取给 CameraX 的 Surface
provider.setCameraInputBufferSize(width = 1280, height = 720)
val previewSurface = provider.createPreviewSurface()
previewUseCase.setSurfaceProvider { request ->
    request.provideSurface(previewSurface, executor) { }
}

// 4. 实时更新美颜参数
provider.updateFilters(
    BeautyParams(
        enabled = true,
        smoothing = 0.3f,
        whitening = 0.2f,
        bigEyes = 0.1f,
        slimFace = 0.15f
    )
)
```

---

## 最小初始化（GPUPixel 路径）

```kotlin
// GPUPixel 通过 ImageAnalysis 接收帧，不使用 Preview UseCase 的 Surface
val provider = GpupixelBeautyPreviewProvider(context)
provider.setScaleMode(isFillCenter = true)
provider.initialize()

// 在 ImageAnalysis.Analyzer 中传帧
override fun analyze(image: ImageProxy) {
    provider.onRgbaFrame(rgbaBytes, width, height, rotationDegrees)
    image.close()
}
```

---

## 生命周期与释放

`BeautyPreviewEngine` 持有 EGL 上下文、渲染线程和 GPU 资源，**必须**在合适的生命周期节点释放：

```kotlin
// Composable 场景由 DisposableEffect 自动管理
DisposableEffect(provider) {
    onDispose { provider.release() }
}

// ViewModel 场景
override fun onCleared() {
    provider.release()
}
```

释放顺序由内部保证：`WindowSurface` → `EGL Context` → `SurfaceTexture` → `渲染线程`。

---

## 容灾降级

如果 `initialize()` 抛出异常（如设备不支持所需 GLES 版本、Shader 编译失败）：

1. `BeautyPreviewEngine` 内部不会自动回退，异常会向上抛出。
2. **调用方**（`GlBeautyPreviewStrategy` / `GpupixelBeautyPreviewStrategy`）捕获异常，降级为 CameraX `PreviewView` 直出，并通过 `onWarmUpFallback` 通知 UI 层。
3. `BeautyEngineRuntimeState` 记录回退原因，供 UI 层消费展示提示。
4. `BeautyPerfStats` 会额外暴露 `errorCategory` / `errorReason`，用于调试浮层展示最近一次 `PicMe:BeautyRenderer` 错误分类。
5. 详细的兜底策略与冷却恢复机制请参阅 `docs/BEAUTY_ENGINE_FALLBACK.md`。

---

## 接口稳定性

| API | 状态 | 说明 |
|-----|------|------|
| `BeautyPreviewEngine` | ✅ 稳定 | Phase 3 库化核心契约（Provider + Capability + getView） |
| `BeautyParams` | ✅ 稳定 | 新增参数默认 `0.0f`，向后兼容；`colorMatrix` 字段支持 FilterType |
| `BeautyPerfStats` | ⚠️ 实验性 | 字段可能随观测需求微调 |
| GPUPixel 路径 | ⚠️ 实验性 | 功能完整但仍在观测中，不保证 API 稳定 |
| 独立发布 AAR | ⏳ 未开始 | 预计 M3 完成后进入 Maven 发布流程 |

---

## GPUPixel 已接入能力（2026-04）

| 能力 | 滤镜类 | 状态 |
|---|---|---|
| 磨皮 / 美白 | `BeautyFaceFilter` | ✅ |
| 瘦脸 / 大眼 | `FaceReshapeFilter` | ✅ |
| 唇色 | `LipstickFilter` | ✅ |
| 腮红 | `BlusherFilter` | ✅ |
| 曝光 / 对比度 / 饱和度 / 白平衡 | `ExposureFilter` 等 | ✅ |
| 卡通 / 平滑卡通 | `ToonFilter` / `SmoothToonFilter` | ✅ |
| 素描 | `SketchFilter` | ✅ |
| 色块化 | `PosterizeFilter` | ✅ |
| 浮雕 | `EmbossFilter` | ✅ |
| 交叉线 | `CrosshatchFilter` | ✅ |
| 人脸关键点 | `FaceDetector`（Mars 模型，106 点） | ✅ |

---

## 常见错误排查

| 现象 | 可能原因 | 排查建议 |
|------|----------|----------|
| `initialize()` 抛出 EGL 相关异常 | 设备 GLES 版本过低或上下文创建失败 | 检查 `EGLCore` 日志，确认 `eglChooseConfig` 成功 |
| 预览黑屏 / 无画面 | `createPreviewSurface()` 未被正确绑定到 CameraX | 确认 `SurfaceRequest.provideSurface()` 已调用 |
| 参数更新不生效 | 在 `initialize()` 之前调用了 `updateFilters()` | 确保在 `isReady() == true` 后再更新参数 |
| GPUPixel 切换后崩溃 | `SetRotation` 传入了角度值（90/270）而非枚举序号 | 必须映射：90→2, 180→7, 270→1, 0→0 |
| GPUPixel 黑屏无帧 | 同时创建了 CameraX `Preview` UseCase 导致 `ImageAnalysis` 无帧 | GPUPixel 模式下不创建 `Preview` UseCase |
| 内存泄漏 / ANR | `release()` 未被调用或调用时机过晚 | 在 `ViewModel.onCleared()` 或 `DisposableEffect.onDispose` 中释放 |
| 帧率过低 | Shader 复杂度过高或 FBO 频繁创建 | 检查 `getPerfStats()` 中的 `processingMs` 是否 > 16ms |

---

## 相关文档

- `beauty-engine/AGENTS.md` — 内部实现规范与代码约束（详细）
- `docs/BIG_BEAUTY_TECH_SPEC.md` — 大美丽渲染链路、容灾回退、冷却恢复与观测指标
- `docs/BEAUTY_ENGINE_FALLBACK.md` — 跨模块容灾降级统一说明
- `docs/BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md` — 大美丽 QA 执行清单
