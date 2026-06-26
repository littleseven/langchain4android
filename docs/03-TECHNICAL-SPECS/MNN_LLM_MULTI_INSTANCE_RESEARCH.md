# MNN-LLM 全局模型加载与多实例安全调研报告

> **文档编号**: TECH-RESEARCH-MNN-LLM-MULTI-INSTANCE-001
> **调研日期**: 2026-06-26
> **关联模块**: `:runtime-core` (LocalLlmEngine, MnnLlmClient, MnnResourceManager), `:app` (AgentOrchestrator, ChatViewModel, MediaPager, TagGenerationScheduler)
> **调研人**: RD Agent

---

## 1. 调研背景与问题

### 1.1 用户原始问题

1. **全局有多少处加载 `qwen3_5_2b` 模型的调用？**
2. **如果一处加载了未卸载，另一处调用加载会怎样？**
3. **同一模型的加载是否要全局单例？还是说多例也是OK的？**
4. **请结合 MNN 的特性做判断。**

### 1.2 触发场景

相册预览页点击"图像理解"返回空结果，日志显示 `LLM not loaded, cannot do image inference`。修复过程中发现 `MediaPager.kt` 直接调用 `imageInference()` 前未加载模型，引发对全局模型加载模式的深入调研。

---

## 2. 调研方法

- **代码静态分析**: 全局搜索 `.loadModel()` 调用点，分析调用链路与线程模型
- **源码阅读**: 逐层阅读 `AgentOrchestrator` → `LocalLlmEngine` → `MnnLlmClient` → `MnnResourceManager`
- **MNN 特性分析**: 结合 `MnnGlobalReleaseLock` 注释与实现，理解 MNN 全局状态特性
- **已有文档交叉验证**: 参考 `MNN_RESOURCE_MANAGER_DESIGN.md` 等已有技术规范

---

## 3. 调研结论

### 3.1 全局模型加载调用点（共 8 处）

| # | 调用位置 | 调用方式 | 场景 | 是否已做加载检查 |
|---|----------|----------|------|------------------|
| 1 | `AgentOrchestrator.loadModel()` | `localLlmEngine.loadModel()` | 通用入口 | 内部处理 |
| 2 | `ChatViewModel.kt:583` | `orchestrator.loadModel()` | 聊天页进入 | 有（`isLoaded` 检查） |
| 3 | `AiAgentUseCase.kt:157` | `orchestrator.loadModel()` | Agent 推理 | 有（`isLoaded` 检查） |
| 4 | `TagGenerationScheduler.kt:1040` | `engine.loadModel(..., useOpencl=true)` | Pass 3 OpenCL 尝试 | 有（完整 `ensureModelLoaded` 流程） |
| 5 | `TagGenerationScheduler.kt:1059` | `engine.loadModel(..., useOpencl=false)` | Pass 3 CPU 回退 | 有（完整 `ensureModelLoaded` 流程） |
| 6 | `OpenClGuardian.kt:188` | `engine.loadModel(...)` | OpenCL warmup | 有（Guardian 内部检查） |
| 7 | `ImageTagIndexingWorker.kt:209` | `localLlmEngine.loadModel()` | 后台标签索引 | 有（Worker 内部检查） |
| 8 | `MediaPager.kt:400` | `engine.loadModel(...)` | 相册图像理解 | **修复后添加**（此前缺失） |

**关键发现**：所有调用最终都汇聚到 `AgentOrchestrator.getLlmEngine()` 返回的**同一个** `LocalLlmEngine` 实例。

### 3.2 单例架构链路（调用链分析）

```
调用方（8处）
    │
    ▼
AgentOrchestrator.getInstance(context)  ←── 进程级单例（Double-Check Locking）
    │
    ├── getLlmEngine() ───────────────────────→ LocalLlmEngine（单例，由 AgentConfigurator 持有）
    │                                                   │
    │                                                   ▼
    │                                           MnnLlmClient（成员变量，唯一实例）
    │                                                   │
    │                                                   ▼
    │                                           nativeHandle: Long（指向 C++ Llm 对象）
    │
    └── loadModel(modelId) ─────────────────────→ LocalLlmEngine.loadModel(modelId)
                                                        │
                                                        ▼
                                                engineMutex.withLock（协程 Mutex）
                                                        │
                                                        ▼
                                                MnnLlmClient.load(modelId)
                                                        │
                                                        ▼
                                                if (isLoaded) return true（幂等守卫）
                                                        │
                                                        ▼
                                                MnnGlobalReleaseLock.withOperation {
                                                    nativeHandle = nativeCreate(configPath)
                                                }
```

