# PicMe 远程推理架构设计：分层自适应推理模式

> **状态**: 草案 (Draft)  
> **作者**: CO Agent → PM Agent → RD Agent  
> **日期**: 2026-05-29  
> **关联文档**: [AGENTS.md](../../AGENTS.md), [FEATURES.md](../01-PRODUCT/FEATURES.md)

---

## 1. 背景与问题

当前 PicMe 的 AI Agent 支持两种推理后端：

| 后端 | 模型 | 上下文长度 | 能力边界 |
|------|------|-----------|---------|
| **本地 (LOCAL)** | Qwen3-1.7B (MNN) | ~4K tokens | 单指令意图识别，JSON 输出，短上下文对话 |
| **远程 (REMOTE)** | kimi-for-coding  | 128K+ tokens | 单指令解析，与本地共享同一 prompt |

**核心问题**：远程模型具备长上下文、强推理、多步骤规划能力，但当前架构将其与本地模型等同对待——都走 `processInput() → 单条 JSON → 单命令执行` 路径，浪费了远程模型的核心优势。

**目标**：为远程推理设计分层自适应架构，区分本地/远程的能力边界，释放远程模型的编排潜力。

---

## 2. 能力差异分析

### 2.1 本地模型能力边界

```
本地 Qwen3-1.7B (端侧量化)
├── 优势: 零延迟、零隐私风险、离线可用
├── 局限:
│   ├── 上下文短 (~4K)，无法承载多轮复杂对话
│   ├── 推理弱，无法理解条件分支（"如果光线暗就切夜景，否则拍人像"）
│   ├── 输出格式单一，只能输出单行 JSON
│   └── 无记忆，每轮独立处理
└── 适合场景: 高频单指令（拍照、调美颜、切滤镜）
```

### 2.2 远程模型能力空间

```
远程 kimi-for-coding (128K 上下文)
├── 优势:
│   ├── 长上下文 → 可承载完整对话历史 + 场景状态
│   ├── 强推理 → 可解析条件、循环、依赖关系
│   ├── 结构化输出 → 可输出命令数组、执行计划
│   └── 工具调用 → 支持 Function Calling 模式
├── 局限: 依赖网络、有成本、有延迟 (~500ms-2s)
└── 适合场景:
    ├── 多指令编排（"先切人像模式，再开磨皮 60，然后拍 3 张"）
    ├── 条件执行（"如果当前是后置摄像头就切前置，否则直接拍"）
    ├── 计划执行（"帮我设置一个夜景人像的参数组合"）
    └── 对话式交互（带记忆的连续对话）
```

---

## 3. 分层自适应推理架构

### 3.1 架构总览

```
用户输入
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│                    InferenceRouter                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ ModeDetector│→ │ LOCAL       │  │ REMOTE              │ │
│  │ (LOCAL/     │  │ ┌─────────┐ │  │ ┌─────────────────┐ │ │
│  │  REMOTE/    │  │ │Single   │ │  │ │AdaptiveStrategy │ │ │
│  │  FORCE)     │  │ │Command  │ │  │ │Selector         │ │ │
│  └─────────────┘  │ │Executor │ │  │ └─────────────────┘ │ │
│                   │ └─────────┘ │  │         │           │ │
│                   └─────────────┘  │    ┌────┴────┐      │ │
│                                    │    ▼         ▼      │ │
│                                    │ Layer2    Layer3    │ │
│                                    │ Batch     Plan      │ │
│                                    │ FC        Execute   │ │
│                                    │    ┌────┴────┐      │ │
│                                    │    ▼         ▼      │ │
│                                    │ Layer1    Layer4    │ │
│                                    │ Cache     ReAct     │ │
│                                    └─────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
CapabilityRegistry.dispatch() → 命令执行
```

### 3.2 四层远程推理模式

| 层级 | 模式 | 触发条件 | LLM Prompt 策略 | 输出格式 | 适用场景 |
|------|------|---------|----------------|---------|---------|
| **L1** | 本地意图缓存 | 高频指令命中缓存 | 不走 LLM | 预定义命令 | "拍照"、"切前置" |
| **L2** | Batch Function Calling | 单轮多指令 | system + tools 定义 | `commands[]` 数组 | "磨皮 60 + 美白 30 + 拍照" |
| **L3** | Plan-and-Execute | 条件/依赖/多步骤 | 计划生成 + 分步执行 | `plan[]` + 状态机 | "如果光线暗就切夜景，否则拍人像" |
| **L4** | ReAct (兜底) | L2/L3 失败或不确定 | 思考-行动-观察循环 | `thought + action` | 开放式探索、错误恢复 |

