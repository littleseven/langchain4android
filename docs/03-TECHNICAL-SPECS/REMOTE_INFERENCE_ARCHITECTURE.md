# PicMe 远程推理架构设计：标准 OpenAI 协议与 langchain4j 实现

> **状态**: 已实施 (Implemented)  
> **作者**: RD Agent  
> **日期**: 2026-05-29  
> **更新日期**: 2026-06-19  
> **关联文档**: [AGENTS.md](../../AGENTS.md), [FEATURES.md](../01-PRODUCT/FEATURES.md), [IM_REMOTE_CONTROL_TECH_SPEC.md](IM_REMOTE_CONTROL_TECH_SPEC.md)

---

## 1. 背景与问题

当前 PicMe 的 AI Agent 支持两种推理后端：

| 后端 | 模型 | 协议 | 能力边界 |
|------|------|------|---------|
| **本地 (LOCAL)** | Qwen3.5-2B (MNN-LLM) | 自定义 JSON 数组协议 (method + params) | 单指令意图识别，GBNF 约束输出，短上下文对话 |
| **远程 (REMOTE)** | DeepSeek / Kimi / Claude 等 | 标准 OpenAI Chat Completions API (原生 tool_calls) | 长上下文、强推理、多步骤规划、流式对话 |

**核心问题（已解决）**：远程模型具备长上下文、强推理、原生 Function Calling 能力，但旧架构将其与本地模型等同对待——都走 `processInput() → 单条 JSON → 单命令执行` 路径，浪费了远程模型的核心优势。

**2026-06-22 状态**：ADR-005 已完成协议分离。远程推理链路使用 `:agent-core` 的 `OpenAiChatModel` 标准化，直接使用标准 OpenAI Chat Completions API（含原生 tool_calls、流式、多轮对话）。本地链路保持自定义 JSON 数组协议不变。两条链路完全独立。

---

## 2. 协议对比

### 2.1 本地模型能力边界

```
本地 Qwen3.5-2B (端侧量化)
├── 优势: 零延迟、零隐私风险、离线可用
├── 局限:
│   ├── 上下文短 (~4K)，无法承载多轮复杂对话
│   ├── 推理弱，无法理解条件分支
│   ├── 输出格式单一，GBNF 约束 JSON 数组
│   └── 无记忆，每轮独立处理
└── 适合场景: 高频单指令（拍照、调美颜、切滤镜）
```

### 2.2 远程模型能力空间

```
远程 DeepSeek / Kimi / Claude (128K+ 上下文)
├── 优势:
│   ├── 长上下文 → 可承载完整对话历史 + 场景状态
│   ├── 强推理 → 可解析条件、循环、依赖关系
│   ├── 原生 tool_calls → 标准 OpenAI Function Calling
│   ├── 流式输出 → 首 token 低延迟体验
│   └── 多轮对话 → 自然连续交互
├── 局限: 依赖网络、有成本、有延迟 (~500ms-2s)
└── 适合场景:
    ├── 多指令编排（"先切人像模式，再开磨皮 60，然后拍 3 张"）
    ├── 条件执行（"如果当前是后置摄像头就切前置，否则直接拍"）
    ├── 计划执行（"帮我设置一个夜景人像的参数组合"）
    └── 对话式交互（带记忆的连续对话）
```

---

## 3. 远程推理架构（ADR-005 后）

### 3.1 架构总览

```
用户输入 (Voice/Text/Image/IM消息)
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│                    AgentOrchestrator                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐   │
│  │ ModeDetector│→ │ LOCAL       │  │ REMOTE              │   │
│  │ (LOCAL/     │  │ ┌─────────┐ │  │ ┌─────────────────┐ │   │
│  │  REMOTE/    │  │ │Local   │ │  │ │RemoteInference  │ │   │
│  │  FORCE)     │  │ │Pipeline│ │  │ │Pipeline         │ │   │
│  └─────────────┘  │ └─────────┘ │  │ └─────────────────┘ │   │
│                   └─────────────┘  │         │           │   │
│                                    │    ┌────┴────┐      │   │
│                                    │    ▼         ▼      │   │
│                                    │ L2 Batch  L3 Plan    │   │
│                                    │ FC        Execute   │   │
│                                    │    ┌────┴────┐      │   │
│                                    │    ▼         ▼      │   │
│                                    │ L1 Cache  L4 Chat   │   │
│                                    └─────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
CapabilityRegistry.dispatch() → 命令执行
```

