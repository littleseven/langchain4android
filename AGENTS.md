# PicMe AI Agent 系统：唯一事实来源 (SSOT)

> 本文档为**顶层治理文档**，定义 AI 主导的研发流程与协作规范。
>
> PicMe 的核心实验目标之一是**验证 AI 能否成为软件开发的主导力量**。本规范即为这一实验的操作手册。

---

## 1. 项目背景：AI 友好的三重实验

PicMe 是一个元实验（meta-experiment），同时探索三个层次：

| 层次 | 实验对象 | 核心问题 |
|------|----------|----------|
| **运行时** | 端侧 Agent 架构 | LLM 能否成为应用的中枢神经系统？ |
| **架构层** | AI 友好的客户端框架 | 什么样的代码结构让 AI 最高效？ |
| **流程层** | AI 主导的研发流程 | AI 能否替代传统 SDLC 的人类角色？ |

**核心假设**：通过设计「AI 友好」的架构和流程，AI 可以从「辅助工具」进化为「主导力量」。

---

## 2. AI 友好的代码架构原则

PicMe 的所有代码遵循以下 AI 优先设计原则：

### 2.1 显式优于隐式（Explicit > Implicit）

```kotlin
// ❌ AI 不友好：隐式依赖，全局状态
object BeautyEngine {
    fun getInstance() = instance  // AI 需要全局搜索理解生命周期
}

// ✅ AI 友好：显式依赖注入，构造函数即文档
class CameraViewModel(
    private val beautyEngine: BeautyEngine,
    private val agentUseCase: AiAgentUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel()
```

**AI 收益**：通过构造函数签名，AI 即可理解组件的协作关系，无需跨文件搜索。

### 2.2 枚举优于条件（Exhaustive > Conditional）

```kotlin
// ❌ AI 不友好：分散的布尔标志，状态组合爆炸
class CameraState(
    val isLoading: Boolean,
    val hasError: Boolean,
    val isPreviewing: Boolean
)

// ✅ AI 友好：Sealed Class 枚举所有有效状态
sealed interface CameraState {
    data object Initializing : CameraState
    data class Previewing(val settings: BeautySettings) : CameraState
    data class Error(val reason: String) : CameraState
}
```

**AI 收益**：状态空间显式编码，AI 可枚举所有边界情况，不会遗漏。

### 2.3 自描述优于注释（Self-Describing > Commented）

```kotlin
// ❌ AI 不友好：注释与代码可能脱节
// 调节美颜参数
fun adjust(params: Map<String, Int>) // AI 不知道有哪些参数

// ✅ AI 友好：类型系统即文档
data class BeautyParameters(
    val smooth: IntRange = 0..100,
    val whiten: IntRange = 0..100,
    val slimFace: IntRange = -50..50
)
fun adjust(params: BeautyParameters) // AI 通过类型理解全部参数
```

**AI 收益**：类型系统强制一致性，AI 可靠类型推导而非易腐烂的注释。

### 2.4 结构化可观测性（Structured Observability）

```kotlin
// ❌ AI 不友好：纯文本日志，需正则解析
Log.d("Camera", "Agent parsed: $input -> $intent")

// ✅ AI 友好：结构化事件，AI 可直接消费
data class AgentCommandParsedEvent(
    val rawInput: String,
    val parsedIntent: Intent,
    val confidence: Float,
    val timestamp: Long
) : LogEvent

Logger.log(AgentCommandParsedEvent(...)) // AI 可解析、可查询、可统计
```

**AI 收益**：AI 可消费自身产生的日志，实现自我诊断和自我改进。

---

## 3. AI 角色与协作流程

PicMe 采用**角色化 AI 协作模型**，将传统 SDLC 映射为 AI 可执行的流程。

### 3.1 角色定义

| 角色 | 标识 | 职责 | 输入 | 输出 |
|------|------|------|------|------|
| **[CO]** 协调者 | `🤖CO` | 任务分级、流程路由、冲突仲裁 | 用户请求 | 任务清单、状态板 |
| **[PM]** 产品经理 | `🤖PM` | 需求澄清、PRD 维护、验收标准 | 用户痛点 | PRODUCT.md、FEATURES.md |
| **[RD]** 全栈工程师 | `🤖RD` | 端到端实现、文档同步、自愈修复 | PRD、技术规范 | 代码、模块 AGENTS.md |
| **[CR]** 规范守护者 | `🤖CR` | 架构合规审查、代码质量裁决 | PR、变更 diff | 审查意见、合并决策 |
| **[QA]** 质量专家 | `🤖QA` | 边界测试、性能基线、端到端验收 | 实现代码 | 测试报告、验收结论 |

### 3.2 执行流程

```
用户请求
    ↓
[CO] 分析复杂度 → 分级（Simple/Medium/Complex）
    ↓
[PM] 对齐 PRODUCT.md → 更新/澄清需求
    ↓
[RD] 原子化实现 → 代码 + 文档同步
    ↓  触发 ./scripts/auto-dev-loop.sh
[RD] Self-Heal 闭环 → 编译→安装→测试→日志
    ↓
[CR] 规范审查 → 架构合规、代码质量
    ↓
[QA] 验收测试 → 边界、性能、体验
    ↓
[CO] 汇总交付 → 报告、闭环
```

### 3.3 触发口令