---

## 4. 各层详细设计

### 4.1 Layer 1: 本地意图缓存 (Local Intent Cache)

**目的**：消除高频指令的 LLM 调用开销。

```kotlin
class IntentCache {
    private val cache = LruCache<String, AgentCommand>(100)
    
    // 预置高频意图映射
    private val presetIntents = mapOf(
        "拍照" to AgentCommand.CapturePhoto,
        "拍一张" to AgentCommand.CapturePhoto,
        "切前置" to AgentCommand.FlipCamera,
        "切后置" to AgentCommand.FlipCamera,
        // ... 更多
    )
    
    fun match(input: String): AgentCommand? {
        // 1. 精确匹配
        presetIntents[input.trim()]?.let { return it }
        // 2. Lru 缓存匹配
        cache.get(input.trim())?.let { return it }
        // 3. 模糊匹配（编辑距离 < 2）
        return fuzzyMatch(input)
    }
}
```

**命中规则**：
- 本地/远程模式下都先查 L1
- 命中则直接返回，零延迟
- 未命中才进入 LLM 推理

---

### 4.2 Layer 2: Batch Function Calling (默认模式)

**目的**：单轮解析多个独立指令，输出命令数组。

**Prompt 设计**：

```
你是 PicMe 相机的 AI 助手。用户可能在一句话中包含多个指令。

【输出格式 - 严格 JSON 数组】
[
  {"method":"adjust_beauty","params":{"smoothing":60}},
  {"method":"adjust_beauty","params":{"whitening":30}},
  {"method":"capture","params":{}}
]

【规则】
1. 每个数组元素是一个独立命令
2. 命令按数组顺序依次执行
3. 如果用户输入包含聊天内容，最后一个元素可以是 {"method":"text_reply","params":{"message":"..."}}
4. 绝对不要输出任何其他文字

【当前相机状态】
{state_json}

【可用命令】
{capabilities}
```

**Kotlin 实现**：

```kotlin
// 新增命令类型
sealed class AgentCommand {
    // ... 现有命令 ...
    
    /**
     * 批量命令（仅远程模式支持）
     */
    data class BatchExecute(val commands: List<AgentCommand>) : AgentCommand()
}

// 远程推理入口
class RemoteInferenceEngine {
    
    suspend fun processBatch(
        userInput: String,
        context: AgentContext
    ): Result<List<AgentCommand>> {
        val prompt = buildBatchPrompt(userInput, context)
        
        val response = codingClient.chat(
            system = prompt.system,
            user = prompt.user,
            // 关键：请求结构化输出
            responseFormat = "json_array"
        )
        
        return parseCommandArray(response)
    }
}
```

**执行流程**：

```
用户: "磨皮开到 60，美白 30，然后拍一张"
    │
    ▼
L1 缓存未命中
    │
    ▼
L2 Batch FC
    │
    ▼
LLM 输出:
[
  {"method":"adjust_beauty","params":{"smoothing":60}},
  {"method":"adjust_beauty","params":{"whitening":30}},
  {"method":"capture","params":{}}
]
    │
    ▼
解析为 AgentCommand.BatchExecute([...])
    │
    ▼
CapabilityRegistry 逐个 dispatch
    │
    ▼
UI 反馈: "已调整磨皮 60、美白 30，拍照完成"
```

---

### 4.3 Layer 3: Plan-and-Execute (计划执行模式)

**目的**：处理条件分支、状态依赖、复杂多步骤任务。

**触发条件**：
- 用户输入包含条件词（"如果...就..."、"先...再..."、"除非..."）
- 输入包含 3 个以上步骤
- L2 Batch 执行失败（命令间有依赖冲突）

**两阶段架构**：

```
┌─────────────────┐     ┌─────────────────┐
│  Phase 1: Plan  │────→│ Phase 2: Execute│
│  生成执行计划     │     │  按计划逐步执行   │
└─────────────────┘     └─────────────────┘
```

**Plan 输出格式**：

```json
{
  "plan_id": "uuid",
  "steps": [
    {
      "step": 1,
      "condition": "currentCamera == BACK",
      "action": {"method":"flip_camera","params":{}},
      "description": "切换到前置摄像头"
    },
    {
      "step": 2,
      "action": {"method":"adjust_beauty","params":{"smoothing":80,"whitening":60}},
      "description": "设置人像美颜参数"
    },
    {
      "step": 3,
      "action": {"method":"capture","params":{}},
      "description": "拍照"
    }
  ]
}
```

**执行引擎**：