**关键变化（ADR-005）**：
- `InferenceRouter` 已删除，拆分为 `LocalInferencePipeline` + `RemoteInferencePipeline`
- `AdaptiveStrategySelector` 已删除，本地链路不再需要策略分级
- 远程链路使用标准 OpenAI 协议，不再模拟 method/params 格式

### 3.2 远程推理链路四层模型

| 层级 | 模式 | 触发条件 | 协议 | 输出格式 | 适用场景 |
|------|------|---------|------|---------|---------|
| **L1** | 本地意图缓存 | 高频指令命中缓存 | 不走 LLM | 预定义命令 | "拍照"、"切前置" |
| **L2** | Batch Function Calling | 单轮多指令 | OpenAI Chat Completions + tool_calls | `ToolExecutionRequest[]` → `AgentCommand[]` | "磨皮 60 + 美白 30 + 拍照" |
| **L3** | Plan-and-Execute | 条件/依赖/多步骤 | OpenAI Chat Completions + tool_calls | `ExecutionPlan` (含 command 字段) | "如果光线暗就切夜景，否则拍人像" |
| **L4** | 流式 Chat | 开放式对话、闲聊 | OpenAI Chat Completions (stream=true) | 文本流 + 可选 tool_calls | 深度对话、探索性交互 |

---

## 4. 远程推理协议实现

### 4.1 标准 OpenAI Chat Completions 协议

远程推理使用标准 OpenAI Chat Completions API 格式：

**请求格式**：
```json
POST /v1/chat/completions
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "帮我优化这张照片"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "ai_optimize",
        "description": "AI 一键优化图片",
        "parameters": {
          "type": "object",
          "properties": {...},
          "required": [...],
          "additionalProperties": false
        }
      }
    }
  ],
  "tool_choice": "required",
  "stream": false
}
```

**响应格式（tool_calls）**：
```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_xxx",
        "type": "function",
        "function": {
          "name": "ai_optimize",
          "arguments": "{\"image_id\": \"img_123\"}"
        }
      }]
    }
  }]
}
```

**关键规则**：
- `tool_calls` 是 `message` 对象的独立字段，与 `content` 互斥
- 当存在 `tool_calls` 时，`content` 必须为 `null`
- 参数通过 `function.arguments` 传递，为标准 JSON 字符串

### 4.2 langchain4j 标准化实现

```
// :agent-core OpenAiChatModel — 消费标准 OpenAI 协议
class RemoteOrchestrator(config: RemoteModelConfig) {
    
    private val openAiChatModel = OpenAiChatModel.builder()  // :agent-core
        .baseUrl(config.baseUrl)
        .apiKey(config.apiKey)
        .modelName(config.modelId)
        .temperature(config.temperature)
        .maxTokens(config.maxTokens)
        .build()
    
    fun chat(request: ChatRequest): ChatResponse {
        // 直接使用 :agent-core OpenAiChatModel，支持 tool_calls、流式、多轮
    }
}
```

**协议说明**：
```
// 远程推理直接使用 :agent-core OpenAiChatModel
// OpenAiChatModel 支持所有兼容 OpenAI API 的服务（DeepSeek、通义千问等）
// 通过 AiServices 代理构建器 + ChatMemory 实现多轮对话
```

### 4.3 命令解析（ToolCallCommandParser）

远程推理使用 `ToolCallCommandParser` 直接解析标准 tool_calls 格式：

```
object ToolCallCommandParser {
    
    fun parse(request: ToolExecutionRequest, context: AgentContext): AgentCommand {
        val name = request.name()      // 工具名 → 命令类型映射
        val args = request.arguments() // 标准 JSON 参数
        
        return when (name) {
            "switch_filter" -> AgentCommand.SwitchFilter(
                filterType = parseFilterType(args)
            )
            "adjust_beauty" -> AgentCommand.AdjustBeauty(
                settings = parseBeautySettings(args)
            )
            // ... 其他命令
            else -> AgentCommand.TextReply("未知命令: $name")
        }
    }
}
```

**与本地解析器完全隔离**：
- 远程：`ToolCallCommandParser` — 解析 `name` + `arguments` → `AgentCommand`
- 本地：`LocalCommandParser` — 解析 `method` + `params` → `AgentCommand`
- 两者独立文件，无互相调用

### 4.4 DeepSeek 适配

