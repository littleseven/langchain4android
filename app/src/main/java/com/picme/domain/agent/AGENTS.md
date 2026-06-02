# Agent Runtime 模块技术实现规范 (Agent Runtime Technical Implementation)

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `domain/agent/` 模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：PicMe 的 Agent 运行时核心，负责自然语言理解、意图解析、能力路由和命令执行。是连接用户输入与业务能力的"中枢神经系统"。

**主要维护者**：[RD] 全栈工程师

**阅读对象**：CO、PM、RD、CR、QA、AI Agent

---

## 1. 核心产品逻辑 (Core Product Logic)

- **[PRIVACY] 隐私分级路由**：`PrivacyGuard` 将用户输入分为 `SAFE`/`NORMAL`/`RESTRICTED` 三级，`RESTRICTED` 强制本地推理
- **[PERF] 推理延迟 < 800ms**：本地 Qwen3-1.7B 首 token 延迟目标 < 600ms（骁龙 8 Gen2 基准）
- **[PERF] 端到端命令执行 < 1.5s**：意图解析 → 命令生成 → Capability 执行全流程
- **[HYBRID] 本地+远程混合编排**：`InferenceRouter` 根据策略自动选择本地 LLM 或远程 Kimi API
- **[EXTENSIBLE] 能力热插拔**：新增 Capability 只需实现接口 + 注册到 `CapabilityRegistry`，无需修改 Agent 核心
- **[MEMORY] 多轮对话记忆**：`MemoryManager` 维护最近 N 轮对话上下文，支持隐式引用（"再亮一点"）
- **[VOICE] 语音交互支持**：`VoiceCommandCoordinator` 统一管理 Push-to-Talk 和 WakeWord 两种语音模式

---

## 2. 架构设计 (Architecture)

### 2.1 整体架构

```
User Input (Text / Voice)
    ↓
AgentOrchestrator (应用级单例)
    ├─ PrivacyGuard ──→ RESTRICTED? → 强制本地
    ├─ InferenceRouter
    │   ├─ L1_Cached → 本地缓存命中
    │   ├─ L2_BatchFC → RemoteOrchestrator.processBatch()
    │   ├─ L3_PlanExecute → RemoteOrchestrator.processPlan()
    │   └─ L4_ReAct → RemoteOrchestrator.processChat()
    ├─ LocalLlmEngine (Qwen3-1.7B-MNN)
    ├─ MemoryManager (对话上下文)
    ├─ PromptBuilder (system prompt 生成)
    └─ CapabilityRegistry (能力路由)
        ↓
    CameraCapability (features/camera/)
    GalleryCapability (features/gallery/)
    SettingsCapability (features/settings/)
    NavigationCapability (跨页面导航)
        ↓
    UI Feedback (Compose Chat UI)
```

### 2.2 核心组件职责

| 组件 | 职责 | 线程模型 |
|------|------|----------|
| `AgentOrchestrator` | 统一入口，管理本地模型生命周期，协调各组件 | 主线程协程 |
| `CapabilityRegistry` | 应用级单例，Capability 注册/查询/命令分发，支持跨页面命令队列 | Default 协程 |
| `InferenceRouter` | 根据隐私级别和策略选择器结果路由到本地或远程引擎 | Default 协程 |
| `LocalLlmEngine` | 本地 Qwen3-1.7B MNN-LLM 推理封装 | 独立推理线程 |
| `RemoteOrchestrator` | Kimi Coding API 远程推理（L2/L3/L4） | IO 协程 |
| `ExecutionEngine` | 顺序执行 ExecutionPlan，支持条件/延迟/暂停/取消 | Default 协程 |
| `ExecutionReporter` | 执行过程报告，生成结构化日志 | 主线程回调 |
| `MemoryManager` | 对话历史管理，支持上下文窗口裁剪 | 主线程 |
| `PrivacyGuard` | 输入内容隐私分级 | 同步（轻量） |
| `PromptBuilder` | 根据场景和 Capability 动态生成 system prompt | 同步 |
| `SceneManager` | 页面场景状态管理（Camera/Gallery/Settings/Editor） | 主线程 |

### 2.3 推理策略层级

| 层级 | 策略 | 适用场景 | 执行方式 |
|------|------|----------|----------|
| L1 | `Cached` | 高频单命令（"拍照""翻转镜头"） | 本地缓存直接返回 |
| L2 | `BatchFC` | 多参数同时调节（"瘦脸30，美白50"） | 远程批量解析 |
| L3 | `PlanExecute` | 复杂多步骤任务（"先拍照再进相册编辑"） | 远程生成计划 + 本地执行 |
| L4 | `ReAct` | 开放式对话/探索性交互 | 远程聊天模式 |

---

## 3. 技术实现规范 (Technical Implementation)

### 3.1 Capability 接口规范