```kotlin
class PlanExecutor(
    private val registry: CapabilityRegistry
) {
    suspend fun execute(plan: ExecutionPlan, context: AgentContext): ExecutionResult {
        val results = mutableListOf<StepResult>()
        
        for (step in plan.steps) {
            // 1. 检查条件
            if (step.condition != null && !evaluateCondition(step.condition, context)) {
                results.add(StepResult.Skipped(step, "条件不满足"))
                continue
            }
            
            // 2. 执行命令
            val result = registry.dispatch(step.action, context)
            results.add(StepResult.Executed(step, result))
            
            // 3. 更新上下文（状态变更反馈到下一步）
            context.updateFromResult(result)
            
            // 4. 延迟（给 UI 反应时间）
            if (step.delayMs > 0) delay(step.delayMs)
        }
        
        return ExecutionResult(plan.planId, results)
    }
}
```

**示例**：

```
用户: "如果现在是后置摄像头，先切到前置，然后设置磨皮 80 美白 60，最后拍一张"

Plan:
  Step 1: condition="lensFacing == BACK" → flip_camera
  Step 2: adjust_beauty(smoothing=80, whitening=60)
  Step 3: capture

执行:
  → 检查当前是后置 ✓
  → 执行 flip_camera
  → 执行 adjust_beauty
  → 执行 capture
  → 返回: "已切换前置摄像头，设置人像美颜，拍照完成"
```

---

### 4.4 Layer 4: ReAct (兜底模式)

**目的**：处理开放式请求、错误恢复、探索性交互。

**触发条件**：
- L2/L3 解析失败
- 用户输入无法匹配任何已知命令模式
- 用户要求"你能做什么"等探索性问题

**ReAct 循环**：

```
用户输入
    │
    ▼
┌────────────────────────────────────────┐
│ Thought: 用户想了解相机功能，我应该    │
│          列出可用的 AI 控制命令        │
│ Action: text_reply("我可以帮你...")    │
│ Observation: (无需观察，直接回复)      │
└────────────────────────────────────────┘
```

**与 L2/L3 的区别**：

| 特性 | L2 Batch | L3 Plan | L4 ReAct |
|------|---------|---------|---------|
| 输出确定性 | 高 | 中 | 低 |
| 执行步骤 | 预定义数组 | 预定义计划 | 动态决策 |
| 是否需要观察反馈 | 否 | 是（状态更新） | 是（每步都观察） |
| 最大步数 | 1 轮 | N 步（预定义） | 限步（如 5 步） |
| 超时 | 单次 API | 单次 API + 执行 | 多次 API |

---

## 5. 模式自动选择策略

### 5.1 选择器逻辑

```kotlin
class AdaptiveStrategySelector {
    
    fun selectStrategy(userInput: String, context: AgentContext): InferenceStrategy {
        // 1. 先查 L1 缓存
        IntentCache.match(userInput)?.let {
            return InferenceStrategy.L1_Cached(it)
        }
        
        // 2. 分析输入特征
        val features = analyzeInput(userInput)
        
        return when {
            // 条件/依赖/多步骤 → L3
            features.hasConditionals || features.stepCount >= 3 ->
                InferenceStrategy.L3_PlanExecute
            
            // 多指令但无依赖 → L2
            features.commandCount >= 2 ->
                InferenceStrategy.L2_BatchFC
            
            // 单指令 → L2（默认）
            features.commandCount == 1 ->
                InferenceStrategy.L2_BatchFC
            
            // 开放式/探索性 → L4
            features.isOpenEnded || features.isQuestion ->
                InferenceStrategy.L4_ReAct
            
            // 兜底
            else -> InferenceStrategy.L2_BatchFC
        }
    }
    
    private fun analyzeInput(input: String): InputFeatures {
        return InputFeatures(
            hasConditionals = conditionKeywords.any { input.contains(it) },
            stepCount = stepKeywords.count { input.contains(it) } + 1,
            commandCount = estimateCommandCount(input),
            isOpenEnded = openEndedPatterns.any { it.matches(input) },
            isQuestion = input.endsWith("?") || input.endsWith("？") || questionWords.any { input.contains(it) }
        )
    }
    
    companion object {
        val conditionKeywords = listOf("如果", "假如", "要是", "除非", "否则", "不然")
        val stepKeywords = listOf("先", "然后", "接着", "再", "最后", "之后")
        val questionWords = listOf("什么", "怎么", "哪些", "多少", "吗", "呢")
    }
}
```

### 5.2 降级策略

