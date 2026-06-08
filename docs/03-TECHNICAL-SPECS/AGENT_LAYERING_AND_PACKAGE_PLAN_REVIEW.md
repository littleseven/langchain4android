# Agent 分级推理与包划分方案（Review Draft）

> 版本：v1.1（纳入 L1 正确性与自愈闭环）
>
> 目的：用于评审 `agent-core` 的分级推理策略与包结构演进方向。

---

## 1. 背景与目标

当前 `agent-core` 已具备 `L1/L2/L3/L4` 分级框架，但存在“定义与执行不完全一致”的问题（尤其是 L3 在部分路径仍回退到 L2）。

本方案目标：

1. 明确分级语义（每级做什么、默认走本地还是云端）
2. 明确 `agent-core` 包边界（Android-First 前提）
3. 给出稳定性优先的 L2 策略（结合 0.8B 实测）
4. 建立“伪成功”识别、反馈与自愈闭环（避免错误被缓存固化）

---

## 2. 关键前提（已对齐）

1. `agent-core` 作为 Android 平台库，`api` 层允许依赖 `android.*`（不强制纯 Kotlin）。
2. 隐私红线优先：敏感内容（`RESTRICTED`）必须本地处理。
3. 移动端优先目标：稳定性 > 延迟峰值 > 理论最优能力。

---

## 3. 分级定义（L0~L4）

| 级别 | 定义 | 典型输入 | 输出契约 | 目标时延 |
|---|---|---|---|---|
| L0 | 规则直达（不走LLM） | 精确口令/固定短语 | 单个 `AgentCommand` | < 10ms |
| L1 | 意图缓存（不走LLM） | 高频口语、轻微模糊 | 单个命令或预置 `BatchExecute` | < 20ms |
| L2 | 单轮结构化推理 | 1~3个动作、无复杂条件依赖 | 单命令或 `BatchExecute` | 200~1200ms |
| L3 | 计划执行（Plan + Execute） | 条件分支、等待、步骤依赖 | `ExecutePlan` + `StepResult[]` | 800~3000ms |
| L4 | ReAct 兜底（限步） | 开放式问题、L2/L3失败 | 文本/限步动作序列 | 1~4s |

### 3.1 准入门槛（建议）

- `L0`：命中白名单规则
- `L1`：命中缓存（含别名/轻模糊）
- `L2`：无条件分支、无观察闭环
- `L3`：出现条件词、等待条件、步骤依赖任一
- `L4`：L2/L3失败或高歧义开放式请求

### 3.2 退出条件（建议）

- `L0/L1`：命中即返回
- `L2`：失败后进入 L4（或最多一次简化重试）
- `L3`：计划失败降级 L2；执行失败进入 L4 解释
- `L4`：限步（2~3步）后停止并返回可解释结果

---

## 4. 各级默认本地/云端策略

| 级别 | 默认位置 | 说明 |
|---|---|---|
| L0 | 本地 | 规则引擎 |
| L1 | 本地 | 缓存命中 |
| L2 | 云端优先（建议） | 结构化解析成功率与稳定性更高 |
| L3 | 云端规划 + 本地执行 | 云端生成计划，端上执行命令 |
| L4 | 云端优先 | 兜底语义理解/复杂对话 |

### 4.1 覆盖规则（优先级）

1. 隐私优先：`RESTRICTED` 强制本地
2. 模式优先：`LOCAL` 模式下 L2~L4 需本地降级方案
3. 资源优先：温度/内存压力触发本地禁用与云端接管

---

## 5. L2 本地策略：机会型 vs 长期稳定默认

### 5.1 术语定义

- **机会型本地 L2**：仅在资源条件良好时启用本地 L2，随时可切云端。
- **长期稳定默认 L2**：把某一路（本地或云端）作为长期主干通道，要求长时稳定。

### 5.2 结合 0.8B 结论

基于 `docs/06-QA/perf_trace_2026-06-06_llm_0_8b_vs_1_7b_comparison.md`：

- 冷机阶段：0.8B 可用，显著优于 1.7B
- 长时热机：CPU/Swap/PSS恶化，稳定性风险上升

结论：

- 0.8B 更适合作为 **机会型本地 L2**
- 不建议作为 **长期稳定默认 L2**（当前阶段）

### 5.3 机会型本地 L2 建议门槛

- 温度 > 42°C：强制切云端
- 内存压力/trim 触发：切云端
- 推理超时连续超阈值（如近N次 > 800ms）：切云端
- 相机重负载并发（ASR+人脸+美颜）：本地 L2 降级或互斥

---

## 6. 包划分建议（Android-First，v3）

```text
com.picme.agent.core
├── api
│   ├── command
│   ├── context
│   ├── capability
│   ├── execution
│   ├── policy
│   └── android
├── runtime
│   ├── entry
│   ├── inference
│   ├── parsing
│   ├── planning
│   ├── execution
│   ├── capability
│   ├── state
│   ├── memory
│   └── policy
├── platform
│   ├── llm/local
│   ├── llm/remote
│   ├── mnn
│   ├── storage
│   ├── voice
│   └── logging
├── facade
│   ├── AgentOrchestrator
│   └── AgentConfigurator
└── internal/bootstrap
```

### 6.1 现有类落位建议

