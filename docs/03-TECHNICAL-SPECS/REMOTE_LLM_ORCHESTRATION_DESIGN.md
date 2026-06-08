# PicMe 远程 LLM 混合编排架构设计

> **状态**: 设计评审中 (Design Review)  
> **作者**: Claude (AI Assistant)  
> **日期**: 2026-05-29  
> **关联文档**:
> - [`docs/03-TECHNICAL-SPECS/REMOTE_INFERENCE_ARCHITECTURE.md`](../../03-TECHNICAL-SPECS/REMOTE_INFERENCE_ARCHITECTURE.md)
> - [`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](../../02-ARCHITECTURE/AGENT_ARCHITECTURE.md)
> - [`docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`](../../04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md)

---

## 1. 概述

本文档定义 PicMe 远程 LLM（kimi-for-coding）混合编排架构的详细设计。该架构将远程 LLM 的强推理能力（128K 上下文、条件解析、多步骤规划）与本地 LLM 的低延迟优势结合，实现真正的差异化 Agent 体验。

**短期目标**：落地任务编排（L2 Batch + L3 Plan-and-Execute）  
**中长期目标**：扩展场景推荐引擎（方案 B）

---

## 2. 背景与问题

### 2.1 当前状态

| 组件 | 状态 | 说明 |
|------|------|------|
| `LocalLlmEngine` | ✅ 已落地 | Qwen3-1.7B (MNN-LLM)，单指令意图识别 |
| `RemoteInferenceEngine` | 📝 骨架存在 | L2/L3/L4 概念设计，未完全实现 |
| `ExecutionPlan` | 📝 数据类存在 | PlanStep、StepResult、ExecutionResult 已定义 |
| `AdaptiveStrategySelector` | 📝 骨架存在 | 输入特征分析 + 策略选择逻辑已定义 |
| `KimiCodingApiClient` | ✅ 已落地 | Retrofit + OkHttp 客户端 |
| `AgentOrchestrator` | ✅ 已落地 | 统一入口，目前仅支持本地推理 |

### 2.2 核心问题

1. **能力浪费**：远程 kimi-for-coding 具备长上下文 + 强推理 + Function Calling，但当前架构将其与本地模型等同对待——都走 `processInput() → 单条 JSON → 单命令执行` 路径
2. **体验断层**：用户说"先切人像模式，再开磨皮 60，然后拍 3 张"，本地模型无法理解多步骤语义，只能拆解为多次独立交互
3. **差异化不足**：远程模型没有发挥编排优势，用户感知不到远程的价值

### 2.3 设计目标

| 目标 | 优先级 | 验收标准 |
|------|--------|----------|
| 多步骤任务编排 | P0 | 支持 3+ 步骤的 ExecutionPlan 生成与执行 |
| 条件判断执行 | P0 | 支持"如果...否则..."语义的条件步骤 |
| 智能路由决策 | P0 | 根据输入特征自动选择本地/远程 |
| 执行中可交互 | P1 | 支持暂停/跳过/取消，每步状态反馈 |
| 场景推荐预留 | P2 | ExecutionPlan 数据结构预留 sceneContext 字段 |

---

## 3. 架构设计

### 3.1 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                        用户输入                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  InferenceRouter（本地决策，零延迟）                          │
│  ├─ 单指令高频词 → L1 本地缓存（Qwen3-1.7B）                  │
│  ├─ 多指令/条件/步骤词 → L2/L3 远程（Kimi）                  │
│  └─ 开放式问题 → L4 远程对话                                   │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────────┐   ┌─────────────────────────────┐
│   LocalLlmEngine            │   │   RemoteOrchestrator        │
│   (Qwen3-1.7B / MNN)        │   │   (kimi-for-coding)         │
│                             │   │                             │
│   输出: AgentCommand        │   │   模式:                     │
│                             │   │   ├─ Batch → [commands]     │
│                             │   │   ├─ Plan → ExecutionPlan   │
│                             │   │   └─ Chat → 自然语言回复    │
└─────────────────────────────┘   └─────────────────────────────┘
                                              │
                                              ▼
┌─────────────────────────────────────────────────────────────┐
│  ExecutionEngine（本地执行器）                                │
│  ├─ 顺序执行 PlanStep                                        │
│  ├─ 条件判断（condition 字段）                                │
│  ├─ 延迟控制（delayMs 字段，给 UI 反应时间）                   │
│  ├─ 状态反馈（每步成功/失败/跳过 → 上报）                     │
│  └─ 用户控制（暂停 / 跳过当前 / 取消全部）                     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  ExecutionReporter                                          │
│  ├─ 执行报告生成（成功数/失败数/跳过数）                       │
│  ├─ 状态流推送（供 UI 展示进度）                              │
│  └─ 异常上报（失败步骤详情，供远程动态调整）                    │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 组件职责

| 组件 | 职责 | 位置 |
|------|------|------|
| `InferenceRouter` | 输入特征分析 + 本地/远程路由决策 | `agent.core/` (InferenceRouter.kt) |
| `RemoteOrchestrator` | 远程 LLM 交互、Prompt 构建、响应解析 | `agent.core.remote/` (RemoteOrchestrator.kt) |
| `ExecutionEngine` | ExecutionPlan 顺序执行、条件判断、状态管理 | `agent.core/` (ExecutionEngine.kt) |
| `ExecutionReporter` | 执行结果收集、报告生成、UI 状态流推送 | `agent.core/` (ExecutionReporter.kt) |
| `SceneContextCollector` | **预留**：场景上下文收集（时间/光线/偏好） | `agent.core/`（接口层） |

---

## 4. 核心组件详解

### 4.1 InferenceRouter

```kotlin
class InferenceRouter(
    private val localEngine: LocalLlmEngine,
    private val remoteOrchestrator: RemoteOrchestrator,
    private val strategySelector: AdaptiveStrategySelector,
    private val privacyGuard: PrivacyGuard
) {
    /**
     * 处理用户输入，自动路由到本地或远程
     */
    suspend fun processInput(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        // 1. 隐私检查
        val privacyLevel = privacyGuard.classify(userInput)
        if (privacyLevel == PrivacyLevel.RESTRICTED) {
            return InferenceResult.Local(
                localEngine.process(userInput, context)
            )
        }

        // 2. 策略选择
        val strategy = strategySelector.selectStrategy(userInput, context)

        // 3. 路由执行
        return when (strategy) {
            is InferenceStrategy.L1_Cached ->
                InferenceResult.Local(strategy.command)
            is InferenceStrategy.L2_BatchFC ->
                remoteOrchestrator.processBatch(userInput, context)
            is InferenceStrategy.L3_PlanExecute ->
                remoteOrchestrator.processPlan(userInput, context)
            is InferenceStrategy.L4_ReAct ->
                remoteOrchestrator.processChat(userInput, context)
        }
    }
}
```

**路由规则**：

| 输入特征 | 路由目标 | 说明 |
|----------|----------|------|
| 隐私级别 = RESTRICTED | 本地强制 | 含敏感信息（人脸数据、地理位置） |
| 缓存命中 | 本地 L1 | 高频重复指令，零延迟 |
| 单指令 + 无网络 | 本地 | 离线场景降级 |
| 多指令（2+） | 远程 L2 | Batch Function Calling |
| 条件/步骤词（3+） | 远程 L3 | Plan-and-Execute |
| 开放式/疑问句 | 远程 L4 | ReAct 对话 |

### 4.2 RemoteOrchestrator

```kotlin
class RemoteOrchestrator(
    private val codingClient: KimiCodingApiClient,
    private val promptBuilder: PromptBuilder
) {
    /**
     * L2: 批量命令解析
     */
    suspend fun processBatch(
        userInput: String,
        context: AgentContext
    ): InferenceResult.Batch {
        val prompt = promptBuilder.buildBatchPrompt(userInput, context)
        val response = codingClient.chatCompletion(prompt)
        val commands = parseCommandArray(response)
        return InferenceResult.Batch(commands)
    }

    /**
     * L3: 计划生成与执行
     */
    suspend fun processPlan(
        userInput: String,
        context: AgentContext
    ): InferenceResult.Plan {
        val prompt = promptBuilder.buildPlanPrompt(userInput, context)
        val response = codingClient.chatCompletion(prompt)
        val plan = parseExecutionPlan(response)
        return InferenceResult.Plan(plan)
    }

    /**
     * L4: 对话式交互
     */
    suspend fun processChat(
        userInput: String,
        context: AgentContext
    ): InferenceResult.Chat {
        val prompt = promptBuilder.buildChatPrompt(userInput, context)
        val response = codingClient.chatCompletion(prompt)
        return InferenceResult.Chat(response)
    }
}
```

### 4.3 ExecutionEngine

```kotlin
class ExecutionEngine(
    private val capabilityRegistry: CapabilityRegistry,
    private val reporter: ExecutionReporter
) {
    private var currentJob: Job? = null
    private val _stateFlow = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val stateFlow: StateFlow<ExecutionState> = _stateFlow.asStateFlow()

    /**
     * 执行计划
     */
    suspend fun execute(plan: ExecutionPlan): ExecutionResult {
        _stateFlow.value = ExecutionState.Running(plan.steps.size, 0)
        val results = mutableListOf<StepResult>()

        for (step in plan.steps) {
            // 检查是否被取消
            if (_stateFlow.value is ExecutionState.Cancelled) {
                break
            }

            // 检查是否被暂停
            while (_stateFlow.value is ExecutionState.Paused) {
                delay(100)
            }

            // 条件判断
            if (step.condition != null && !evaluateCondition(step.condition)) {
                results.add(StepResult.Skipped(step, "条件不满足: ${step.condition}"))
                continue
            }

            // 执行步骤
            val result = executeStep(step)
            results.add(result)

            // 更新状态
            _stateFlow.value = ExecutionState.Running(
                totalSteps = plan.steps.size,
                completedSteps = results.size
            )

            // 延迟（给 UI 反应时间）
            if (step.delayMs > 0) {
                delay(step.delayMs)
            }
        }

        return ExecutionResult(plan.planId, results).also {
            _stateFlow.value = ExecutionState.Completed(it)
            reporter.report(it)
        }
    }

    fun pause() { _stateFlow.value = ExecutionState.Paused }
    fun resume() { /* 状态机自动恢复 */ }
    fun cancel() { _stateFlow.value = ExecutionState.Cancelled }
    fun skipCurrent() { /* 跳过当前步骤 */ }
}
```

---

## 5. 数据结构

### 5.1 ExecutionPlan 增强

在现有 `ExecutionPlan` 基础上扩展：

```kotlin
data class ExecutionPlan(
    val planId: String,
    val steps: List<PlanStep>,
    val description: String = "",
    // ── 新增字段 ──
    val interactionMode: InteractionMode = InteractionMode.AUTO,
    val sceneContext: SceneContext? = null,      // 预留：场景推荐上下文
    val recommendationReason: String? = null,    // 预留：推荐理由
)

enum class InteractionMode {
    /** 全自动执行，无需用户确认 */
    AUTO,
    /** 先展示计划预览，用户确认后执行 */
    PREVIEW,
    /** 每步执行前请求用户确认 */
    STEP_BY_STEP,
}

data class PlanStep(
    val step: Int,
    val action: AgentCommand,
    val condition: String? = null,
    val description: String = "",
    val delayMs: Long = 0,
    // ── 新增字段 ──
    val fallbackAction: AgentCommand? = null,    // 失败时的降级操作
)
```

### 5.2 执行状态机

```kotlin
sealed class ExecutionState {
    data object Idle : ExecutionState()
    data class Running(val totalSteps: Int, val completedSteps: Int) : ExecutionState()
    data object Paused : ExecutionState()
    data object Cancelled : ExecutionState()
    data class Completed(val result: ExecutionResult) : ExecutionState()
}
```

### 5.3 推理结果密封类

```kotlin
sealed class InferenceResult {
    data class Local(val command: AgentCommand) : InferenceResult()
    data class Batch(val commands: List<AgentCommand>) : InferenceResult()
    data class Plan(val plan: ExecutionPlan) : InferenceResult()
    data class Chat(val message: String) : InferenceResult()
}
```

---

## 6. Prompt 策略

### 6.1 L2 Batch Prompt

```text
System: 你是一个相机控制助手。将用户输入解析为命令数组。

当前相机状态:
- 摄像头: {front/back}
- 美颜设置: {beautySettings}
- 当前滤镜: {filterType}

可用命令:
- CAPTURE_PHOTO: 拍照
- FLIP_CAMERA: 翻转摄像头
- ADJUST_BEAUTY: 调节美颜 (参数: smooth, whiten, slim, eye, lip, blush, brow)
- SWITCH_FILTER: 切换滤镜 (参数: filter)
- SWITCH_SCENE: 切换场景模式 (参数: scene)

输出格式: JSON 数组
用户: {userInput}
```

### 6.2 L3 Plan Prompt

```text
System: 你是一个相机任务编排专家。将用户输入转换为结构化执行计划。

当前相机状态:
{stateSnapshot}

可用命令: [同 L2]

输出格式: ExecutionPlan JSON
字段说明:
- planId: 唯一标识
- description: 计划描述
- steps: 步骤数组
  - step: 步骤序号
  - action: 命令对象
  - condition: 执行条件（可选），支持变量: currentCamera, currentFilter, beautySettings.*
  - description: 步骤描述（给用户看）
  - delayMs: 执行后延迟（毫秒）
  - fallbackAction: 失败时的降级命令（可选）

用户: {userInput}
```

### 6.3 L4 Chat Prompt

```text
System: 你是一个专业摄影助手。通过对话帮助用户拍出更好的照片。
你可以调用工具获取当前相机状态，可以给出调整建议。

工具:
- getCameraState(): 获取当前相机完整状态
- getSupportedFilters(): 获取支持的滤镜列表
- getBeautyRange(): 获取美颜参数范围

当前上下文:
{conversationHistory}

用户: {userInput}
```

---

## 7. 状态反馈与交互

### 7.1 执行中状态流

```
用户输入: "先切人像模式，再开磨皮 60，然后拍 3 张"
    │
    ▼
InferenceRouter → L3 PlanExecute
    │
    ▼
RemoteOrchestrator 生成 ExecutionPlan
    │
    ▼
UI 展示计划预览（PREVIEW 模式）
┌─────────────────────────────────┐
│ 📋 执行计划（5 步）               │
│ 1. 切换人像场景 ✓               │
│ 2. 磨皮调至 60 ✓                │
│ 3. 拍照（第 1/3 张）...         │
│ 4. 拍照（第 2/3 张）待执行      │
│ 5. 拍照（第 3/3 张）待执行      │
│                                 │
│ [暂停] [跳过] [取消]            │
└─────────────────────────────────┘
    │
    ▼
ExecutionEngine 按步骤执行
    │
    ▼
每步结果 → ExecutionReporter → UI 更新
```

### 7.2 用户控制命令

| 操作 | 行为 | 适用场景 |
|------|------|----------|
| 暂停 | 暂停在当前步骤，不释放资源 | 用户想调整参数 |
| 跳过 | 跳过当前步骤，继续下一步 | 某步骤不必要 |
| 取消 | 终止全部执行，释放资源 | 用户改变主意 |
| 确认 | 确认预览计划，开始执行 | PREVIEW 模式 |

---

## 8. 预留接口（为方案 B：场景推荐）

### 8.1 SceneContextCollector 接口

```kotlin
/**
 * 场景上下文收集器
 *
 * 未来收集时间、光线、地理位置、历史偏好等，
 * 为远程 LLM 提供推荐依据。
 */
interface SceneContextCollector {
    suspend fun collect(): SceneContext
}

data class SceneContext(
    val timeOfDay: TimeOfDay,           // MORNING / AFTERNOON / EVENING / NIGHT
    val lightingEstimate: LightingLevel, // DARK / DIM / NORMAL / BRIGHT
    val cameraFacing: CameraFacing,      // FRONT / BACK
    val userPreferenceProfile: PreferenceProfile?, // 用户历史偏好
)

data class PreferenceProfile(
    val favoriteFilters: List<FilterType>,
    val typicalBeautySettings: BeautySettings,
    val preferredSceneModes: List<SceneMode>,
)
```

### 8.2 场景推荐 Prompt 预留

```text
System: 你是一个智能摄影顾问。根据当前场景上下文，
为用户推荐最佳拍摄参数组合。

场景上下文:
{sceneContext}

用户意图: {userIntent}

请生成一个 ExecutionPlan，包含:
1. 推荐的参数调整
2. 推荐理由（自然语言，给用户看）
```

---

## 9. 隐私与安全

### 9.1 隐私分级

| 级别 | 数据类型 | 处理方式 |
|------|----------|----------|
| **PUBLIC** | 一般指令（"拍照""切换滤镜"） | 可上传远程 LLM |
| **SENSITIVE** | 包含人脸/照片内容的描述 | 本地处理优先 |
| **RESTRICTED** | 精确人脸坐标、OCR 文本 | 强制本地，禁止上传 |

### 9.2 隐私守卫规则

```kotlin
object PrivacyGuard {
    fun classify(input: String): PrivacyLevel {
        return when {
            // 包含人脸坐标、OCR 结果等精确数据
            input.contains(Regex("""\d{1,4}\s*,\s*\d{1,4}""")) -> PrivacyLevel.RESTRICTED
            // 包含"我的照片""这张人脸"等敏感描述
            input.containsAny(SENSITIVE_KEYWORDS) -> PrivacyLevel.SENSITIVE
            else -> PrivacyLevel.PUBLIC
        }
    }
}
```

---

## 10. 错误处理

### 10.1 错误分类与恢复

| 错误类型 | 触发条件 | 恢复策略 |
|----------|----------|----------|
| 网络超时 | 远程请求 > 5s | 降级到本地模型 |
| 解析失败 | LLM 输出非预期格式 | 重试 1 次，失败则返回自然语言错误 |
| 执行失败 | Capability 执行异常 | 触发 fallbackAction，无 fallback 则暂停 |
| 条件错误 | condition 表达式非法 | 跳过该步骤，记录错误日志 |

### 10.2 降级策略

```
远程请求失败
    │
    ├─ 网络超时 ──→ 自动降级到本地 Qwen（单指令模式）
    ├─ 解析失败 ──→ 重试 1 次 → 仍失败则提示"请简化指令"
    └─ 服务端错误 ──→ 提示"远程服务暂不可用，使用本地模式"
```

---

## 11. 测试策略

### 11.1 单元测试

| 测试目标 | 测试内容 |
|----------|----------|
| `AdaptiveStrategySelector` | 输入特征分析正确性、策略选择边界条件 |
| `ExecutionEngine` | 步骤顺序执行、条件判断、延迟控制、暂停/取消 |
| `ExecutionReporter` | 结果统计、状态流推送 |
| `PromptBuilder` | Prompt 格式正确性、上下文注入完整性 |

### 11.2 集成测试

| 测试场景 | 输入 | 预期输出 |
|----------|------|----------|
| 多步骤编排 | "先切人像，再开磨皮60，拍3张" | 5 步 Plan，全部成功 |
| 条件执行 | "如果是后置就切前置，然后拍照" | 条件判断正确，步骤执行/跳过符合预期 |
| 执行中取消 | 执行 5 步计划，第 2 步点击取消 | 第 1 步成功，第 2-5 步未执行 |
| 网络降级 | 远程超时触发 | 自动降级到本地，返回单指令结果 |

---

## 12. 实施计划

### Phase 1: 基础设施（1 周）

- [ ] 增强 `ExecutionPlan` 数据结构（interactionMode、fallbackAction）
- [ ] 实现 `ExecutionState` 状态机
- [ ] 增强 `ExecutionEngine`（暂停/跳过/取消、条件判断）
- [ ] 实现 `ExecutionReporter`（状态流、报告生成）

### Phase 2: 远程编排（1 周）

- [ ] 实现 `InferenceRouter`（路由决策逻辑）
- [ ] 增强 `RemoteOrchestrator`（L2/L3/L4 完整实现）
- [ ] 实现 `PromptBuilder`（三种模式 Prompt 模板）
- [ ] 集成到 `AgentOrchestrator`（统一入口改造）

### Phase 3: UI 交互（1 周）

- [ ] 执行计划预览 UI（PREVIEW 模式）
- [ ] 执行进度展示（进度条、步骤状态）
- [ ] 控制按钮（暂停/跳过/取消）
- [ ] 执行结果报告 UI

### Phase 4: 调优与测试（1 周）

- [ ] Prompt 调优（提高解析准确率）
- [ ] 边界测试（条件判断、降级、并发）
- [ ] 性能基准（远程延迟、执行吞吐量）

### Phase 5: 场景推荐预留（未来）

- [ ] 实现 `SceneContextCollector` 接口
- [ ] 收集时间/光线/偏好数据
- [ ] 场景推荐 Prompt 设计
- [ ] A/B 测试推荐效果

---

## 13. 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 远程 LLM 延迟过高（>3s） | 用户体验差 | 超时降级 + 加载动画 + 流式响应 |
| LLM 输出格式不稳定 | 解析失败率高 | 严格 JSON Schema + 重试机制 |
| 网络不稳定导致执行中断 | 任务失败 | 本地状态持久化 + 断点恢复 |
| Token 成本过高 | 运营成本 | 缓存高频意图 + 本地优先策略 |

---

## 附录 A：与现有架构的关系

```
现有: AgentOrchestrator.processUserInput()
           │
           ▼
      改造后: InferenceRouter.processInput()
                 │
        ┌────────┴────────┐
        ▼                 ▼
   LocalLlmEngine    RemoteOrchestrator
   (现有，增强)       (新增)
        │                 │
        │                 ├─ L2 Batch
        │                 ├─ L3 Plan
        │                 └─ L4 Chat
        │                 │
        └────────┬────────┘
                 ▼
           ExecutionEngine
           (新增)
                 │
                 ▼
           ExecutionReporter
           (新增)
```

---

*本文档遵循 PicMe 三层文档体系，实现时需同步更新模块 AGENTS.md 和代码注释。*