```
L3 Plan 生成失败
    │
    ▼ 自动降级
L2 Batch（忽略条件，按顺序执行）
    │
    ▼ 仍失败
L4 ReAct（让 LLM 自由回复，引导用户）
    │
    ▼ 仍失败
本地规则匹配 / 友好错误提示
```

---

## 6. 数据模型扩展

### 6.1 新增 AgentCommand 类型

```kotlin
sealed class AgentCommand {
    // ==================== 现有命令 ====================
    // ... (AdjustBeauty, SwitchFilter, etc.)
    
    // ==================== 远程模式专用 ====================
    
    /**
     * 批量执行命令（L2）
     */
    data class BatchExecute(val commands: List<AgentCommand>) : AgentCommand()
    
    /**
     * 执行计划（L3）
     */
    data class ExecutePlan(val plan: ExecutionPlan) : AgentCommand()
    
    /**
     * 条件命令（L3 Plan 内部使用）
     */
    data class ConditionalExecute(
        val condition: String,
        val trueBranch: AgentCommand,
        val falseBranch: AgentCommand? = null
    ) : AgentCommand()
}

/**
 * 执行计划（L3）
 */
data class ExecutionPlan(
    val planId: String,
    val steps: List<PlanStep>,
    val description: String = ""
)

data class PlanStep(
    val step: Int,
    val action: AgentCommand,
    val condition: String? = null,
    val description: String = "",
    val delayMs: Long = 0
)
```

### 6.2 远程推理结果包装

```kotlin
sealed class RemoteInferenceResult {
    /**
     * L2: 批量命令
     */
    data class BatchCommands(val commands: List<AgentCommand>) : RemoteInferenceResult()
    
    /**
     * L3: 执行计划
     */
    data class Plan(val plan: ExecutionPlan) : RemoteInferenceResult()
    
    /**
     * L4: 文本回复（ReAct 思考结果）
     */
    data class TextReply(val message: String, val thought: String? = null) : RemoteInferenceResult()
    
    /**
     * 解析失败
     */
    data class ParseError(val rawResponse: String, val reason: String) : RemoteInferenceResult()
}
```

---

## 7. 与现有架构的集成

### 7.1 AiAgentUseCase 改造

```kotlin
class AiAgentUseCase(...) {
    
    private val remoteEngine = RemoteInferenceEngine(codingClient, model)
    private val strategySelector = AdaptiveStrategySelector()
    private val planExecutor = PlanExecutor(capabilityRegistry)
    
    suspend fun processInput(
        userInput: String,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> = withContext(Dispatchers.IO) {
        
        when (currentMode) {
            AiAgentMode.LOCAL, AiAgentMode.OFF -> {
                // 本地模式：保持现有逻辑不变
                if (forceRemoteMode) {
                    return@withContext processRemote(userInput, currentState)
                }
                // ... 本地推理
            }
            
            AiAgentMode.REMOTE -> {
                return@withContext processRemote(userInput, currentState)
            }
        }
    }
    
    private suspend fun processRemote(
        userInput: String,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> {
        val context = buildAgentContext(currentState)
        
        // 1. 选择推理策略
        val strategy = strategySelector.selectStrategy(userInput, context)
        
        return when (strategy) {
            is InferenceStrategy.L1_Cached ->
                Result.success(mapAgentCommand(strategy.command))
            
            is InferenceStrategy.L2_BatchFC -> {
                val result = remoteEngine.processBatch(userInput, context)
                result.map { batch ->
                    if (batch.commands.size == 1) {
                        mapAgentCommand(batch.commands.first())
                    } else {
                        AiAgentCommand.BatchExecute(batch.commands.map { mapAgentCommand(it) })
                    }
                }
            }
            
            is InferenceStrategy.L3_PlanExecute -> {
                val planResult = remoteEngine.generatePlan(userInput, context)
                planResult.map { plan ->
                    // 执行计划并返回汇总结果
                    val executionResult = planExecutor.execute(plan, context)
                    AiAgentCommand.TextReply(formatExecutionSummary(executionResult))
                }
            }
            
            is InferenceStrategy.L4_ReAct -> {
                val reactResult = remoteEngine.react(userInput, context)
                Result.success(AiAgentCommand.TextReply(reactResult.message))
            }
        }
    }
}
```

### 7.2 UI 层适配

```kotlin
// CameraScreen 中处理批量命令
fun onAiAgentCommand(command: AiAgentCommand) {
    when (command) {
        is AiAgentCommand.BatchExecute -> {
            // 串行执行批量命令，每步有 UI 反馈
            scope.launch {
                command.commands.forEachIndexed { index, cmd ->
                    executeSingleCommand(cmd)
                    if (index < command.commands.size - 1) {
                        delay(200) // 给 UI 反应时间
                    }
                }
            }
        }
        // ... 其他命令
    }
}
```