**设计原则**：
- Capability 是**应用级单例**，在 `Application.onCreate()` 中注册一次，永不注销
- 页面通过**绑定/解绑 delegate** 来激活/停用 Capability
- 跨页面指令可以**排队执行**，当目标页面激活时自动处理

```kotlin
interface Capability {
    val name: String
    val description: String
    fun activeScenes(): List<SceneManager.Scene>
    fun supportedCommands(): List<String>
    fun getCommandDescription(command: String): String
    fun isAvailable(): Boolean
    suspend fun execute(command: AgentCommand, context: AgentContext, pageContext: PageContext? = null): Result<AgentAction>
}
```

**已实现的 Capability**：

| Capability | 场景 | 命令示例 |
|-----------|------|----------|
| `CameraCapability` | CAMERA | `take_photo`, `switch_camera`, `set_beauty_param`, `set_filter` |
| `GalleryCapability` | GALLERY | `navigate_to`, `delete_photo`, `share_photo`, `start_edit` |
| `SettingsCapability` | SETTINGS | `navigate_to`, `toggle_setting`, `set_model` |
| `NavigationCapability` | ALL | `navigate_to_page` |

### 3.2 AgentOrchestrator（应用级单例）

```kotlin
class AgentOrchestrator private constructor(context: Context) {
    companion object {
        fun getInstance(context: Context): AgentOrchestrator // 双重检查锁单例
    }
}
```

**关键约束**：
- 使用 `applicationContext` 避免内存泄漏
- `InferenceRouter` 懒加载，避免不需要时初始化远程组件
- 支持运行时切换 `agentMode`（LOCAL / REMOTE / HYBRID）

### 3.3 CapabilityRegistry（应用级单例）

**跨页面命令队列**：
```kotlin
data class QueuedCommand(
    val command: AgentCommand,
    val context: AgentContext,
    val pageContext: PageContext?,
    val targetScene: SceneManager.Scene,
    val retryCount: Int = 0
)
```

**行为**：
- 命令目标场景与当前场景不匹配时，自动入队
- 页面切换时检查队列，自动执行目标场景匹配的命令
- 最大重试次数 3 次，超限丢弃并记录错误

### 3.4 InferenceRouter 路由逻辑

```
1. PrivacyGuard.classify(input) → RESTRICTED?
   ├─ 是 → routeToLocal()
   └─ 否 → 2
2. AdaptiveStrategySelector.selectStrategy() → 策略
   ├─ L1_Cached → 返回缓存命令
   ├─ L2_BatchFC → RemoteOrchestrator.processBatch()
   ├─ L3_PlanExecute → RemoteOrchestrator.processPlan() → ExecutionEngine.execute()
   └─ L4_ReAct → RemoteOrchestrator.processChat()
```

### 3.5 ExecutionEngine 执行引擎

**支持特性**：
- **条件评估**：`condition` 字段支持表达式，不满足时跳过步骤
- **步骤间延迟**：`delayMs` 支持步骤间等待
- **暂停/恢复/取消**：通过 `MutableStateFlow<ExecutionState>` 暴露状态
- **失败回退**：`fallbackAction` 在步骤失败时执行

**状态机**：
```kotlin
sealed class ExecutionState {
    data object Idle : ExecutionState()
    data class Running(val currentStep: Int, val totalSteps: Int) : ExecutionState()
    data class Paused(val atStep: Int) : ExecutionState()
    data class Completed(val results: List<StepResult>) : ExecutionState()
    data class Failed(val step: Int, val error: String, val fallbackResult: StepResult?) : ExecutionState()
}
```

### 3.6 语音交互集成

**VoiceCommandCoordinator** 统一管理两种语音模式：

| 模式 | 触发方式 | 适用场景 |
|------|----------|----------|
| `PUSH_TO_TALK` | 按住说话按钮 | 精确控制、嘈杂环境 |
| `WAKE_WORD` | 自动检测语音活动 | 免手操作、快速指令 |

**数据流**：
```
AudioRecorder → VADDetector → ASREngine → AiAgentUseCase → AgentOrchestrator
```

**关键参数**：
- VAD 阈值：30dB（平衡灵敏度与误触发）
- 最小语音时长：100ms
- 最大片段时长：4000ms
- 静音超时：800ms

---

## 4. Agent 执行规约 (Execution Rules)

### 4.1 编码规范

- **单例模式**：`AgentOrchestrator`、`CapabilityRegistry`、`SceneManager` 均为应用级单例，使用双重检查锁
- **懒加载**：远程组件（`InferenceRouter`、`RemoteOrchestrator`）必须懒加载
- **协程上下文**：IO 操作使用 `Dispatchers.IO`，计算使用 `Dispatchers.Default`，UI 回调切回主线程
- **日志标签**：统一使用 `PicMe:AgentRuntime`
- **异常处理**：Capability.execute() 返回 `Result<AgentAction>`，禁止抛出未捕获异常

### 4.2 Capability 注册约束