| 适配项 | 实现 | 位置 |
|--------|------|------|
| 禁用 thinking | API 请求自动附加 `thinking: {"type": "disabled"}` | `OpenAiChatModel` 内部处理 |
| strict 模式兼容 | ToolSpec 自动添加 `additionalProperties: false` | `OpenAiChatModel` 内部处理 |
| tool_choice 修复 | `REQUIRED` 正确映射为 `"required"`（非 `"auto"`） | `OpenAiChatModel` 内部处理 |
| content 回退解析 | 当 API 未返回 tool_calls 但 content 含 tool_calls JSON 时，正则提取解析 | `RemoteOrchestrator.parseFallbackToolCalls()` |
| Prompt 规范 | 禁止在 Prompt 中提供具体 tool_calls JSON 示例，避免模型输出到 content | `RemotePromptBuilder` |

---

## 5. 各层详细设计

### 5.1 Layer 1: 本地意图缓存 (Local Intent Cache)

与之前一致。`IntentCache` 在 `RemoteInferencePipeline` 和 `LocalInferencePipeline` 中共享。

### 5.2 Layer 2: Batch Function Calling (默认模式)

**目的**：单轮解析多个独立指令，通过原生 tool_calls 输出命令数组。

**实现**：
```
class RemoteInferencePipeline {
    
    suspend fun processBatch(userInput: String, context: AgentContext): InferenceResult {
        // 1. L1 缓存查询
        val cached = intentCache.match(userInput)
        if (cached != null) return InferenceResult.Local(cached)
        
        // 2. 构建 ChatRequest（含 ToolSpecifications）
        val request = remotePromptBuilder.buildBatchRequest(userInput, context)
        
        // 3. 调用 OpenAiChatModel（标准 OpenAI 协议）
        val response = chatLanguageModel.chat(request)
        
        // 4. 解析 tool_calls
        val toolRequests = response.aiMessage().toolExecutionRequests()
        val commands = ToolCallCommandParser.parseAll(toolRequests, context)
        
        return InferenceResult.Remote(commands)
    }
}
```

**Prompt 设计原则**：
- 使用 langchain4j `ToolSpecification` 定义工具 Schema
- 禁止在 System Prompt 中提供具体的 `{"tool_calls":[...]}` JSON 示例
- 描述 function calling 机制即可，让模型使用原生 API

### 5.3 Layer 3: Plan-and-Execute (计划执行模式)

**目的**：处理条件分支、状态依赖、复杂多步骤任务。

**Plan 输出格式（已更新为标准 tool_calls）**：
```json
{
  "plan_id": "uuid",
  "steps": [
    {
      "step": 1,
      "condition": "currentCamera == BACK",
      "command": {
        "name": "flip_camera",
        "arguments": "{}"
      },
      "description": "切换到前置摄像头"
    }
  ]
}
```

**执行引擎**：
```
class ExecutionEngine {
    
    suspend fun execute(plan: ExecutionPlan, context: AgentContext): ExecutionResult {
        val results = mutableListOf<StepResult>()
        
        for (step in plan.steps) {
            // 1. 检查条件
            if (step.condition != null && !evaluateCondition(step.condition, context)) {
                results.add(StepResult.Skipped(step, "条件不满足"))
                continue
            }
            
            // 2. 执行命令（通过 CapabilityRegistry）
            val result = capabilityRegistry.dispatch(step.action, context)
            results.add(StepResult.Executed(step, result))
            
            // 3. 延迟
            if (step.delayMs > 0) delay(step.delayMs)
        }
        
        return ExecutionResult(plan.planId, results)
    }
}
```

### 5.4 Layer 4: 流式 Chat (对话模式)

**目的**：处理开放式请求、错误恢复、探索性交互。

**实现**：
```
class RemoteOrchestrator {
    
    suspend fun processChat(userInput: String, context: AgentContext): InferenceResult {
        val messages = buildMessagesWithHistory(systemPrompt, userInput, sessionId)
        
        return try {
            val result = streamingChatModel.chat(messages, object : StreamingChatResponseHandler {
                override fun onPartialResponse(partialResponse: String) {
                    // 流式推送文本到 UI
                    emitStreamEvent(StreamEvent.TextDelta(partialResponse))
                }
                
                override fun onCompleteResponse(completeResponse: ChatResponse) {
                    // 完成后检查是否有 tool_calls
                    val toolRequests = completeResponse.aiMessage().toolExecutionRequests()
                    if (toolRequests.isNotEmpty()) {
                        val commands = parseToolCalls(toolRequests, context)
                        continuation.resume(Result.success(InferenceResult.Remote(commands)))
                    } else {
                        continuation.resume(Result.success(
                            InferenceResult.Text(completeResponse.aiMessage().text())
                        ))
                    }
                }
            })
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

---

## 6. 数据模型

### 6.1 AgentCommand 密封类

```
sealed class AgentCommand {
    // 相机命令
    data class AdjustBeauty(val settings: BeautySettings) : AgentCommand()
    data class SwitchFilter(val filterType: FilterType) : AgentCommand()
    data class CapturePhoto : AgentCommand()
    data class FlipCamera : AgentCommand()
    
