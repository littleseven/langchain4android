# Beauty Engine 容灾降级统一说明

> **定位**：跨模块容灾兜底的单一事实来源（SSOT）。
> 
> 本文档统一说明 `beauty-engine`（大美丽）初始化失败或运行异常时的回退策略、状态记录与恢复机制。各模块 AGENTS.md 中涉及容灾降级的描述，均以此文档为准。

**最后更新**：2026-04-11（PixelFree 已完全移除，重写为单主引擎 + GPUPixel 实验引擎策略）

---

## 1. 引擎策略概览

PicMe 当前引擎策略如下：

| 引擎 | 状态 | 职责 | 实现类 | 所在模块 |
|------|------|------|--------|----------|
| **大美丽 (`BIG_BEAUTY`)** | ✅ 默认主引擎 | 自研 OpenGL ES + EGL 管线，完整美颜功能 | `GlBeautyPreviewProvider` | `:beauty-engine` |
| **GPUPixel (`GPUPIXEL`)** | 🧪 实验性备选 | 开源 C++/OpenGL ES 滤镜库，通过 `BeautyStrategy.GPUPIXEL` 手动切换 | `GpupixelBeautyPreviewStrategy` | `:app` |
| ~~PixelFree (`PIXEL_FREE`)~~ | ❌ **已完全移除（2026-04）** | 第三方 SDK 兜底引擎，因与 Compose 架构冲突且算法不可控，已彻底清除 | — | — |

> **重要说明**：当前项目**不再**使用"主引擎失败自动降级至兜底引擎"的双引擎策略。
> 大美丽初始化失败后，系统将使用 `PreviewView` 进行无美颜预览，并通过冷却窗口机制在下次启动时自动重试。

---

## 2. 故障回退流程

### 2.1 初始化阶段回退（大美丽 warm-up 失败）

在 `:app` 模块的相机预览链路（`CameraPreviewStrategies.kt`）中，按以下流程处理初始化失败：

1. 相机绑定时触发大美丽 warm-up（`GlBeautyPreviewProvider.initialize()`）。
2. 若 `initialize()` 抛出异常（如 GLES 不支持、Shader 编译失败、EGL 上下文创建失败）：
   - 调用 `onGlWarmUpFallback(reason)` 收敛回退逻辑；
   - 调用 `BeautyEngineRuntimeState.markGlEngineFallback(reason)` 记录回退原因与冷却时间；
   - 切换至 `useProviderRenderView = false`，使用 CameraX 原生 `PreviewView` 继续预览；
   - 持久化策略为 `PIXEL_FREE`（历史字段复用，仅用于区分降级状态）；
   - 输出 `PicMe:Camera` 级别日志，确保问题可追踪。
3. 若超过 `PROVIDER_VIEW_BIND_TIMEOUT_MS` 超时仍未绑定成功，同样触发上述回退流程。

```kotlin
// CameraPreviewStrategies.kt（示意，非完整代码）
private fun onGlWarmUpFallback(reason: String) {
    BeautyEngineRuntimeState.markGlEngineFallback(reason)
    // 切换到 PreviewView
    _uiState.update { state -> state.copy(useProviderRenderView = false) }
    Logger.w("PicMe:Camera", "大美丽 warm-up failed: $reason, fallback to PreviewView")
}
```

### 2.2 运行时异常回退

- `beauty-engine` 内部运行异常（如渲染线程崩溃、FBO 失效）会向上抛出。
- `:app` 层在接收到异常后，通过 `BeautyEngineRuntimeState` 标记状态，并在下一次页面重建时回落至 `PreviewView`。
- 详细的运行时冷却与重试机制，请参阅 `docs/BIG_BEAUTY_TECH_SPEC.md`。

### 2.3 GPUPixel 引擎回退（实验性）

- GPUPixel 初始化失败时，由 `GpupixelBeautyPreviewStrategy.bindPreview` 触发 `onWarmUpFallback`。
- 当前无自动恢复热切换，需重启预览绑定流程。

---

## 3. 冷却恢复机制

`BeautyEngineRuntimeState` 是 `:app` 模块中的单例对象，负责记录并消费回退原因：

- **`markGlEngineFallback(reason: String)`**：记录回退原因，并写入冷却时间戳（`gl_engine_recovery_available_at_ms`）。
- **`consumeGlEngineFallbackReason(): String?`**：消费并清空回退原因，供 UI 层展示一次性提示（如 Toast / Snackbar）。
- **冷却到期后**：自动触发 `triggerManualGlEngineRecovery()`，下次相机启动时重新尝试大美丽初始化。

**设计意图**：
- 回退原因只会被消费一次，避免重复弹窗。
- UI 层在适当时机（如相机页面 `onResume`）查询并展示降级提示文案，文案必须提取到 `strings.xml` 以支持 I18N。

---

## 4. 依赖方向约束

- `:beauty-engine` 模块**不依赖** `:app` 模块，也不感知外部策略的存在。
- `:beauty-engine` 仅在初始化失败时抛出异常；兜底决策完全由 `:app` 的相机预览策略层负责。
- 禁止 `:beauty-engine` 的 `egl/` 内部实现类被 `:app` 直接引用；`:app` 只能通过 `api/BeautyPreviewProvider` 访问能力。

---

## 5. 相关文档

- `beauty-engine/AGENTS.md` — 主引擎实现规范
- `beauty-engine/README.md` — 调用方 Quick Start
- `docs/BIG_BEAUTY_TECH_SPEC.md` — 渲染链路、冷却恢复与观测指标
- ~~`docs/PIXELFREE_FALLBACK_TECH_SPEC.md`~~ — **已废弃并移除（2026-04）**
- `app/src/main/java/com/picme/di/AGENTS.md` — DI 层实现规范
- `app/src/main/java/com/picme/features/camera/AGENTS.md` — Camera 模块实现规范
