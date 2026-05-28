# PicMe AI Agent 团队执行手册

> **定位**：本文件为 AI 协作开发会话内的**快速执行参考**。  
> **核心理念**：Agent First —— Agent 作为第一公民，主导研发流程。  
> **完整治理规则**：`../AGENTS.md`

---

## 1. 实验目标

PicMe 的 Agent 协作体系是一个**元实验**：验证 Agent First 范式——Agent 主导从需求分析到质量验收的完整研发流程。

**核心问题**：
- Agent 能否通过结构化文档理解复杂需求？
- Agent 能否通过编排 Tools 完成验证闭环？
- Tools 层如何设计才能被 Agent 高效调用？
- 人机协作的最佳边界在哪里？

---

## 2. 角色速查

| 指令 | 角色 | 核心能力 | 参考文档 | 激活方式 |
| :--- | :--- | :--- | :--- | :--- |
| `[CO]` | 协调者 | 任务分级、状态板维护、流程推进 | `co_agent.md` | **所有请求默认激活** |
| `[PM]` | 产品经理 | 需求澄清、PRD 维护、验收标准 | `pm_agent.md` | 由CO在需求类任务中激活 |
| `[RD]` | 全栈工程师 | 端到端实现、Self-Heal、Tools 编排 | `rd_agent.md` | 由CO在实现类任务中激活 |
| `[CR]` | 规范审计 | 架构合规、代码质量、红线守护 | `review_agent.md` | 由CO在RD完成后激活 |
| `[QA]` | 质量专家 | 边界测试、性能基线、回归验收 | `qa_agent.md` | 由CO在CR通过后激活 |

**设计原则**：
- 每个角色有**明确的输入输出契约**
- 每个角色有**可验证的交付标准**
- 角色间通过**CO协调**传递信息，非直接沟通
- **CO是所有用户请求的唯一入口**

---

## 3. 协作流程（CO驱动）

```
用户请求
    ↓
[CO] 分析 → 任务分级（L1/L2/L3）→ 创建/更新状态板
    ↓
[PM] 需求对齐 → 输出AC（L1任务可跳过）
    ↓
[RD] 实现 → 代码 + 文档 + Tools 编排验证
    ↓  调用 Tools 完成验证
    ├─ CompileTool → 编译检查
    ├─ InstallTool → 安装到设备
    ├─ ScreenshotTool → 截屏验证
    └─ LogAnalysisTool → 日志分析
    ↓  [CO检测到"编译通过"自动推进]
[CR] 审查 → 架构合规、代码质量
    ↓  [CO检测到"审计通过"自动推进]
[QA] 验收 → 边界测试、回归检测
    ↓  [CO检测到"验收通过"自动推进]
[CO] 汇总交付 → 更新状态板 → 报告闭环
```

**CO推进守则**：
- RD报告"编译通过" → CO**必须**立即启动CR审计
- CR报告"无Critical" → CO**必须**立即启动QA验收
- QA报告"无P0缺陷" → CO**必须**立即生成最终交付报告
- **严禁**在L1/L2任务中间环节要求用户确认

**Token节省**：
- L1任务阶段间推进消息≤3行
- 状态板替代长篇进度汇报
- 各角色仅输出增量信息，不重复已知上下文

**回流机制**：
- CR不通过 → CO回流RD，不通过计数+1
- QA不通过 → CO回流RD，标记为Bug
- RD自愈2次仍失败 → CO上报用户，提供选项

---

## 4. 触发口令与执行模式

| 口令 | 模式 | 自动化程度 | 适用场景 |
|------|------|-----------|----------|
| （无口令） | **默认模式** | L1全自动 / L2半自动 | 日常开发任务 |
| `自动执行` | 全链路自动 | 强制完整流程 | 明确的全链路需求 |
| `保守执行` | 全链路可控 | 关键节点暂停确认 | 高风险变更、不可逆操作 |
| `仅分析` | 诊断模式 | 仅分析不执行 | 需求澄清、方案比选 |

**默认模式分级行为**：
- **L1 简单**（单文件修改、已知模式）：CO→RD→CR→QA，全自动，仅最终报告
- **L2 中等**（跨2-5文件、新功能）：CO→PM→RD→CR→QA，半自动，关键节点简报
- **L3 复杂**（架构变更、无先例）：CO→PM→RD→CR→QA，手动，每阶段确认

---

## 5. 状态板管理（强制）

CO必须使用 `todo_write` 工具维护任务状态板，确保跨消息持久化。

**状态板模板**：

