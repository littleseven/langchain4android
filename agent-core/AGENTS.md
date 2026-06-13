# Agent Core 模块

## 状态

**已激活** — 纯 Kotlin 模块（`java-library` + `kotlin("jvm")`），包含 Agent Runtime 全部组件。

## 模块定位

`:agent-core` 是 **Agent Runtime 核心**，承载从 `app/domain/agent/` 迁移出的所有 Agent 组件。提供平台无关的泛型接口和 Android 无关的纯 Kotlin 实现：

### 核心组件（43 个文件，8 个子包）

| 组件 | 职责 | 包路径 |
|------|------|--------|
| `AgentOrchestrator` | 应用级单例，统一入口，管理本地模型生命周期 | `agent.core` |
| `CapabilityRegistry` | Capability 注册/查询/命令分发，跨页面命令队列；同时实现 `ToolProvider` 支持 Tool Calling | `agent.core` |
| `LocalLlmEngine` | 本地 Qwen3-1.7B MNN-LLM 推理封装，实现 `ChatLanguageModel` / `StreamingChatLanguageModel` | `agent.core` |
| `AgentCommandParser` | LLM 响应解析为 AgentCommand | `agent.core` |
| `InferenceRouter` | 隐私分级 + 本地/远程路由 | `agent.core` |
| `ExecutionEngine` | 顺序执行 ExecutionPlan | `agent.core` |
| `ExecutionReporter` | 执行过程报告，结构化日志 | `agent.core` |
| `MemoryManager` | 对话历史管理 | `agent.core` |
| `PrivacyGuard` | 输入内容隐私分级 | `agent.core` |
| `PromptBuilder` | System prompt 动态构建 | `agent.core` |
| `SceneManager` | 页面场景状态管理 | `agent.core` |
| `AgentConfigurator` | Agent 配置管理 | `agent.core` |
| `Capability<T,C,P,A>` | 泛型 Capability 接口 | `agent.core` |
| `CapabilityHost` | Capability 宿主绑定 | `agent.core` |
| `CommandExecutor<T,C,P,A>` | 命令执行器（超时+异常） | `agent.core` |
| `CrossPageCommandQueue<T,C,P,A>` | 跨页面命令队列（TTL+重试） | `agent.core` |
| `FaceDetectionProvider` | 人脸检测结果提供 | `agent.core` |
| `Logger` | 日志接口 | `agent.core` |

### 子包

| 子包 | 内容 | 说明 |
|------|------|------|
| `langchain4j/` | `ChatLanguageModel`, `StreamingChatLanguageModel`, `ChatMessage`, `ChatRequest`, `ChatResponse`, `ToolSpecification`, `ToolExecutionRequest` | 与 LangChain4j 对齐的模型 API 层（无外部依赖） |
| `tool/` | `ToolOrchestrator`, `ToolCallingChatLanguageModel`, `ToolCallingOutputParser`, `ToolPromptBuilder` | Tool/Function Calling 实现 |
| `llm/` | `MnnLlmClient`, `LlmModelManager`, `LocalLlmEngine` | MNN LLM 客户端、模型管理与本地推理引擎 |
| `mnn/` | `MnnResourceManager` | MNN 资源管理 |
| `model/` | `AgentCommands`, `AgentModels`, `AiAgentConfig`, `ExecutionState`, `InferenceResult`, `MediaAsset`, `PageContext`, `SceneContext`, `RemoteModelConfig` | 数据模型 |
| `voice/` | `AsrEngine`, `VadDetector`, `MnnAsrClient`, `AudioRecorder`, `SherpaMnnAsrEngine` | 语音交互 |
| `remote/` | `RemoteOrchestrator`, `UnifiedRemoteClient`, `AdaptiveStrategySelector`, `IntentCache`, `ExecutionPlan` + `kimi/` (KimiCodingApiClient 等) + `openai/` (OpenAiApiClient 等) | 远程推理编排 |

## 设计原则

**零业务依赖**：不依赖 `BeautySettings`、`FilterType`、`MediaType`、`ExecutionPlan`（业务）等业务类型。
**泛型化**：通过 `<T, C, P, A>` 类型参数让业务模块注入具体类型。
**纯 Kotlin**：使用 `java-library` + `kotlin("jvm")` 插件，无 Android 依赖。

## 与 App 模块的关系

```
:agent-core (Agent Runtime 核心)
    ↑ 被依赖
:app (业务实现)
    - AgentCommand 密封类（含 BeautySettings 等）
    - Capability 接口（特化为 AgentCommand/AgentContext/PageContext/AgentAction）
    - CameraCapability / GalleryCapability / SettingsCapability
```

