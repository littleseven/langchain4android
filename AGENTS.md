# PicMe AI Agent 系统：唯一事实来源 (SSOT)

本文件定义 PicMe 项目中 AI Agent 的协作机制、治理边界与全局红线。

> 定位说明：`AGENTS.md` 是**顶层治理文档**，只保留跨模块通用规则。
> 具体产品需求、交互细节、技术实现细节请分别查阅 `PRODUCT.md`、`docs/FEATURES.md`、模块 `AGENTS.md` 与专项技术文档。

## 1. 角色定义与层级
- **[CO] 协调者**：任务分级、流程路由、状态板维护与统一交付汇总。
- **[PM] 产品经理**：`PRODUCT.md` 的权威维护者，负责业务价值、交互逻辑（UX Flow）与 I18N 文案。
- **[RD] 全栈工程师**：负责从 Domain 到 UI 的完整实现，并执行自愈闭环。
- **[CR] 规范守护者**：负责规范一致性审查与技术文档合规裁决。
- **[QA] 质量专家**：负责边界测试、性能基线与端到端验收。

### 1.1 团队运行模式（2026-04）
- **单实例多角色**：同一会话内按 `CO -> PM -> RD -> CR -> QA` 串行流转。
- **触发口令**：`自动执行`（默认自动推进）、`保守执行`（关键节点确认）。
- **自愈预算**：RD 单任务最多自愈 2 次，超限必须上报并给出备选方案。
- **红线暂停**：仅在隐私风险、不可逆操作或缺失外部输入时请求用户确认。

## 2. 文档体系与边界

### 2.1 三层文档架构
```text
PRODUCT.md (What: 目标与约束)
    ↓
docs/FEATURES.md (How: 交互与体验规则)
    ↓
模块 AGENTS.md / 技术专项文档 (Implementation: 实现细则)
```

### 2.2 单一可信源原则（MUST）
- 产品目标与验收口径：以 `PRODUCT.md` 为准。
- 交互流程与体验规则：以 `docs/FEATURES.md` 为准。
- 技术实现、代码规范与检查清单：以模块 `AGENTS.md` 与 `docs/AGENTS_SPEC.md` 为准。

### 2.3 顶层 AGENTS 内容边界（MUST）
- **应保留**：角色协作、全局红线、文档治理、执行流程、通用审计要求。
- **应下沉**：
  - 具体业务规则与交互文案 -> `PRODUCT.md` / `docs/FEATURES.md`
  - 模块实现细节与代码示例 -> 各模块 `AGENTS.md`
  - 深度技术方案（EGL、Shader、SDK 集成）-> 专项技术文档
- **禁止**：在顶层 `AGENTS.md` 持续堆叠模块级技术细节。

### 2.4 文档同步规则（MUST）
- 新增功能：按 `PRODUCT.md -> docs/FEATURES.md -> 模块 AGENTS.md` 顺序更新。
- 修改功能：同步更新上述所有相关文档，严禁只改代码不改文档。
- 技术路线调整：对应技术文档必须在 24 小时内更新并标记旧方案状态（废弃/备选）。
  
## 3. 全局红线 [严格执行]
- **[PRIVACY] 隐私至上**：所有 AI 处理（人脸、OCR、分类）必须 100% 本地化，严禁依赖云端推理。
- **[PERF] 极致反馈**：交互反馈 < 100ms，拍摄快门延迟 < 50ms。
- **[I18N] 多语言同步**：禁止硬编码用户可见文案；必须同步 `values`、`values-zh-rCN`、`values-zh-rTW`。

## 4. 工程基线规范
- **架构模式**：Clean Architecture（Domain -> Data -> Features）。
- **缩进标准**：Kotlin/Java 4 空格；XML/JSON/MD 2 空格。
- **Lambda 规范**：显式命名参数，禁止使用隐式 `it`。
- **状态管理**：UI 状态优先使用 `Sealed Class` 建模。
- **导入规范**：禁止通配符导入（`*`）。
- **结构化日志**：日志标签统一为 `PicMe:[ModuleName]`。

> 详细代码风格、导入分组与审查清单请查阅 `docs/AGENTS_SPEC.md` 与对应模块 `AGENTS.md`。

## 5. AI 执行工作流（Self-Heal Loop）
1. **触发**：按 `自动执行` 或 `保守执行` 启动。
2. **路由**：`CO` 进行复杂度分级并维护状态板。
3. **对齐**：`PM/RD` 对齐 `PRODUCT.md`、`docs/FEATURES.md` 与模块 `AGENTS.md`。
4. **执行**：`RD` 做原子化修改并记录关键决策。
5. **自愈**：
   - 立即修复编译 Error 与相关 Warning。
   - 执行 `./gradlew assembleDebug`；失败则基于日志继续自修复。
   - 超出自愈预算时上报并给出备选方案。
6. **审计验收**：`CR` 做规范复核，`QA` 做核心验收，最终由 `CO` 对外汇总。

## 6. 模块与技术文档索引

### 6.1 模块实现规范
- `app/src/main/java/com/picme/data/AGENTS.md`
- `app/src/main/java/com/picme/di/AGENTS.md`
- `app/src/main/java/com/picme/features/camera/AGENTS.md`
- `app/src/main/java/com/picme/features/gallery/AGENTS.md`
- `app/src/main/java/com/picme/features/editor/AGENTS.md`
- `app/src/main/java/com/picme/features/settings/AGENTS.md`
- `app/src/main/java/com/picme/features/debug/AGENTS.md`

### 6.2 专项技术文档
- 相机预览：`docs/CAMERA_PREVIEW_TECH_SPEC.md`
- 大美丽（主引擎）：`docs/BIG_BEAUTY_TECH_SPEC.md`
- 容灾降级统一说明：`docs/BEAUTY_ENGINE_FALLBACK.md`
### 6.3 文档写作与审查规范
- 顶层/模块 AGENTS 写作规范：`docs/AGENTS_SPEC.md`
- 产品需求：`PRODUCT.md`
- 交互细节：`docs/FEATURES.md`

## 7. 交付审计清单（跨模块）
- [ ] 需求是否已在 `PRODUCT.md` 落地或保持一致。
- [ ] 交互是否已在 `docs/FEATURES.md` 落地或保持一致。
- [ ] 实现是否已在对应模块 `AGENTS.md` 补全规范。
- [ ] 是否满足 `[PRIVACY]`、`[PERF]`、`[I18N]` 三条红线。
- [ ] 是否完成编译自检与关键日志可观测性检查。
