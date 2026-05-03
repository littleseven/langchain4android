# PicMe AI Agent 团队执行手册

> **定位**：本文件为 OpenClaw / kimi-cli 会话内的**快速执行参考**，不重复根目录 `AGENTS.md` 的治理规范。  
> **完整治理规则**：`../AGENTS.md`（角色定义、全局红线、文档体系、审计清单）

## 1. 角色速查

| 指令 | 角色 | 参考文档 |
| :--- | :--- | :--- |
| `[CO]` | 协调者 | `co_agent.md` |
| `[PM]` | 产品经理 | `pm_agent.md` + `PRODUCT.md` + `docs/FEATURES.md` |
| `[RD]` | 全栈工程师 | `rd_agent.md` + 模块 `AGENTS.md` |
| `[CR]` | 规范审计 | `review_agent.md` + `../AGENTS.md` |
| `[QA]` | 质量专家 | `qa_agent.md` + `PRODUCT.md` |

## 2. 触发口令

| 口令 | 模式 | 说明 |
|------|------|------|
| `自动执行` | 全自动 | L1/L2 自动推进；L3 自动优先，红线场景暂停 |
| `保守执行` | 半自动 | 关键节点等待确认，适用于架构变更/重大重构 |
| `执行吧` | 闭环模式 | RD-Review 循环直至完成 |

## 3. 单实例多角色流程（精简）

```
用户输入
  ↓
[CO] 任务分级（L1/L2/L3）
  ↓
[PM] 需求确认 → [RD] 实现 + 自愈（≤2次）
  ↓
[CR] 规范审计 → [QA] 质量验收
  ↓
[CO] 汇总交付
```

**阶段流转规则**：
- RD 编译不通过 → 自愈（最多 2 次）→ 超限上报
- CR 不通过 → 回流 RD（1 次）
- QA 不通过 → 标记 Bug 回流 RD

## 4. OpenClaw 工具调用速查

| 场景 | 工具 |
|------|------|
| 原子化代码修改 | `replace_text` / `insert_text` |
| 读取并分析文件 | `analyze_current_file` |
| 执行 Gradle 构建 | `execute_command` (`./gradlew assembleDebug`) |
| 查看日志 | `execute_command` (`adb logcat`) |

> **kimi-cli 等效工具**：`StrReplaceFile` / `ReadFile` / `Shell`

## 5. 严禁事项（快速版）

- **严禁询问编译错误修复方式** — RD 必须自主阅读 Log 并修正
- **严禁遗漏多语言** — 任何 UI 变更必须覆盖 `EN/CN/TW`
- **严禁使用隐式 `it`** — Lambda 必须显式命名
- **严禁通配符导入** — 如 `import android.content.*`

---

*角色详细规范见同目录 `co_agent.md`、`rd_agent.md`、`pm_agent.md`、`review_agent.md`、`qa_agent.md`*  
*完整治理规则见 `../AGENTS.md`*
