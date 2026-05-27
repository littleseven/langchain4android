# PicMe AI Agent 团队执行手册

> **定位**：本文件为 Agent 协作开发会话内的**快速执行参考**。  
> **核心理念**：Agent First —— 基础设施原子化为 Tools 层，Agent 通过编排 Tools 完成开发任务。  
> **完整治理规则**：`../AGENTS.md`

---

## 1. 实验目标

PicMe 的 Agent 协作体系是一个**元实验**：验证 Agent First 范式——基础设施原子化为 Tools 层，Agent 通过编排 Tools 完成需求分析、架构设计、代码实现、质量验收。

**关键问题**：
- Agent 能否通过结构化文档理解复杂需求？
- Agent 能否通过编排 Tools 完成开发闭环？
- Tools 层如何设计才能被 Agent 高效调用？
- 人机协作的最佳边界在哪里？

---

## 2. 角色速查

| 指令 | 角色 | 核心能力 | 参考文档 |
| :--- | :--- | :--- | :--- |
| `[CO]` | 协调者 | 任务分级、状态板、Tools 编排 | `co_agent.md` |
| `[PM]` | 产品经理 | 需求澄清、PRD 维护、验收标准 | `pm_agent.md` |
| `[RD]` | 全栈工程师 | 端到端实现、Tools 编排、Self-Heal | `rd_agent.md` |
| `[CR]` | 规范审计 | 架构合规、DocSyncTool 调用 | `review_agent.md` |
| `[QA]` | 质量专家 | 边界测试、Tools 化验收 | `qa_agent.md` |

**Agent First 的角色设计**：
- 每个角色通过**编排 Tools** 完成任务
- 每个 Tool 有**明确的输入输出契约**
- 角色间通过**结构化文档**传递信息，非口头沟通

---

## 3. Tools 化执行流程

```
用户请求
    ↓
[CO] 分析 → 任务分级（L1/L2/L3）→ 启动状态板
    ↓
[PM] 需求对齐 → 更新 PRODUCT.md / FEATURES.md
    ↓
[RD] 实现 → 代码 + 文档 + Tools 编排
    ↓ 编排 Tools 完成验证
    ├─ CompileTool → 编译检查
    ├─ InstallTool → 安装到设备
    ├─ ScreenshotTool → 截屏对比
    └─ LogAnalysisTool → 日志分析
    ↓
[CR] 审查 → DocSyncTool 验证文档一致性
    ↓
[QA] 验收 → ScreenshotDiffTool + PerfBaselineTool
    ↓
[CO] 汇总 → ChangeReportTool 生成报告
```

**Agent First 收益**：
- 基础设施原子化为 Tools，Agent 可编排组合
- 每个 Tool 有明确输入输出，可验证
- 失败时明确回流路径，可自动重试

---

## 4. 触发口令

| 口令 | 模式 | AI 行为 |
|------|------|---------|
| `自动执行` | 全自动 | AI 角色自主流转，RD 自愈最多 2 次 |
| `保守执行` | 半自动 | 关键节点暂停等待人工确认 |
| `仅分析` | 诊断模式 | CO 仅输出分析，不启动执行 |

---

## 5. Agent First 的协作原则

### 5.1 显式上下文（Explicit Context）

```
❌ 不推荐：隐式假设
"按照之前的做法"

✅ 推荐：显式引用
"按照 docs/FEATURES.md Section 1.3 的交互规范"
```

### 5.2 结构化输出（Structured Output）

```
❌ 不推荐：纯文本描述
"美颜功能已经完成了"

✅ 推荐：结构化报告（Tool 输出格式）
- [x] AdjustBeautyCapability 实现
- [x] CapabilityRegistry 注册
- [x] CompileTool 通过
- [x] ScreenshotTool 验证通过
- [x] FEATURES.md 更新
```

### 5.3 可验证声明（Verifiable Claims）

```
❌ 不推荐：主观断言
"性能应该没问题"

✅ 推荐：Tool 输出支撑
"预览帧率 58fps（目标 ≥ 55fps），PerfBaselineTool 报告详见 report.md"
```

---

## 6. Self-Heal 工作流（RD 通过 Tools 编排）

```kotlin
// RD Agent 通过编排 Tools 完成开发闭环
fun implementTask(task: Task) {
    // 1. 理解需求
    val requirement = parseProductMd(task.productRef)
    val spec = parseFeaturesMd(task.featureRef)
    
    // 2. 实现代码
    writeCode(requirement, spec)
    
    // 3. Tools 编排验证
    val tools = listOf(
        CompileTool(),       // 编译检查
        InstallTool(),       // 安装到设备
        ScreenshotTool(),    // 截屏验证
        LogAnalysisTool()    // 日志分析
    )
    
    var attempts = 0
    while (attempts < MAX_RETRY) {
        val results = tools.map { it.execute() }
        val allSuccess = results.all { it.success }
        
        when {
            allSuccess -> submitPR()
            results.any { it.recoverable } -> {
                results.filter { !it.success }.forEach { fix(it.errors) }
                attempts++
            }
            else -> escalateToHuman()
        }
    }
}
```

**关键 Tools**：
- `CompileTool`：代码编译检查
- `InstallTool`：安装到设备
- `ScreenshotTool`：自动截屏验证
- `LogAnalysisTool`：结构化日志分析
- `DocSyncTool`：文档同步检查
- `ImpactAnalyzerTool`：变更影响分析

---

## 7. 严禁事项（红线）

| 红线 | 说明 | 后果 |
|------|------|------|
| **严禁隐式依赖** | 必须使用显式依赖注入 | CR 拒绝合并 |
| **严禁无文档变更** | 代码变更必须同步 AGENTS.md | CR 拒绝合并 |
| **严禁硬编码文案** | 必须同步 EN/CN/TW | CR 拒绝合并 |
| **严禁通配符导入** | 如 `import android.content.*` | CR 拒绝合并 |
| **严禁询问修复方式** | RD 必须自主分析日志修复 | 能力退化 |

---

## 8. 工具调用速查

| 场景 | 工具 | 示例 |
|------|------|------|
| 代码修改 | `replace_in_file` | 原子化修改 |
| 批量读取 | `read_file` | 多文件并行分析 |
| 编译验证 | `execute_command` | `./gradlew assembleDebug` |
| 设备操作 | `execute_command` | `adb install/logcat` |
| 任务追踪 | `todo_write` | 状态板维护 |
| 知识存储 | `update_memory` | 关键决策记录 |

---

## 9. 快速参考

### 文档体系
```
PRODUCT.md (What)
    ↓
FEATURES.md (How)
    ↓
模块 AGENTS.md (Implementation + Tools 规范)
    ↓
代码
```

### 角色流转
```
CO → PM → RD → CR → QA → CO
```

### 关键指标
- RD Tools 编排成功率：目标 > 70%
- 文档同步率：目标 > 95%
- Tool 原子化覆盖率：目标 > 80%
- 人工介入率：目标 < 20%

---

*角色详细规范见同目录各角色文件*  
*完整治理规则见 `../AGENTS.md`*