- 必须在 `Application.onCreate()` 中完成注册
- 注册顺序：NavigationCapability → CameraCapability → GalleryCapability → SettingsCapability
- 禁止在 Activity/Fragment 中动态注册/注销

### 4.3 隐私分级规则

| 级别 | 判定条件 | 处理方式 |
|------|----------|----------|
| `SAFE` | 纯设备操作命令 | 可路由到远程 |
| `NORMAL` | 一般对话，无敏感信息 | 可路由到远程 |
| `RESTRICTED` | 包含人脸数据、位置、个人身份信息 | 强制本地推理 |

### 4.4 测试要求

- 每个 Capability 必须有单元测试（最小覆盖率 60%）
- `AgentOrchestrator` 解析逻辑必须有端到端测试
- `ExecutionEngine` 状态机必须覆盖所有状态转换
- 使用 Fake 替代真实 LLM 引擎进行测试

---

## 5. 常见陷阱检查清单 (Checklist)

### 5.1 架构与接口
- [ ] `AgentOrchestrator` 是否使用了 `applicationContext`？（避免内存泄漏）
- [ ] `CapabilityRegistry` 是否在 `Application.onCreate()` 中注册？（禁止页面级注册）
- [ ] 跨页面命令队列是否有最大长度限制？（防止无限堆积）
- [ ] Capability 是否返回 `Result` 而非抛出异常？

### 5.2 推理路由
- [ ] `RESTRICTED` 内容是否强制走本地？（隐私红线）
- [ ] `InferenceRouter` 懒加载是否生效？（冷启动不初始化远程组件）
- [ ] 远程 API 失败时是否有降级到本地的策略？
- [ ] 缓存命令是否有过期机制？（避免陈旧命令）

### 5.3 执行引擎
- [ ] `ExecutionPlan` 步骤是否支持条件跳过？
- [ ] 步骤间延迟是否正确应用？
- [ ] 取消操作是否能正确中断执行？
- [ ] 失败回退是否在所有步骤定义？

### 5.4 语音交互
- [ ] `WakeWordEngine` 是否在页面退出时停止？（避免后台耗电）
- [ ] AudioRecorder 资源是否正确释放？
- [ ] VAD 阈值是否适配不同环境噪音？
- [ ] 语音命令是否支持打断和重新识别？

### 5.5 性能与稳定性
- [ ] 本地 LLM 首 token 延迟是否 < 800ms？（Qwen3-1.7B）
- [ ] 端到端命令执行是否 < 1.5s？
- [ ] 内存管理：对话历史是否有上限？（防止 OOM，1.7B 模型约占用 1.5GB RAM）
- [ ] 协程作用域是否正确管理？（避免泄漏）

---

## 6. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ Agent 响应 < 800ms → `LocalLlmEngine` 本地推理 + `InferenceRouter` 缓存策略（Qwen3-1.7B）
- ✅ 意图识别准确率 > 92% → `AgentCommandParser` 多模式解析 + 远程增强（相比 0.6B 提升 2%）
- ✅ 对话式错误恢复 > 70% → `MemoryManager` 上下文 + 澄清提示
- ✅ 端到端命令执行 < 1.5s → 本地缓存 + 轻量路由
- ✅ 隐私绝对 → `PrivacyGuard` 分级 + `RESTRICTED` 强制本地
- ✅ 能力热插拔 → `Capability` 接口 + `CapabilityRegistry` 动态发现

**技术决策记录**：
- 选择应用级单例而非 DI：简化 Agent 架构，避免 Hilt 循环依赖
- 本地 + 远程混合编排：本地处理简单命令保证延迟，远程处理复杂意图保证准确率
- L1~L4 策略分层：高频命令缓存、多参数批量解析、复杂任务计划执行、开放式对话
- Capability 页面 delegate 模式：解耦 Agent 核心与页面生命周期
- 跨页面命令队列：支持"先拍照再编辑"等跨场景指令
- **Qwen3-1.7B 升级**：相比 0.6B 提供更强的推理能力，意图识别准确率提升约 2%，延迟增加约 300ms（可接受范围内）

---

## 7. 相关文档与实现入口

- `PRODUCT.md` - 产品需求规格说明书（Agent 体验目标）
- `docs/01-PRODUCT/FEATURES.md` - 功能交互规范（Agent 交互模式）
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` - Agent 架构详细设计
- `docs/03-TECHNICAL-SPECS/REMOTE_INFERENCE_ARCHITECTURE.md` - 远程推理架构
- `app/src/main/java/com/picme/features/common/chat/AGENTS.md` - Chat UI 组件规范
- `app/src/main/java/com/picme/features/camera/agent/` - Camera Agent 集成
- `app/src/main/java/com/picme/features/gallery/agent/` - Gallery Agent 集成
- `app/src/main/java/com/picme/features/settings/agent/` - Settings Agent 集成