---

## 8. 性能与成本考量

### 8.1 Token 消耗估算

| 模式 | System Prompt | User Input | Output | 单次总 Token | 成本（按 1元/1M tokens） |
|------|--------------|-----------|--------|-------------|----------------------|
| L1 Cache | 0 | 0 | 0 | 0 | 0 |
| L2 Batch | ~800 | ~50 | ~200 | ~1050 | ~0.001 元 |
| L3 Plan | ~1000 | ~100 | ~500 | ~1600 | ~0.0016 元 |
| L4 ReAct | ~800 | ~50 | ~300 | ~1150 | ~0.001 元 |

### 8.2 延迟估算

| 模式 | 网络 RTT | LLM 生成 | 解析 | 总延迟 |
|------|---------|---------|------|-------|
| L1 | 0 | 0 | 0 | < 10ms |
| L2 | 200-500ms | 200-500ms | 50ms | 450-1050ms |
| L3 | 200-500ms | 500-1000ms | 100ms | 800-1600ms |
| L4 | 200-500ms | 300-800ms | 50ms | 550-1350ms |

### 8.3 优化策略

1. **L1 缓存预热**：启动时预置 50+ 高频意图
2. **L2 默认化**：80% 场景走 L2，保持简单高效
3. **L3 异步执行**：计划生成后异步执行，不阻塞 UI
4. **L4 限步**：ReAct 最多 3 轮交互，防止无限循环

---

## 9. 验收标准 (AC)

| ID | 验收项 | 优先级 |
|----|--------|--------|
| AC-1 | 远程模式下，"磨皮 60 然后拍照" 能解析为两个命令并依次执行 | P0 |
| AC-2 | 远程模式下，"如果是后置就切前置再拍" 能正确执行条件判断 | P0 |
| AC-3 | 本地模式下，所有现有功能保持 100% 兼容 | P0 |
| AC-4 | L1 缓存命中率 > 60%（高频指令） | P1 |
| AC-5 | 远程推理平均延迟 < 1.5s | P1 |
| AC-6 | 支持对话式记忆（多轮上下文） | P2 |

---

## 10. 任务拆分 [kimi-task]

### Phase 1: 基础设施 (RD)
- [ ] `kimi-task:remote-infra-001` 实现 `IntentCache` L1 缓存
- [ ] `kimi-task:remote-infra-002` 实现 `AdaptiveStrategySelector` 策略选择器
- [ ] `kimi-task:remote-infra-003` 扩展 `AgentCommand` 支持 BatchExecute / ExecutePlan

### Phase 2: L2 Batch 模式 (RD)
- [ ] `kimi-task:remote-l2-001` 实现 `RemoteInferenceEngine.processBatch()`
- [ ] `kimi-task:remote-l2-002` 设计 Batch Prompt Template
- [ ] `kimi-task:remote-l2-003` UI 层适配批量命令串行执行

### Phase 3: L3 Plan 模式 (RD)
- [ ] `kimi-task:remote-l3-001` 实现 `PlanExecutor` 执行引擎
- [ ] `kimi-task:remote-l3-002` 实现条件求值器 (`evaluateCondition`)
- [ ] `kimi-task:remote-l3-003` 设计 Plan Prompt Template

### Phase 4: L4 ReAct 模式 (RD)
- [ ] `kimi-task:remote-l4-001` 实现 ReAct 循环（限步 + 超时）

### Phase 5: 集成与测试 (QA)
- [ ] `kimi-task:remote-qa-001` 端到端测试（多指令、条件、降级）
- [ ] `kimi-task:remote-qa-002` 性能基准（延迟、Token 消耗）

---

## 11. 附录

### A. 参考文档
- [AGENTS.md](../../AGENTS.md) - Agent First 架构原则
- [FEATURES.md](../01-PRODUCT/FEATURES.md) - 产品功能定义
- [MNN_LLM_ANDROID.md](MNN_LLM_ANDROID.md) - 本地 LLM 接入规范

### B. 相关代码
- [AiAgentUseCase.kt](../../app/src/main/java/com/picme/domain/usecase/AiAgentUseCase.kt)
- [AgentOrchestrator.kt](../../app/src/main/java/com/picme/domain/agent/AgentOrchestrator.kt)
- [AgentCommands.kt](../../app/src/main/java/com/picme/domain/agent/model/AgentCommands.kt)