    // Gallery 命令
    data class ViewMedia(val mediaId: String? = null) : AgentCommand()
    data class SearchMedia(val query: String) : AgentCommand()
    
    // 导航命令
    data class NavigateTo(val destination: String) : AgentCommand()
    object GoBack : AgentCommand()
    
    // 通用命令
    data class TextReply(val message: String) : AgentCommand()
    data class Unknown(val raw: String) : AgentCommand()
    data class Error(val reason: String) : AgentCommand()
}
```

### 6.2 推理结果包装

```
sealed class InferenceResult {
    data class Local(val command: AgentCommand) : InferenceResult()
    data class Remote(val commands: List<AgentCommand>) : InferenceResult()
    data class Text(val message: String) : InferenceResult()
    data class Plan(val plan: ExecutionPlan) : InferenceResult()
    data class Error(val reason: String) : InferenceResult()
}
```

---

## 7. 与现有架构的集成

### 7.1 AiAgentUseCase（Facade）

```
class AiAgentUseCase(
    context: Context,
    agentMode: AiAgentMode = AiAgentMode.REMOTE, // 默认 REMOTE（远程优先）
    privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    localModelId: String = "qwen3_5_2b",
    remoteConfig: RemoteModelConfig? = null,
    forceRemote: Boolean = false
) {
    
    private val orchestrator = AgentOrchestrator(
        localPipeline = LocalInferencePipeline(...),
        remotePipeline = RemoteInferencePipeline(...)
    )
    
    suspend fun processInput(userInput: String, context: AgentContext): InferenceResult {
        return when (configurator.getAgentMode()) {
            AiAgentMode.LOCAL -> localPipeline.process(input)
            AiAgentMode.REMOTE -> remotePipeline.process(input)
            AiAgentMode.OFF -> Result.failure(AgentDisabledException())
        }
    }
}
```

### 7.2 IM 远程控制集成

飞书远程控制复用同一 `RemoteOrchestrator` 和 `RemoteInferencePipeline`：

```
飞书消息 → FeishuChannelHandler → RemoteCommandDispatcher
    → LLM 解析意图（复用 RemoteOrchestrator，独立 System Prompt）
    → CapabilityRegistry.dispatch()
    → 结果 → FeishuChannelHandler.sendMessage/sendImage
