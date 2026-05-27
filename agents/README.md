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

| 指令 | 角色 | 核心能力 | 参考文档 |
| :--- | :--- | :--- | :--- |
| `[CO]` | 协调者 | 任务分级、状态板维护 | `co_agent.md` |
| `[PM]` | 产品经理 | 需求澄清、PRD 维护、验收标准 | `pm_agent.md` |
| `[RD]` | 全栈工程师 | 端到端实现、Self-Heal、Tools 编排 | `rd_agent.md` |
| `[CR]` | 规范审计 | 架构合规、代码质量、红线守护 | `review_agent.md` |
| `[QA]` | 质量专家 | 边界测试、性能基线、回归验收 | `qa_agent.md` |

**设计原则**：
- 每个角色有**明确的输入输出契约**
- 每个角色有**可验证的交付标准**
- 角色间通过**结构化文档**传递信息，非口头沟通

---

## 3. 协作流程

```
用户请求
    ↓
[CO] 分析 → 任务分级（L1/L2/L3）→ 启动状态板
    ↓
[PM] 需求对齐 → 更新 PRODUCT.md / FEATURES.md
    ↓
[RD] 实现 → 代码 + 文档 + Tools 编排验证
    ↓ 调用 Tools 完成验证
    ├─ CompileTool → 编译检查
    ├─ InstallTool → 安装到设备
    ├─ ScreenshotTool → 截屏验证
    └─ LogAnalysisTool → 日志分析
    ↓
[CR] 审查 → 架构合规、代码质量
    ↓
[QA] 验收 → 边界测试、回归检测
    ↓
[CO] 汇总 → 交付报告、状态板归档
```

**收益**：
- 流程可预测，无隐性依赖
- 每个阶段产出可验证
- 失败时明确回流路径

---

## 4. 触发口令

| 口令 | 模式 | AI 行为 |
|------|------|---------|
| `自动执行` | 全自动 | AI 角色自主流转，RD 自愈最多 2 次 |
| `保守执行` | 半自动 | 关键节点暂停等待人工确认 |
| `仅分析` | 诊断模式 | CO 仅输出分析，不启动执行 |

---

## 5. 协作原则

### 5.1 显式上下文

```
❌ 不推荐：隐式假设
"按照之前的做法"

✅ 推荐：显式引用
"按照 docs/FEATURES.md Section 1.3 的交互规范"
```

### 5.2 结构化输出

```
❌ 不推荐：纯文本描述
"美颜功能已经完成了"

✅ 推荐：结构化报告
- [x] AdjustBeautyCapability 实现
- [x] CapabilityRegistry 注册
- [x] 编译检查通过
- [x] 截屏验证通过
- [x] FEATURES.md 更新
```

### 5.3 可验证声明

```
❌ 不推荐：主观断言
"性能应该没问题"

✅ 推荐：数据支撑
"预览帧率 58fps（目标 ≥ 55fps），详见报告 report.md"
```

---

## 6. Self-Heal 工作流（RD 核心能力）

```kotlin
// RD Agent 的标准执行循环
fun implementTask(task: Task) {
    // 1. 理解需求
    val requirement = parseProductMd(task.productRef)
    val spec = parseFeaturesMd(task.featureRef)
    
    // 2. 实现代码
    writeCode(requirement, spec)
    
    // 3. 闭环验证
    var attempts = 0
    while (attempts < MAX_RETRY) {
        val result = execute("./scripts/auto-dev-loop.sh")
        when {
            result.success -> submitPR()
            result.recoverable -> {
                analyzeAndFix(result.errors)
                attempts++
            }
            else -> escalateToHuman()
        }
    }
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

---

## 9. 工具调用速查

| 场景 | 工具 | 示例 |
|------|------|------|
| 代码修改 | `replace_in_file` | 原子化修改 |
| 批量读取 | `read_file` | 多文件并行分析 |
| 编译验证 | `execute_command` | `./gradlew assembleDebug` |
| 设备操作 | `execute_command` | `adb install/logcat` |
| 任务追踪 | `todo_write` | 状态板维护 |
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
CO → PM → RD → CR → QA → CO
```

### 关键指标
- RD Self-Heal 成功率：目标 > 70%
- 文档同步率：目标 > 95%
- 人工介入率：目标 < 20%

---

*角色详细规范见同目录各角色文件*  
*完整治理规则见 `../AGENTS.md`*
