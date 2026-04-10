# PicMe Beauty Engine

`beauty-engine` 是 PicMe 的实时美颜预览引擎，以独立 Android Library 模块存在，长期演进为可独立发布的视觉能力基础库。

---

## 模块定位

- **能力层**：对外暴露稳定的 `api/` 接口（`BeautyPreviewProvider`、`BeautyParams` 等）。
- **实现层**：内部封装 OpenGL ES + EGL 渲染管线（`egl/` 包），禁止外部直接引用。
- **性能目标**：零拷贝 GPU 数据流，单帧处理 ≤ 16ms（60fps），参数响应延迟 < 100ms。

---

## Gradle 依赖

在 `app/build.gradle.kts`（或其他调用方模块）中添加：

```kotlin
dependencies {
    implementation(project(":beauty-engine"))
}
```

---

## 最小初始化代码

```kotlin
// 1. 通过 Factory 获取 Provider 实例（避免直接引用 egl/ 实现类）
val provider = BeautyPreviewProviderFactory.create(context)

// 2. 初始化引擎（离屏 EGL + Shader 编译）
provider.initialize()

// 3. 将预览 Surface 交给 CameraX
val previewSurface = provider.createPreviewSurface()
cameraProvider.bindToLifecycle(
    lifecycleOwner,
    cameraSelector,
    Preview.Builder().build().apply {
        setSurfaceProvider(SurfaceProvider { request ->
            request.provideSurface(previewSurface, executor) {}
        })
    },
    imageCapture
)

// 4. 实时更新美颜参数
provider.updateFilters(
    BeautyParams(
        smoothing = 0.3f,
        whitening = 0.2f,
        bigEyes = 0.1f
    )
)
```

---

## 生命周期与释放

`BeautyPreviewProvider` 持有 EGL 上下文、渲染线程和 GPU 资源，必须在合适的生命周期节点释放：

```kotlin
override fun onCleared() {
    provider.release()
}
```

释放顺序由内部保证：`WindowSurface` → `EGL Context` → `SurfaceTexture` → `渲染线程`。

---

## 容灾降级

如果 `initialize()` 抛出异常（如设备不支持所需 GLES 版本、Shader 编译失败）：

1. `BeautyPreviewProvider` 内部不会自动回退，异常会向上抛出。
2. **调用方**（通常是 `app` 模块的 DI 层）应捕获异常并降级为无美颜预览（CameraX `PreviewView` 直出）。
3. 详细的兜底策略与状态记录机制请参阅 `docs/BEAUTY_ENGINE_FALLBACK.md`。

---

## 接口稳定性

| API | 状态 | 说明 |
|-----|------|------|
| `BeautyPreviewProvider` | ✅ 稳定 | Phase 3 库化核心契约 |
| `BeautyParams` | ✅ 稳定 | 新增参数默认 `0.0f`，向后兼容 |
| `BeautyPerfStats` | ⚠️ 实验性 | 字段可能随观测需求微调 |
| 独立发布 AAR | ⏳ 未开始 | 预计 M3 完成后进入 Maven 发布流程 |

---

## 常见错误排查

| 现象 | 可能原因 | 排查建议 |
|------|----------|----------|
| `initialize()` 抛出 EGL 相关异常 | 设备 GLES 版本过低或上下文创建失败 | 检查 `EGLCore` 日志，确认 `eglChooseConfig` 成功 |
| 预览黑屏/无画面 | `createPreviewSurface()` 未被正确绑定到 CameraX | 确认 `SurfaceRequest.provideSurface()` 已调用 |
| 参数更新不生效 | 在 `initialize()` 之前调用了 `updateFilters()` | 确保在 `isReady() == true` 后再更新参数 |
| 内存泄漏 / ANR | `release()` 未被调用或调用时机过晚 | 在 `ViewModel.onCleared()` 或页面 `onDestroy()` 中释放 |
| 帧率过低 | Shader 复杂度过高或 FBO 频繁创建 | 检查 `getPerfStats()` 中的 `processingMs` 是否 > 16ms |

---

## 相关文档

- `beauty-engine/AGENTS.md` — 内部实现规范与代码约束
- `docs/BIG_BEAUTY_TECH_SPEC.md` — 大美丽 渲染链路、容灾回退、冷却恢复与观测指标
- `docs/BEAUTY_ENGINE_FALLBACK.md` — 跨模块容灾降级统一说明
- `docs/PIXELFREE_FALLBACK_TECH_SPEC.md` — ~~PixelFree 兜底引擎技术规范~~ **已废弃并移除（2026-04）**
