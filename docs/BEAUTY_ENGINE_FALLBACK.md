# Beauty Engine 容灾降级统一说明

> **定位**：跨模块容灾兜底的单一事实来源（SSOT）。
> 
> 本文档统一说明 `beauty-engine`（大美丽）初始化失败或运行异常时的回退策略、状态记录与恢复机制。各模块 AGENTS.md 中涉及容灾降级的描述，均以此文档为准。

---

## 1. 双引擎策略

PicMe 采用主备双引擎策略保障美颜预览的稳定性：

| 引擎 | 职责 | 实现类 | 所在模块 |
|------|------|--------|----------|
| **大美丽 (`BIG_BEAUTY`)** | 默认主引擎，自研 OpenGL ES + EGL 管线 | `GlBeautyPreviewProvider` | `:beauty-engine` |
| **PixelFree (`PIXEL_FREE`)** | 稳定兜底引擎，第三方 SDK | `PixelFreeBeautyPreviewProvider` | `:app` |

---

## 2. 故障回退流程

### 2.1 初始化阶段回退

在 `:app` 模块的 DI 层（`di/AppContainerImpl`）中，按以下流程决定使用哪个引擎：

1. 读取用户配置（或默认策略）`BeautyStrategy`。
2. 若策略为 `BIG_BEAUTY`，先尝试初始化 `GlBeautyPreviewProvider`。
3. 若 `initialize()` 抛出异常（如 GLES 不支持、Shader 编译失败、EGL 上下文创建失败）：
   - 捕获异常；
   - 调用 `BeautyEngineRuntimeState.markGlEngineFallback(reason)` 记录回退原因；
   - 自动降级为 `PixelFreeBeautyPreviewProvider`；
   - 输出 `PicMe:DI` 级别日志，确保问题可追踪。
4. 若策略本身为 `PIXEL_FREE`，直接实例化兜底引擎，不做主引擎尝试。

```kotlin
private val beautyProcessor: BeautyPreviewProvider by lazy {
    val strategy = userPrefs.getBeautyStrategyBlocking()
    when (strategy) {
        BeautyStrategy.PIXEL_FREE -> PixelFreeBeautyPreviewProvider(context)
        BeautyStrategy.BIG_BEAUTY -> {
            try {
                GlBeautyPreviewProvider(context).apply { initialize() }
            } catch (error: Throwable) {
                BeautyEngineRuntimeState.markGlEngineFallback(
                    error.message ?: "unknown"
                )
                Logger.w("PicMe:DI", "大美丽 init failed, fallback to PixelFree", error)
                PixelFreeBeautyPreviewProvider(context)
            }
        }
    }
}
```

### 2.2 运行时异常回退

- `beauty-engine` 内部运行异常（如渲染线程崩溃、FBO 失效）会向上抛出。
- `:app` 层在接收到异常后，可通过 `BeautyEngineRuntimeState` 标记状态，并在下一次启动或页面重建时切换到兜底引擎。
- 详细的运行时冷却与重试机制，请参阅 `docs/BIG_BEAUTY_TECH_SPEC.md`。

---

## 3. 状态记录与查询

`BeautyEngineRuntimeState` 是 `:app` 模块中的单例对象，负责记录并消费回退原因：

- **`markGlEngineFallback(reason: String)`**：记录本次回退原因。
- **`consumeGlEngineFallbackReason(): String?`**：消费并清空回退原因，供 UI 层展示一次性提示（如 Toast / Snackbar）。

**设计意图**：
- 回退原因只会被消费一次，避免重复弹窗。
- UI 层在适当时机（如相机页面 `onResume`）查询并展示降级提示文案，文案必须提取到 `strings.xml` 以支持 I18N。

---

## 4. 依赖方向约束

- `:beauty-engine` 模块**不依赖** `:app` 模块，也不感知 PixelFree 的存在。
- `:beauty-engine` 仅在初始化失败时抛出异常；兜底决策完全由 `:app` 的 DI 层负责。
- 禁止 `:beauty-engine` 的 `egl/` 内部实现类被 `:app` 直接引用；`:app` 只能通过 `api/BeautyPreviewProvider` 与 `api/BeautyPreviewProviderFactory` 访问能力。

---

## 5. 相关文档

- `beauty-engine/AGENTS.md` — 主引擎实现规范
- `beauty-engine/README.md` — 调用方 Quick Start
- `docs/BIG_BEAUTY_TECH_SPEC.md` — 渲染链路、冷却恢复与观测指标
- `docs/PIXELFREE_FALLBACK_TECH_SPEC.md` — PixelFree 兜底引擎集成细节
- `app/src/main/java/com/picme/di/AGENTS.md` — DI 层实现规范
- `app/src/main/java/com/picme/features/camera/AGENTS.md` — Camera 模块实现规范