```

---

## 8. 性能与成本考量

### 8.1 Token 消耗估算

| 模式 | System Prompt | User Input | Output | 单次总 Token |
|------|--------------|-----------|--------|-------------|
| L1 Cache | 0 | 0 | 0 | 0 |
| L2 Batch | ~800 | ~50 | ~200 | ~1050 |
| L3 Plan | ~1000 | ~100 | ~500 | ~1600 |
| L4 Chat | ~800 | ~50 | ~300 | ~1150 |

### 8.2 延迟估算

| 模式 | 网络 RTT | LLM 生成 | 解析 | 总延迟 |
|------|---------|---------|------|-------|
| L1 | 0 | 0 | 0 | < 10ms |
| L2 | 200-500ms | 200-500ms | 50ms | 450-1050ms |
| L3 | 200-500ms | 500-1000ms | 100ms | 800-1600ms |
| L4 (流式) | 200-500ms | 首 token 50-200ms | 50ms | 250-750ms |

### 8.3 优化策略

1. **L1 缓存预热**：启动时预置 50+ 高频意图
2. **L2 默认化**：80% 场景走 L2，保持简单高效
3. **L3 异步执行**：计划生成后异步执行，不阻塞 UI
4. **L4 流式**：首 token 低延迟，提升对话体验
5. **连接池 + Keep-Alive**：复用 TCP 连接

---

## 9. 验收标准 (AC)

| ID | 验收项 | 优先级 |
|----|--------|--------|
| AC-1 | 远程模式下，"磨皮 60 然后拍照" 能解析为两个 tool_calls 并依次执行 | P0 |
| AC-2 | 远程模式下，"如果是后置就切前置再拍" 能正确执行条件判断 | P0 |
| AC-3 | 本地模式下，所有现有功能保持 100% 兼容 | P0 |
| AC-4 | L1 缓存命中率 > 60%（高频指令） | P1 |
| AC-5 | 远程推理平均延迟 < 1.5s | P1 |
| AC-6 | 支持对话式记忆（多轮上下文） | P2 |
| AC-7 | DeepSeek 模型 tool_calls 成功率 > 95% | P0 |
| AC-8 | 流式聊天首 token 延迟 < 500ms | P1 |

---

## 10. 任务拆分 [agent-task]

### Phase 1: 基础设施 (RD) — 已完成
- [x] `agent-task:remote-infra-001` 实现 `RemoteInferencePipeline`（标准 OpenAI 协议）
- [x] `agent-task:remote-infra-002` 引入 :agent-core OpenAiChatModel 标准化
- [x] `agent-task:remote-infra-003` 实现 `ToolCallCommandParser`（tool_calls → AgentCommand）
- [x] `agent-task:remote-infra-004` 删除 `InferenceRouter`、`AdaptiveStrategySelector` 等冗余组件

### Phase 2: L2 Batch 模式 (RD) — 已完成
- [x] `agent-task:remote-l2-001` 实现 `RemoteOrchestrator.processBatch()`（tool_calls 解析）
- [x] `agent-task:remote-l2-002` 设计 `RemotePromptBuilder`（ToolSpecification 格式）
- [x] `agent-task:remote-l2-003` UI 层适配批量命令串行执行

### Phase 3: L3 Plan 模式 (RD) — 已完成
- [x] `agent-task:remote-l3-001` 实现 `ExecutionEngine` 执行引擎
- [x] `agent-task:remote-l3-002` 更新 Plan 格式为标准 tool_calls（command 字段）
- [x] `agent-task:remote-l3-003` 条件求值器 (`evaluateCondition`)

### Phase 4: L4 流式 Chat (RD) — 已完成
- [x] `agent-task:remote-l4-001` 实现流式聊天（StreamingChatResponseHandler）
- [x] `agent-task:remote-l4-002` ChatMemory 历史管理（DataStoreChatMemoryStore）

### Phase 5: DeepSeek 适配 (RD) — 已完成
- [x] `agent-task:remote-ds-001` 禁用 thinking 模式
- [x] `agent-task:remote-ds-002` strict 模式兼容（additionalProperties: false）
- [x] `agent-task:remote-ds-003` content 回退解析（fallback tool_calls 提取）
- [x] `agent-task:remote-ds-004` Prompt 移除 tool_calls JSON 示例

### Phase 6: 集成与测试 (QA)
- [ ] `agent-task:remote-qa-001` 端到端测试（多指令、条件、降级）
- [ ] `agent-task:remote-qa-002` 性能基准（延迟、Token 消耗）
- [ ] `agent-task:remote-qa-003` DeepSeek 工具调用成功率测试

---

## 11. 附录

### A. 参考文档
- [AGENTS.md](../../AGENTS.md) - Agent First 架构原则
- [FEATURES.md](../01-PRODUCT/FEATURES.md) - 产品功能定义
- [IM_REMOTE_CONTROL_TECH_SPEC.md](IM_REMOTE_CONTROL_TECH_SPEC.md) - IM 远程控制技术规格
- [MNN_LLM_PERFORMANCE_OPTIMIZATION.md](MNN_LLM_PERFORMANCE_OPTIMIZATION.md) - 本地 LLM 性能优化

### B. 相关代码
- [AiAgentUseCase.kt](../../app/src/main/java/com/mamba/picme/domain/usecase/AiAgentUseCase.kt)
- [AgentOrchestrator.kt](../../runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt)
- [RemoteReActAgent.kt](../../runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgent.kt)
- [RemoteCommandDispatcher.kt](../../app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteCommandDispatcher.kt)
- [OpenAiChatModel.java](../../agent-core/src/main/java/com/mamba/model/openai/OpenAiChatModel.java) — :agent-core OpenAI 兼容聊天模型
- [AiServices.java](../../agent-core/src/main/java/com/mamba/service/AiServices.java) — :agent-core AI 服务代理构建器
- [ToolCallCommandParser.kt](../../runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/parser/ToolCallCommandParser.kt)
- [RemotePromptBuilder.kt](../../runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/prompt/RemotePromptBuilder.kt)