### 3.3 MNN 多实例安全性判断：安全（在当前架构下）

#### 3.3.1 安全的核心原因

| 保障层级 | 机制 | 说明 |
|----------|------|------|
| **架构层** | 全局单例 `AgentOrchestrator` | 所有调用方通过 `getInstance()` 获取同一实例，自然汇聚到同一 `LocalLlmEngine` |
| **引擎层** | `LocalLlmEngine` 唯一实例 | 由 `AgentConfigurator` 持有，进程生命周期内唯一 |
| **客户端层** | `MnnLlmClient` 唯一实例 | `LocalLlmEngine` 的成员变量，构造函数内创建 |
| **加载层** | `isLoaded` 幂等守卫 | `MnnLlmClient.load()` 第一行检查 `if (isLoaded) return true`，防止重复 `nativeCreate` |
| **线程层** | `engineMutex` + `modelDispatcher` | `LocalLlmEngine.loadModel()` 使用 `Mutex.withLock` + 专用单线程调度器，串行化所有模型操作 |
| **Native 层** | `MnnGlobalReleaseLock` | 所有 MNN native 操作（create/destroy/reset/generate）通过全局锁串行化，防止并发冲突 |

#### 3.3.2 MNN 特性与风险分析

**MNN 全局共享状态问题**（来自 `MnnResourceManager.kt` 注释）：

> `libMNN.so` 的 `EagerBufferAllocator` 和 `Express::Executor` 是全局共享状态，非线程安全。所有涉及 MNN native 资源释放的操作必须通过此锁串行化，防止并发释放导致的 use-after-free / double-free 崩溃。

**当前架构如何规避**：

| 风险场景 | 是否可能发生 | 原因 |
|----------|-------------|------|
| 两个 `MnnLlmClient` 实例同时加载同一模型 | **不可能** | `LocalLlmEngine` 是单例，其内部 `MnnLlmClient` 也是单例 |
| 同一 `MnnLlmClient` 重复调用 `load()` | **安全** | `isLoaded` 守卫 + `engineMutex` 串行化 |
| 加载与卸载并发执行 | **安全** | `engineMutex` 确保互斥；`MnnGlobalReleaseLock` 确保 native 层串行 |
| 多个调用方同时请求加载 | **安全** | `modelDispatcher` 单线程 + `Mutex` 排队 |
| 一处加载后另一处调用推理 | **安全** | 所有操作在同一 `nativeHandle` 上执行，状态一致 |

#### 3.3.3 假设多实例架构的风险（理论分析）

如果**未来**出现多个 `LocalLlmEngine` / `MnnLlmClient` 实例同时加载同一模型：

| 风险 | 严重程度 | 说明 |
|------|----------|------|
| Native 内存重复分配 | 高 | 每个 `nativeCreate` 独立分配 ~4GB Native Heap，极易 OOM |
| MNN 全局状态冲突 | 高 | `EagerBufferAllocator` 非线程安全，并发 create/destroy 可能崩溃 |
| 模型状态不一致 | 中 | 各实例独立 KV Cache，多轮对话历史不共享 |
| 引用计数混乱 | 中 | `MnnResourceManager` 的引用计数与实例生命周期难以对齐 |

**结论**：即使 MNN 底层支持多实例，应用层也应保持**单例模式**，通过 `MnnResourceManager` 的引用计数协调生命周期。

### 3.4 图像理解空结果根因与修复

**根因**：`MediaPager.kt` 的 `onStartVision` 回调直接调用 `engine.imageInference()`，未先加载模型。

**修复**：在调用 `imageInference()` 前添加 `ensureModelLoaded` 模式：