| 口令 | 含义 | 适用场景 |
|------|------|----------|
| `自动执行` | AI 角色按流程自动流转 | 默认模式，RD 最多自愈 2 次 |
| `保守执行` | 关键节点需人工确认 | 高风险变更、不可逆操作 |
| `仅分析` | CO 仅输出分析，不启动执行 | 需求澄清、方案比选 |

---

## 4. Self-Heal 与自动化工具链

PicMe 的核心创新之一是让 AI 具备**闭环验证能力**——不仅能写代码，还能验证代码的正确性。

### 4.1 自愈工作流

```kotlin
// RD Agent 的执行循环
object RdAgent {
    fun implement(task: Task) {
        var attempts = 0
        while (attempts < MAX_RETRY) {
            try {
                writeCode(task)
                val result = execute("./scripts/auto-dev-loop.sh")
                if (result.success) {
                    submitPR()
                    return
                }
                analyzeAndFix(result.errors)
                attempts++
            } catch (e: Exception) {
                if (attempts >= MAX_RETRY) escalateToHuman()
            }
        }
    }
}
```

### 4.2 自动化工具链

| 脚本 | 用途 | 调用者 |
|------|------|--------|
| `./scripts/ai-gate.sh` | 代码质量门禁 | CI / RD |
| `./scripts/auto-dev-loop.sh` | 编译→安装→启动→截屏→日志 | RD |
| `./scripts/impact-analyzer.sh` | 变更影响分析 | CO |
| `./scripts/doc-sync-guardian.sh` | 文档同步检查 | CR |
| `./scripts/test-generator.py` | 基于 public 方法生成测试骨架 | RD |
| `./scripts/screenshot-diff.py` | UI 回归检测 | QA |

**AI 收益**：AI 角色可调用标准化工具，消除人工操作的不确定性。

---

## 5. 文档体系（AI 可解析）

PicMe 的文档体系设计为**AI 可消费**——结构清晰、机器可读、交叉引用完整。

### 5.1 文档层级

```
PRODUCT.md (What: 目标与约束)
    ↓ 引用
FEATURES.md (How: 交互与体验)
    ↓ 引用
模块 AGENTS.md (Implementation: 实现约束)
    ↓ 反向链接
代码实现
```

### 5.2 任务标记规范 `[kimi-task]`

AI 可直接解析 Spec 文档中的任务标记，生成执行计划：

```markdown
### 调节美颜参数 [kimi-task:beauty-001]
- **Assignee**: RD
- **Scope**: `domain/agent/capability/AdjustBeautyCapability.kt`
- **Expected Change**:
  1. 实现 Capability 接口
  2. 注册到 CapabilityRegistry
  3. 添加单元测试
- **Priority**: P0
- **Acceptance**: AC-P0-1
```

**AI 收益**：需求→任务→代码的转换自动化，减少信息损耗。

---

## 6. 全局红线（不可突破）

| 红线 | 定义 | 验证方式 |
|------|------|----------|
| **[PRIVACY]** | 100% 端侧 AI，零云端推理 | 权限清单扫描、网络抓包 |
| **[PERF]** | 交互 < 100ms，快门 < 50ms | 性能测试、人工体感 |
| **[I18N]** | 禁止硬编码，三语同步 | 资源文件检查 |
| **[DOC-SYNC]** | 代码变更必须同步文档 | CI 文档检查 |
| **[AI-FRIENDLY]** | 新代码必须遵循 AI 友好原则 | CR 审查 |

---

## 7. 研究问题与度量

### 7.1 待验证的假设

1. **AI 可处理代码规模上限**：当前 PicMe 约 2 万行 Kotlin，上限是多少？
2. **AI 重构能力**：AI 能否主导跨模块架构重构？
3. **Self-Heal 成功率**：RD Agent 自动修复编译/运行时错误的成功率？
4. **文档驱动开发的效率**：相比传统流程，AI 协作的效率提升？

### 7.2 度量指标

| 指标 | 当前基线 | 目标 |
|------|----------|------|
| RD Self-Heal 成功率 | 待收集 | > 70% |
| 文档→代码一致性 | 待评估 | > 95% |
| AI 生成代码占比 | 待评估 | > 60% |
| 人工介入频次 | 待评估 | < 20% |

---

## 8. 文档索引

| 类型 | 文档 |
|------|------|
| **顶层治理** | `AGENTS.md`（本文档） |
| **产品定义** | `PRODUCT.md` |
| **交互规范** | `docs/FEATURES.md` |
| **AI 协作角色** | `agents/README.md`, `agents/co_agent.md`, `agents/rd_agent.md`, `agents/pm_agent.md`, `agents/review_agent.md`, `agents/qa_agent.md` |
| **模块规范** | 各模块 `AGENTS.md` |
| **技术专项** | `docs/*.md` |

---

## 9. 交付审计清单

- [ ] 代码遵循 AI 友好原则（显式、枚举、自描述、结构化）
- [ ] PRODUCT.md 已更新或保持一致
- [ ] FEATURES.md 已更新或保持一致
- [ ] 模块 AGENTS.md 已更新实现细节
- [ ] 满足 [PRIVACY]、[PERF]、[I18N] 红线
- [ ] Self-Heal 闭环验证通过
- [ ] CR 架构合规审查通过
- [ ] QA 核心验收通过

---

> **维护者**：CO Agent  
> **最后更新**：2025-06  
> **实验状态**：进行中