- `InferenceRouter`、`AdaptiveStrategySelector`、`IntentCache` → `runtime.inference`
- `AgentCommandParser`、`PromptBuilder` → `runtime.parsing`
- `ExecutionEngine`、`ExecutionReporter` → `runtime.execution`
- `CapabilityRegistry`、`CrossPageCommandQueue`、`CommandExecutor` → `runtime.capability`
- `SceneManager` → `runtime.state`
- `PrivacyGuard` → `runtime.policy`
- `LocalLlmEngine`、`RemoteOrchestrator`、`mnn/*`、`voice/*`、`MemoryManager(DataStore)` → `platform.*`
- `AgentOrchestrator`、`AgentConfigurator` → `facade`

---

## 7. 当前与目标差距（需优先收敛）

1. L3 在部分入口实际回退为 L2，导致“定义是L3、执行是L2”。
2. L4 当前更偏 Chat fallback，而非完整 ReAct（限步闭环）。
3. 条件求值能力仍偏占位，复杂条件执行能力不足。
4. 策略入口存在分叉风险（core/app 双路径）。

---

## 8. L1 缓存正确性与伪成功治理（新增）

### 8.1 问题定义

需要重点识别并治理以下场景：

- **伪成功（False Success）**：命令执行成功，但与用户本意不一致。
- 典型例子：用户说“开美白”，系统执行“开磨皮”；界面有变化但语义错误。

### 8.2 三段一致性校验（建议）

一次执行结果应拆分为三段校验，不再仅看执行是否成功：

1. **Intent（本意）**：用户语句抽取出的目标域、目标槽位、方向、约束
2. **Command（命令语义）**：系统将执行的域、槽位、方向
3. **Outcome（结果状态）**：执行前后状态 diff 的真实变化

只有 `Intent == Command == Outcome` 才定义为 `TRUE_SUCCESS`。

### 8.3 结果分类（建议）

| 分类 | 定义 | 缓存写入 |
|---|---|---|
| `TRUE_SUCCESS` | 本意、命令、结果一致 | 允许 |
| `PARTIAL_SUCCESS` | 仅完成部分子意图 | 禁止 |
| `FALSE_SUCCESS` | 执行成功但语义错误 | 禁止 + 降权 |
| `UNSAFE_SUCCESS` | 语义错误且涉及高风险操作 | 禁止 + 熔断 |

**缓存写入门槛**：

`cache_write = execution_ok && intent_alignment_ok && outcome_match_ok`

### 8.4 L1 命中策略收敛（建议）

- 缓存 key 维度：`normalizedText + scene + mode + capabilityVersion`
- 模糊匹配仅用于低风险命令；高风险命令（拍照/删除/录制开关）仅精确匹配
- 缓存项 TTL + 版本失效（prompt/model/capability 变更时失效）
- 连续误命中自动驱逐，避免错误固化

### 8.5 自愈闭环（Detect -> Repair -> Learn）

1. **Detect**：识别 `FALSE_SUCCESS/UNSAFE_SUCCESS`
2. **Repair**：自动补偿（可逆操作优先回滚）
3. **Retry**：升级路径重试（L1 -> L2，必要时 L2 -> L3/L4）
4. **Learn**：缓存降权/驱逐、加入混淆黑名单

### 8.6 用户参与原则（最小化）

- 正常命中/自动修正成功：用户无感
- 中风险歧义：一次快捷确认
- 高风险不可逆：必须确认

目标：**90% 无感、9% 一键确认、1% 手动澄清**。

---

## 9. 实施顺序（建议）

### Phase 0（低风险，先行）

- 增加一致性判定事件与分类埋点（`TRUE/PARTIAL/FALSE/UNSAFE`）
- 在 L1 链路增加缓存写入门槛（仅 `TRUE_SUCCESS` 可学习）

### Phase 1（低风险）

- 包迁移（move），不改业务行为
- 建立 `runtime.entry` 单一策略入口

### Phase 2（中风险）

- 打通真实 L3（计划生成 + 执行）
- 定义统一回退链路（L3 -> L2 -> L4）

### Phase 3（中风险）

- 引入“机会型本地 L2”门槛控制
- 实现热态与内存压力自动切换

### Phase 4（优化）

- 补全 L4 限步闭环或明确降级为 ChatFallback（术语统一）
- 落地伪成功自愈链路（补偿 + 重试 + 学习）
- 增加策略命中率与切换稳定性看板

---

## 10. 评审待确认项（Review Checklist）

- [ ] 是否接受“L2 云端优先 + 本地机会型”的稳态策略
- [ ] 是否接受 Android-First API 边界（`api` 可含 `android.*`）
- [ ] 是否将 `runtime.entry` 设为唯一策略入口
- [ ] 是否在本迭代内打通真实 L3（不再名义L3）
- [ ] 是否将 L4 定义收敛为“ReAct限步”或“ChatFallback”之一
- [ ] 是否接受 `FALSE_SUCCESS/UNSAFE_SUCCESS` 分类并接入自愈闭环
- [ ] 是否将“仅 `TRUE_SUCCESS` 写 L1 缓存”作为硬约束

---

## 11. 一句话结论

在当前设备与模型条件下，推荐采用：

**L0/L1 本地、L2 云端优先（本地机会型）、L3 云端规划端上执行、L4 云端兜底；并以一致性校验与自愈闭环兜住“伪成功”，确保错误不被 L1 缓存固化。**