```kotlin
// 修复后（MediaPager.kt）
val orchestrator = AgentOrchestrator.getInstance(context)
val engine = orchestrator.getLlmEngine()

val modelKey = "qwen3_5_2b"
if (!engine.isLoaded) {
    val loadResult = engine.loadModel(modelKey, useOpencl = false)
    if (loadResult.isFailure) {
        // 处理加载失败
        return@launch
    }
}

val result = engine.imageInference(bitmap, systemPrompt, userPrompt)
```

**正确模式参考**（`ChatViewModel.kt`）：

```kotlin
if (!orchestrator.isModelLoaded()) {
    val loadResult = orchestrator.loadModel()
    // ... 处理结果
}
```

### 3.5 模型加载最佳实践模式

```kotlin
/**
 * 标准模型加载模式（适用于所有调用方）
 */
suspend fun safeModelInference(
    orchestrator: AgentOrchestrator,
    modelKey: String = "qwen3_5_2b",
    inferenceBlock: suspend (LocalLlmEngine) -> String
): String {
    val engine = orchestrator.getLlmEngine()
    
    // Step 1: 确保模型已加载（幂等操作）
    if (!engine.isLoaded) {
        val loadResult = engine.loadModel(modelKey, useOpencl = false)
        if (loadResult.isFailure) {
            return "模型加载失败: ${loadResult.exceptionOrNull()?.message}"
        }
    }
    
    // Step 2: 执行推理
    return inferenceBlock(engine)
}
```

---

## 4. 架构设计建议

### 4.1 当前架构优势

1. **单例汇聚**：所有调用方通过 `AgentOrchestrator` 汇聚到同一 `LocalLlmEngine`，天然避免多实例竞争
2. **多层幂等**：`isLoaded` 守卫 + `engineMutex` + `MnnGlobalReleaseLock`，三重保护
3. **统一协调**：`MnnResourceManager` 引用计数管理生命周期，支持场景驱动的自动卸载

### 4.2 潜在改进点

| 改进项 | 优先级 | 说明 |
|--------|--------|------|
| 封装 `ensureModelLoaded()` 到 `AgentOrchestrator` | P1 | 避免各调用方重复实现加载检查逻辑 |
| 添加 `loadModel()` 调用点日志审计 | P2 | 便于追踪谁在何时触发了模型加载 |
| 统一 `useOpencl` 参数决策 | P2 | 当前各调用方自行决定，应由 `OpenClGuardian` 统一管理 |
| 文档化加载契约 | P2 | 明确各调用方的加载责任（调用前是否必须加载） |

---

## 5. 关键代码引用

### 5.1 单例实现

- `AgentOrchestrator.getInstance()`: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt:61-65`
- `LocalLlmEngine` 实例持有: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentConfigurator.kt`（通过 `configurator.localLlmEngine`）
- `MnnResourceManager.getInstance()`: `runtime-core/src/main/java/com/mamba/picme/agent/core/platform/mnn/MnnResourceManager.kt:99-103`

### 5.2 线程安全

- `LocalLlmEngine.loadModel()`: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/LocalLlmEngine.kt:116-168`
- `MnnLlmClient.load()`: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/MnnLlmClient.kt:48-108`
- `MnnGlobalReleaseLock`: `runtime-core/src/main/java/com/mamba/picme/agent/core/platform/mnn/MnnResourceManager.kt:32-72`

### 5.3 修复提交

- `MediaPager.kt` 模型加载修复: `app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt`（约第 393-400 行）

---

## 6. 关联文档

- `docs/03-TECHNICAL-SPECS/MNN_RESOURCE_MANAGER_DESIGN.md` — MNN 资源协调管理器设计
- `docs/03-TECHNICAL-SPECS/MNN_LLM_PERFORMANCE_OPTIMIZATION.md` — MNN-LLM 性能优化指南
- `docs/03-TECHNICAL-SPECS/MNN_UNLOAD_TRIGGER_MECHANISM.md` — 模型卸载触发机制
- `docs/03-TECHNICAL-SPECS/MNN_UNLOAD_TEST_CASES.md` — 卸载测试用例
- `AGENTS.md` — Agent First 研发流程与协作规范

---

> **维护者**: RD Agent
> **最后更新**: 2026-06-26
> **下次 review**: 当新增模型加载调用点或 MNN 版本升级时