```markdown
## 任务状态板：[任务简述]

| 阶段 | 负责 | 状态 | 输出物 |
|------|------|------|--------|
| 需求分析 | [PM] | ⏸️/🔄/✅/❌ | 需求确认 |
| 技术实现 | [RD] | ⏸️/🔄/✅/❌ | 代码 + 构建结果 |
| 规范审计 | [CR] | ⏸️/🔄/✅/❌ | 审计报告 |
| 质量验收 | [QA] | ⏸️/🔄/✅/❌ | 测试报告 |
| 最终交付 | [CO] | ⏸️/🔄/✅/❌ | 汇总报告 |

**当前阶段**：[角色]
**任务分级**：[L1/L2/L3]
**RD自愈次数**：[0/1/2]
**阻塞项**：[如有]
```

---

## 6. Self-Heal 工作流（RD 核心能力）

```kotlin
// RD Agent 的标准执行循环
fun implementTask(task: CoTask) {
    // 1. 理解需求（基于CO传递的PM结论）
    val requirement = parsePmConclusion(task.pmOutput)
    val spec = parseFeaturesMd(task.featureRef)
    
    // 2. 分析上下文
    analyzeCodebase(task.affectedModules)
    
    // 3. 编码实现
    writeCode(requirement, spec)
    
    // 4. 闭环验证
    var attempts = 0
    while (attempts < MAX_RETRY) {
        val result = execute("./scripts/auto-dev-loop.sh")
        when {
            result.success -> {
                reportToCo("✅ 编译通过，变更摘要：...")
                return
            }
            result.recoverable -> {
                analyzeAndFix(result.errors)
                attempts++
                reportToCo("🔄 第${attempts}次自愈...")
            }
            else -> {
                reportToCo("❌ 不可恢复错误：...")
                return
            }
        }
    }
    reportToCo("❌ 自愈${MAX_RETRY}次仍失败...")
}
```

**关键脚本**：
- `./scripts/auto-dev-loop.sh`：编译→安装→启动→截屏→日志→报告
- `./scripts/impact-analyzer.sh`：变更影响分析
- `./scripts/doc-sync-guardian.sh`：文档同步检查

---

## 7. Tools 层

基础设施原子化为 **Tools**，供 Agent 编排调用：

| Tool | 功能 | 输入 | 输出 | 调用者 |
|------|------|------|------|--------|
| `CompileTool` | 代码编译检查 | 源码变更 | 编译结果/错误日志 | RD |
| `InstallTool` | 安装到设备 | APK | 安装状态 | RD |
| `ScreenshotTool` | 自动截屏 | 设备连接 | 截图文件 | RD/QA |
| `LogAnalysisTool` | 日志分析 | Logcat | 结构化事件 | RD |
| `DocSyncTool` | 文档同步检查 | Git diff | 需更新文档列表 | CR |
| `ScreenshotDiffTool` | UI 回归检测 | 截图对比 | Diff 报告 | QA |
| `PerfBaselineTool` | 性能基线对比 | 性能指标 | 对比报告 | QA |

---

## 8. 严禁事项（红线）

| 红线 | 说明 | 后果 |
|------|------|------|
| **严禁隐式依赖** | 必须使用显式依赖注入 | CR 拒绝合并 |
| **严禁无文档变更** | 代码变更必须同步 AGENTS.md | CR 拒绝合并 |
| **严禁硬编码文案** | 必须同步 EN/CN/TW | CR 拒绝合并 |
| **严禁通配符导入** | 如 `import android.content.*` | CR 拒绝合并 |
| **严禁询问修复方式** | RD 必须自主分析日志修复 | 能力退化 |
| **严禁角色跳过CO** | 任何角色不得直接向用户交付 | 流程失效 |
| **严禁遗漏状态板** | CO必须每阶段更新状态板 | 进度丢失 |

---

## 9. 工具调用速查

| 场景 | 工具 | 示例 |
|------|------|------|
| 代码修改 | `replace_in_file` | 原子化修改 |
| 批量读取 | `read_file` | 多文件并行分析 |
| 编译验证 | `execute_command` | `./gradlew assembleDebug` |
| 设备操作 | `execute_command` | `adb install/logcat` |
| 任务追踪 | `todo_write` | **状态板维护（强制）** |
| 知识存储 | `update_memory` | 关键决策记录 |

---

## 10. 快速参考

### 文档体系
```
PRODUCT.md (What)
    ↓
FEATURES.md (How)
    ↓
模块 AGENTS.md (Implementation)
    ↓
代码
```

### 角色流转
```
用户 → CO → PM → RD → CR → QA → CO → 用户
         ↑              ↓______↓
         └────────────── 回流机制
```

### 关键指标
- RD Self-Heal 成功率：目标 > 70%
- 文档同步率：目标 > 95%
- 人工介入率：目标 < 20%
- 阶段遗漏率：目标 = 0%

---

*角色详细规范见同目录各角色文件*  
*完整治理规则见 `../AGENTS.md`*
