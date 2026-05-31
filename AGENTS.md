# PicMe AI Agent 系统：唯一事实来源 (SSOT)

> 本文档为**顶层治理文档**，定义 Agent First 的研发流程与协作规范。
>
> PicMe 的核心实验目标之一是**验证 Agent First 的工程范式**：基础设施原子化为 Tools 层，Agent 通过编排 Tools 完成开发任务。

---

## 1. 项目背景：Agent First 三重实验

PicMe 是一个元实验（meta-experiment），同时探索三个层次：

| 层次 | 实验对象 | 核心问题 |
|------|----------|----------|
| **运行时** | 端侧 Agent 架构 | LLM 能否成为应用的中枢神经系统？ |
| **架构层** | Agent First 客户端框架 | 什么样的架构让 Agent 最高效？ |
| **流程层** | Agent First 研发流程 | Agent 如何通过编排 Tools 完成开发？ |

**核心假设**：当基础设施原子化为 Tools 层后，Agent 可以从「辅助工具」进化为「主导力量」。

---

## 2. Agent First 的代码架构原则

PicMe 的所有代码遵循以下原则，确保 Agent 能高效理解、修改、验证：

### 2.1 显式优于隐式（Explicit > Implicit）

```kotlin
// ❌ 隐式依赖：AI 需要全局搜索理解生命周期
object BeautyEngine {
    fun getInstance() = instance
}

// ✅ 显式注入：构造函数即文档
class CameraViewModel(
    private val beautyEngine: BeautyEngine,
    private val agentUseCase: AiAgentUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel()
```

**收益**：通过构造函数签名，AI 即可理解组件协作关系，无需跨文件搜索。

### 2.2 枚举优于条件（Exhaustive > Conditional）

```kotlin
// ❌ 布尔标志组合爆炸
class CameraState(
    val isLoading: Boolean,
    val hasError: Boolean,
    val isPreviewing: Boolean
)

// ✅ 枚举所有合法状态
sealed interface CameraState {
    data object Initializing : CameraState
    data class Previewing(val settings: BeautySettings) : CameraState
    data class Error(val reason: String) : CameraState
}
```

**收益**：状态空间显式编码，AI 可枚举所有边界情况，不会遗漏。

### 2.3 自描述优于注释（Self-Describing > Commented）

```kotlin
// ❌ 注释与代码可能脱节
// 调节美颜参数
fun adjust(params: Map<String, Int>) // AI 不知道有哪些参数

// ✅ 类型系统即文档
data class BeautyParameters(
    val smooth: IntRange = 0..100,
    val whiten: IntRange = 0..100,
    val slimFace: IntRange = -50..50
)
fun adjust(params: BeautyParameters) // 类型即契约
```

**收益**：类型系统强制一致性，AI 可靠类型推导而非易腐烂的注释。

### 2.4 结构化可观测性（Structured Observability）

```kotlin
// ❌ 纯文本日志，需正则解析
Log.d("Camera", "Agent parsed: $input -> $intent")

// ✅ 结构化事件，AI 可直接消费
data class AgentCommandParsedEvent(
    val rawInput: String,
    val parsedIntent: Intent,
    val confidence: Float,
    val timestamp: Long
) : LogEvent

Logger.log(AgentCommandParsedEvent(...))
```

**收益**：结构化日志可被 AI 消费，实现自我诊断和自我改进。

---

## 3. Agent 角色与协作流程

PicMe 采用**角色化协作模型**：每个 Agent 角色有明确的职责边界、输入输出契约。

### 3.1 角色定义

| 角色 | 标识 | 核心职责 | 关键能力 |
|------|------|----------|----------|
| **[CO]** 协调者 | `🤖CO` | 任务分级、流程路由、冲突仲裁 | 复杂度分析、状态板维护 |
| **[PM]** 产品经理 | `🤖PM` | 需求澄清、PRD 维护、验收标准 | 需求拆解、文档同步 |
| **[RD]** 全栈工程师 | `🤖RD` | 端到端实现、文档同步、Self-Heal | 代码生成、Tools 编排 |
| **[CR]** 规范守护者 | `🤖CR` | 架构合规审查、代码质量裁决 | 红线检查、影响分析 |
| **[QA]** 质量专家 | `🤖QA` | 边界测试、性能基线、端到端验收 | 场景设计、回归检测 |

### 3.2 协作流程（CO驱动）

**核心原则**：CO是所有用户请求的默认入口，负责分析、分级、路由和推进。

```
用户请求
    ↓
[CO] 分析任务类型 → 复杂度分级（L1/L2/L3）→ 创建状态板
    ↓
[PM] 需求对齐 → 输出可执行结论（AC）
    ↓
[RD] 原子化实现 → 代码 + 文档同步
    ↓  调用 Tools 完成验证
[RD] Self-Heal 闭环 → 编译 → 安装 → 测试 → 日志
    ↓  [CO检测到"编译通过"自动推进]
[CR] 规范审查 → 架构合规、代码质量
    ↓  [CO检测到"审计通过"自动推进]
[QA] 验收测试 → 边界、性能、体验
    ↓  [CO检测到"验收通过"自动推进]
[CO] 汇总交付 → 更新状态板 → 报告闭环
```

**CO推进规则**：
- RD报告编译通过 → CO**必须**立即启动CR审计
- CR报告无Critical → CO**必须**立即启动QA验收
- QA报告无P0缺陷 → CO**必须**立即生成最终交付报告
- **严禁**在L1/L2任务中间环节要求用户确认

