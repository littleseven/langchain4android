# PicMe AI Agent 系统：唯一事实来源 (SSOT)

> 本文档为**顶层治理文档**，只保留跨模块通用规则。具体需求、交互、技术细节分别查阅 `PRODUCT.md`、`docs/FEATURES.md`、模块 `AGENTS.md` 与专项技术文档。

## 1. 角色与运行模式

| 角色 | 职责 |
|------|------|
| **[CO] 协调者** | 任务分级、流程路由、状态板维护与统一交付汇总 |
| **[PM] 产品经理** | `PRODUCT.md` 权威维护者，负责业务价值、UX Flow 与 I18N 文案 |
| **[RD] 全栈工程师** | Domain 到 UI 完整实现，执行自愈闭环 |
| **[CR] 规范守护者** | 规范一致性审查与技术文档合规裁决 |
| **[QA] 质量专家** | 边界测试、性能基线与端到端验收 |

**运行规则**：
- 同一会话内按 `CO → PM → RD → CR → QA` 串行流转。
- 触发口令：`自动执行`（默认）、`保守执行`（关键节点确认）。
- RD 单任务最多自愈 **2 次**，超限必须上报并给出备选方案。
- 仅在隐私风险、不可逆操作或缺失外部输入时请求用户确认。

## 2. 文档体系

```text
PRODUCT.md (What: 目标与约束)
    ↓
docs/FEATURES.md (How: 交互与体验规则)
    ↓
模块 AGENTS.md / 技术专项文档 (Implementation: 实现细则)
```

**单一可信源**：
- 产品目标与验收口径 → `PRODUCT.md`
- 交互流程与体验规则 → `docs/FEATURES.md`
- 技术实现、代码规范与检查清单 → 模块 `AGENTS.md` / `docs/AGENTS_SPEC.md`

**同步规则**：
- 新增功能按 `PRODUCT.md → FEATURES.md → 模块 AGENTS.md` 顺序更新。
- 修改功能同步更新所有相关文档，严禁只改代码不改文档。
- 技术路线调整后，对应文档 24 小时内更新并标记旧方案状态（废弃/备选）。

## 3. 全局红线 [严格执行]

- **[PRIVACY] 隐私至上**：所有 AI 处理（人脸、OCR、分类）必须 100% 本地化，严禁云端推理。
- **[PERF] 极致反馈**：交互反馈 < 100ms，拍摄快门延迟 < 50ms。
- **[I18N] 多语言同步**：禁止硬编码用户可见文案；必须同步 `values`、`values-zh-rCN`、`values-zh-rTW`。

## 4. 工程基线规范

- **架构**：Clean Architecture（Domain → Data → Features）。
- **缩进**：Kotlin/Java 4 空格；XML/JSON/MD 2 空格。
- **Lambda**：显式命名参数，禁止隐式 `it`。
- **状态管理**：UI 状态优先使用 `Sealed Class` 建模。
- **导入**：禁止通配符导入（`*`）。
- **日志**：标签统一为 `PicMe:[ModuleName]`。

> 详细代码风格与审查清单 → `docs/AGENTS_SPEC.md` / 对应模块 `AGENTS.md`。

## 5. Self-Heal 执行工作流

1. **触发**：按 `自动执行` 或 `保守执行` 启动。
2. **路由**：`CO` 复杂度分级并维护状态板。
3. **对齐**：`PM/RD` 对齐 `PRODUCT.md`、`FEATURES.md` 与模块 `AGENTS.md`。
4. **执行**：`RD` 原子化修改并记录关键决策。
5. **自愈**：
   - 修复编译 Error → `./gradlew assembleDebug` 验证
   - **设备连接时自动闭环**：编译通过后自动 `adb install -r` → 启动应用 → 截屏/日志收集
   - 使用 `./scripts/auto-dev-loop.sh` 一键完成代码→设备的完整验证
   - 超限上报备选方案
6. **审计**：`CR` 规范复核，`QA` 核心验收，`CO` 对外汇总。

### 5.1 自动化测试工具链（消除人工干预）

| 脚本/工具 | 用途 | 人工干预点消除 |
|-----------|------|----------------|
| `./scripts/ai-gate.sh` | 代码级质量门禁 | 编译后自动检测设备安装并验证 |
| `./scripts/auto-dev-loop.sh` | 一键开发自循环 | **编译→安装→启动→截屏→日志→报告**全自动 |
| `./scripts/regression-test.sh` | P0 端到端回归 | 相机/美颜/相册核心用例自动执行 |
| `./scripts/quick-compile.sh` | 分层快速编译 | **语法→编译→Dex→APK**分层递进，失败即停 |
| `./scripts/impact-analyzer.sh` | 变更影响分析 | 自动识别影响模块、红线、需同步文档 |
| `./scripts/screenshot-diff.py` | 截图像素级对比 | 基准截图 diff，检测 UI 回归和渲染异常 |
| `./scripts/perf-baseline.sh` | 性能基线对比 | 自动提取 FPS/耗时，与基线对比告警 |
| `./scripts/crash-detector.sh` | Crash 自动检测 | 扫描 FATAL/ANR/Native crash/GL 错误 |
| `./scripts/ui-check.py` | UI 自动校验 | 黑屏/快门按钮/网格布局/关键点覆盖层检测 |
| `./scripts/test-generator.py` | 测试骨架生成 | 基于 public 方法自动生成 mockk 测试 |
| `adb-bot` Skill | ADB 命令参考 | 提供标准化设备操作命令集 |
| `intent-router` Skill | 意图路由 | 自然语言→技术术语→上下文自动加载 |
| `error-healer` Skill | 编译错误修复 | 错误分类→定向修复策略→自愈循环 |

**标准工作流（有设备连接时）**：
```bash
# RD 完成代码修改后执行：
./scripts/auto-dev-loop.sh

# 自动输出：
# - 代码检查报告 (ktlint/detekt/unit test)
# - 编译结果
# - 安装状态
# - 设备截屏 (screen_startup.png / screen_after_capture.png)
# - PicMe 日志 (logcat_picme.txt)
# - Markdown 汇总报告 (report.md)
```

## 6. 文档索引

| 类型 | 文档 |
|------|------|
| **模块规范** | `core/AGENTS.md`, `data/AGENTS.md`, `di/AGENTS.md`, `features/camera/AGENTS.md`, `features/gallery/AGENTS.md`, `features/editor/AGENTS.md`, `features/settings/AGENTS.md`, `features/debug/AGENTS.md`, `beauty-engine/AGENTS.md` |
| **技术专项** | `docs/BIG_BEAUTY_TECH_SPEC.md`, `docs/CAMERA_PREVIEW_TECH_SPEC.md`, `docs/BEAUTY_ENGINE_FALLBACK.md` |
| **AI 工具配置** | `AI_TOOLS.md`, `.kimi/AGENTS.md`, `.openclaw/workspace/`, `.lingma/skills/` |
| **写作规范** | `docs/AGENTS_SPEC.md`, `PRODUCT.md`, `docs/FEATURES.md` |

## 7. 交付审计清单

- [ ] 需求是否已在 `PRODUCT.md` 落地或保持一致。
- [ ] 交互是否已在 `docs/FEATURES.md` 落地或保持一致。
- [ ] 实现是否已在对应模块 `AGENTS.md` 补全规范。
- [ ] 是否满足 `[PRIVACY]`、`[PERF]`、`[I18N]` 三条红线。
- [ ] 是否完成编译自检与关键日志可观测性检查。