## 文件清单

### 根包 (`agent.core`)
- `AgentOrchestrator.kt` — 编排器（应用级单例）
- `AgentCommandParser.kt` — 命令解析器
- `AgentConfigurator.kt` — 配置管理
- `Capability.kt` — 泛型 Capability 接口
- `CapabilityHost.kt` — Capability 宿主
- `CapabilityRegistry.kt` — 注册表（应用级单例）
- `CommandExecutor.kt` — 命令执行器
- `CrossPageCommandQueue.kt` — 跨页面队列
- `ExecutionEngine.kt` — 执行引擎
- `ExecutionReporter.kt` — 执行报告器
- `FaceDetectionProvider.kt` — 人脸检测提供
- `InferenceRouter.kt` — 推理路由器
- `LocalLlmEngine.kt` — 本地 LLM 引擎
- `Logger.kt` — 日志接口
- `MemoryManager.kt` — 记忆管理
- `PrivacyGuard.kt` — 隐私守卫
- `PromptBuilder.kt` — Prompt 构建器
- `SceneManager.kt` — 场景管理器

### `langchain4j/`
- `ChatLanguageModel.kt` — 同步对话模型接口
- `StreamingChatLanguageModel.kt` — 流式对话模型接口
- `StreamingChatResponseHandler.kt` — 流式响应回调
- `ChatMessage.kt` — 消息密封接口（`SystemMessage` / `UserMessage` / `AiMessage` / `ToolExecutionResultMessage`）
- `ChatRequest.kt` — 对话请求（含 `toolSpecifications`）
- `ChatResponse.kt` — 对话响应
- `ChatResponseMetadata.kt` — 响应元数据（token、速度等）
- `ToolSpecification.kt` / `ToolParameters.kt` / `JsonSchemaProperty.kt` — 工具 Schema
- `ToolExecutionRequest.kt` / `ToolExecutionResultMessage.kt` — 工具执行消息
- `ToolExecutor.kt` / `ToolProvider.kt` — 工具执行与发现接口

- `ToolOrchestrator.kt` — tool-request → execute → result 循环
- `ToolCallingChatLanguageModel.kt` — 为模型注入工具提示并解析文本输出
- `ToolCallingOutputParser.kt` — 解析 OpenAI `tool_calls` / `<tool_call>` / ReAct Action 格式
- `ToolPromptBuilder.kt` — 工具说明渲染
- `ToolCallingMode.kt` / `ToolCallingConfig.kt` — OPENAI_TOOLS / REACT 模式配置

### `llm/`
- `MnnLlmClient.kt` — MNN LLM 客户端
- `LlmModelManager.kt` — 模型管理器
- `LocalLlmEngine.kt` — 本地 LLM 推理引擎

### `mnn/`
- `MnnResourceManager.kt` — MNN 资源管理

### `model/`
- `AgentCommands.kt` — 命令定义
- `AgentModels.kt` — Agent 模型
- `AiAgentConfig.kt` — 配置
- `ExecutionState.kt` — 执行状态
- `InferenceResult.kt` — 推理结果
- `MediaAsset.kt` — 媒体资产
- `PageContext.kt` — 页面上下文
- `SceneContext.kt` — 场景上下文
- `RemoteModelConfig.kt` — 远程模型配置

### `voice/`
- `AsrEngine.kt` — ASR 引擎接口
- `VadDetector.kt` — VAD 检测器
- `MnnAsrClient.kt` — MNN ASR 客户端
- `AudioRecorder.kt` — 音频录制器
- `SherpaMnnAsrEngine.kt` — Sherpa MNN ASR 引擎

### `remote/`
- `RemoteOrchestrator.kt` — 远程编排器
- `UnifiedRemoteClient.kt` — 统一远程客户端
- `AdaptiveStrategySelector.kt` — 自适应策略选择器
- `IntentCache.kt` — 意图缓存
- `ExecutionPlan.kt` — 执行计划
- `kimi/KimiCodingModels.kt` — Kimi 模型定义
- `kimi/KimiCodingApiClient.kt` — Kimi API 客户端
- `kimi/KimiCodingApiService.kt` — Kimi API 服务
- `openai/OpenAiModels.kt` — OpenAI 模型定义
- `openai/OpenAiApiClient.kt` — OpenAI API 客户端
- `openai/OpenAiApiService.kt` — OpenAI API 服务

## 编译验证

```bash
./gradlew :agent-core:compileKotlin  # ✅ BUILD SUCCESSFUL
```