### 3.3 Tools 层

基础设施原子化为 **Tools**，供 Agent 编排调用：

| Tool | 功能 | 调用者 |
|------|------|--------|
| `CompileTool` | 代码编译检查 | RD |
| `InstallTool` | 安装到设备 | RD |
| `ScreenshotTool` | 自动截屏 | RD/QA |
| `LogAnalysisTool` | 结构化日志分析 | RD |
| `DocSyncTool` | 文档同步检查 | CR |
| `ScreenshotDiffTool` | UI 回归检测 | QA |
| `PerfBaselineTool` | 性能基线对比 | QA |

**关键转变**：从「人类操作脚本」到「Agent 编排 Tools」。

### 3.4 触发口令与执行模式

| 口令 | 模式 | 自动化程度 | CO行为 | 适用场景 |
|------|------|-----------|--------|----------|
| （无口令） | **默认模式** | L1全自动 / L2半自动 | 自动分析分级并启动对应流程 | 日常开发任务 |
| `自动执行` | 全链路自动 | L1/L2全自动 | 强制启动完整CO→PM→RD→CR→QA流程 | 明确的全链路需求 |
| `保守执行` | 全链路可控 | 关键节点暂停 | 每阶段完成后暂停等待用户确认 | 高风险变更、不可逆操作 |
| `仅分析` | 诊断模式 | 不执行 | CO仅输出分析，不启动任何角色 | 需求澄清、方案比选 |

**默认模式分级行为**：
- **L1任务**（单文件修改、已知模式）：CO→RD→CR→QA，全自动推进，仅最终报告
- **L2任务**（跨多文件、新功能）：CO→PM→RD→CR→QA，半自动，关键节点简报
- **L3任务**（架构变更、无先例）：CO→PM→RD→CR→QA，手动，每阶段确认

---

## 4. Self-Heal 与自动化工具链

PicMe 的核心创新是赋予 RD **闭环验证能力**——不仅能写代码，还能通过 Tools 自动验证正确性。

### 4.1 自愈工作流

```kotlin
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

### 4.2 自动化脚本

| 脚本 | 用途 | 调用者 |
|------|------|--------|
| `./scripts/ai-gate.sh` | 代码质量门禁 | CI / RD |
| `./scripts/auto-dev-loop.sh` | 编译→安装→启动→截屏→日志 | RD |
| `./scripts/impact-analyzer.sh` | 变更影响分析 | CO |
| `./scripts/doc-sync-guardian.sh` | 文档同步检查 | CR |
| `./scripts/test-generator.py` | 基于 public 方法生成测试骨架 | RD |
| `./scripts/screenshot-diff.py` | UI 回归检测 | QA |

**收益**：标准化工具消除人工操作的不确定性，AI 可编排完成复杂验证。

---

## 5. 文档体系（AI 可解析）

PicMe 的文档设计为**机器可读、交叉引用完整**，AI 可直接解析为执行计划。

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

AI 可直接解析 Spec 中的任务标记，生成执行计划：

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

**收益**：需求→任务→代码的转换自动化，减少信息损耗。

---

## 6. 全局红线（不可突破）

| 红线 | 定义 | 验证方式 |
|------|------|----------|
| **[PRIVACY]** | 100% 端侧 AI，零云端推理 | 权限清单扫描、网络抓包 |
| **[PERF]** | 交互 < 100ms，快门 < 50ms | 性能测试、人工体感 |
| **[I18N]** | 禁止硬编码，三语同步 | 资源文件检查 |
| **[DOC-SYNC]** | 代码变更必须同步文档 | CI 文档检查 |
| **[AGENT-FIRST]** | 新代码必须遵循 Agent First 原则 | CR 审查 |

---

## 7. 研究问题与度量

### 7.1 待验证的假设

1. **AI 可处理代码规模上限**：当前 PicMe 约 3 万行 Kotlin（含 Agent Runtime、语音交互、远程编排），上限是多少？
2. **AI 重构能力**：AI 能否主导跨模块架构重构？
3. **Self-Heal 成功率**：RD Agent 自动修复编译/运行时错误的成功率？
4. **文档驱动开发的效率**：相比传统流程，AI 协作的效率提升？
5. **Tools 扩展性**：新 Tools 能否被 Agent 自动发现和集成？

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
| **交互规范** | `docs/01-PRODUCT/FEATURES.md` |
| **AI 协作角色** | `agents/README.md`, `agents/co_agent.md`, `agents/rd_agent.md`, `agents/pm_agent.md`, `agents/review_agent.md`, `agents/qa_agent.md` |
| **模块规范** | 各模块 `AGENTS.md` |
| **技术专项** | `docs/*.md` |

---

## 9. 交付审计清单

- [ ] 代码遵循 Agent First 原则（显式、枚举、自描述、结构化）
- [ ] PRODUCT.md 已更新或保持一致
- [ ] FEATURES.md 已更新或保持一致
- [ ] 模块 AGENTS.md 已更新实现细节
- [ ] 满足 [PRIVACY]、[PERF]、[I18N] 红线
- [ ] Self-Heal 闭环验证通过
- [ ] CR 架构合规审查通过
- [ ] QA 核心验收通过

---

> **维护者**：CO Agent
> **最后更新**：2026-06-01
> **实验状态**：进行中 · Phase 2 智能体验升级（Agent 混合编排 + 语音交互已落地）
