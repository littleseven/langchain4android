# AI Agent 架构设计备忘 (AI Agent Architecture Memo)

> **边界声明（Boundary Statement）**
> - 本文档为技术备忘，记录 AI Agent 模块的架构分析结论与演进建议。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 模块级实现细节以 `app/src/main/java/com/picme/domain/agent/AGENTS.md` 为准。

**模块定位**: PicMe 相机 AI 助手"小觅"的 Runtime 架构与推理模式选型

**主要维护者**: [RD] 全栈工程师

**阅读对象**: RD、AI Agent

**版本**: 1.0

**最后更新**: 2026-05-26

---

## 1. 核心产品逻辑 (Core Product Logic)

- **[PRIVACY] 100% 本地推理**: 人脸、对话数据严禁上云，PrivacyGuard 需实际拦截数据流
- **[PERF] 交互反馈 < 100ms**: LLM 推理需在后台完成，UI 不得阻塞；端侧 Qwen3.5-2B 单次推理目标 < 200ms
- **[I18N] 多语言同步**: System Prompt 及用户可见回复禁止硬编码中文，需接入 string 资源
- **[OFFLINE] 离线可用**: 本地模型未下载时提供明确引导，而非静默失败
- **[TYPE_SAFE] 命令类型安全**: AgentCommand / AgentAction 必须使用 Sealed Class，禁止字符串魔法值

---

## 2. 技术实现规范 (Technical Implementation)

### 2.1 当前架构模式

当前架构为 **单轮 Function Calling（指令解析-执行模式）**，非 ReAct 模式。

```
用户输入 → 构建 System Prompt → LLM 一次性生成 → 解析为 AgentCommand → Capability 执行
```

**核心组件**:

| 组件 | 职责 | 状态 |
|------|------|------|
| `AgentOrchestrator` | Prompt 构建、LLM 推理、响应解析、命令路由 | 🔄 部分实现 |
| `LocalLlmEngine` | 封装 MNN-LLM 客户端，支持多模型切换 | ✅ 已落地 |
| `MemoryManager` | DataStore 持久化对话历史，按 session 隔离 | ✅ 已落地 |
| `CapabilityRegistry` | Capability 注册与命令分发 | ✅ 已落地 |
| `Capability` | 领域能力抽象（相机控制、相册管理等） | 🔄 部分实现 |
| `PrivacyGuard` | 隐私策略检查（预留接口） | ⏳ 规划中 |

### 2.2 与 ReAct 模式的区别

| 维度 | 当前架构 | ReAct |
|------|---------|-------|
| 推理步骤 | 单轮：输入 → 输出 | 多轮 Thought-Action-Observation 循环 |
| LLM 调用次数 | 1 次 | 多次（每步一次） |
| 中间观察 | 无，执行结果直接返回用户 | 工具结果反馈给 LLM 继续推理 |
| 决策深度 | 单步指令 | 多步骤规划、条件判断、错误恢复 |
| 延迟 | 低（单次推理） | 高（多轮 RTT） |

### 2.3 端侧推理模式选型（Qwen3.5-2B）

**不推荐完整 ReAct**，原因：
- 2B 模型 COT（链式思考）能力弱，Thought 质量不稳定
- 相机场景要求 < 100ms 反馈，多轮推理无法满足 `[PERF]` 红线
- 端侧电池/发热敏感

**推荐演进路径**:

```
Phase 1（当前）: 单轮 Function Calling
       ↓
Phase 2: 多指令批量执行（Batch Function Calling）
       ↓
Phase 3: Plan-and-Execute（预定义模板）
       ↓
Phase 4: 轻量 ReAct（限 2-3 步，仅复杂场景）
```

### 2.4 远端推理模式选型

远端模式下**减少 LLM 调用次数**比端侧更重要（RTT 成本主导）。

**推荐分层自适应模式**:

| 层级 | 模式 | 适用场景 | RTT 次数 |
|------|------|---------|---------|
| Layer 1 | 本地规则缓存 | "拍照"等高频指令 | 0 |
| Layer 2 | Batch Function Calling | 连续动作指令 | 1 |
| Layer 3 | Plan-and-Execute | 条件/多步任务 | 1（规划）+ 本地执行 |
| Layer 4 | ReAct（限步） | 极少数动态推理场景 | N（≤3，超时熔断） |

**远端优化策略**:
- 连接池 + Keep-Alive 复用 TCP
- 100ms 防抖窗口合并请求
- 2s 超时降级到本地规则或文本提示
- 常见意图响应缓存（LruCache）

### 2.5 Qwen3.5-2B Prompt 工程建议

- 使用模型原生 `tools` 参数定义相机控制函数，替代手写 JSON format prompt
- System Prompt 精简：只传关键状态，大模型理解力强无需冗长描述
- 历史裁剪更激进：`maxHistoryRounds = 5`（端侧 10）

---

## 3. Agent 执行规约 (Execution Rules)

- **JSON 解析**: 必须使用 `kotlinx.serialization.json`，严禁正则提取字段
- **System Prompt**: 禁止硬编码在 `AgentOrchestrator` 内，需抽象为 `PromptBuilder` 策略接口
- **Capability 注册**: 新增 Capability 必须同步更新 `CapabilityRegistry` 的命令映射，禁止遗漏
- **Memory 持久化**: `appendConversation` 需引入内存缓存 + 批量刷盘，禁止每条消息 2 次 DataStore IO
- **ChatML 格式**: `LocalLlmEngine` 禁止硬编码 Qwen ChatML，需抽象 `ChatFormat` 接口按模型注册
- **线程安全**: `AgentOrchestrator` 的 `agentMode` / `currentModelId` 需同步控制，禁止并发修改
- **模型加载**: 快速连续调用需加并发锁，避免触发多次加载
- **隐私拦截**: `PrivacyGuard` 必须接入 LLM 输入输出流和 Capability 执行链路，禁止仅做断言
- **日志规范**: 统一使用 `PicMe:Agent` 前缀，禁止各组件标签不一致

---

## 4. 常见陷阱检查清单 (Checklist)

- [ ] JSON 解析是否使用了正则？（必须用 kotlinx.serialization，正则无法处理嵌套/转义）
- [ ] System Prompt 是否硬编码在类内？（需按场景插件化，违反 OCP）
- [ ] 新增 AgentCommand 子类后是否同步更新了 CapabilityRegistry 的映射？（易遗漏）
- [ ] MemoryManager 是否存在 IO 放大问题？（每条消息 2 次 DataStore 读写需优化）
- [ ] ChatML 格式是否与模型耦合？（换模型如 Llama/Gemma 需改代码）
- [ ] CameraCapability 回调是否为 null？（11 个可选回调需 Builder 模式或统一接口）
- [ ] 模型加载是否有并发控制？（快速连续调用可能触发多次加载）
- [ ] PrivacyGuard 是否实际拦截了数据流？（当前仅断言，未接入 LLM 和 Capability）
- [ ] 用户可见文案是否硬编码中文？（需接入 strings.xml 支持多语言）
- [ ] AgentAction.Success 是否携带了语义冗余？（应携带执行结果数据，而非原命令）

---

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ 100% 本地推理 → `LocalLlmEngine` + `MnnLlmClient`，`PrivacyGuard` 拦截远程调用
- ✅ 交互反馈 < 100ms → 单轮 Function Calling，异步推理不阻塞 UI
- ✅ 多语言同步 → System Prompt 和用户回复接入 string 资源
- ✅ 离线可用 → 模型未下载时明确引导用户到设置页下载

**技术决策记录**:
- **选择单轮 Function Calling 而非 ReAct**: 相机场景延迟敏感，2B 模型推理能力弱，ReAct 多轮 RTT 不可接受
- **选择 Batch Function Calling 作为 Phase 2**: 性价比最高，单次 RTT 可处理"自然妆然后拍照"类连续指令
- **选择 Plan-and-Execute 作为复杂任务方案**: 1 次 RTT 获取 plan，本地零延迟执行，适合条件分支
- **Capability 通过回调注入而非直接依赖 UI**: 保持 Domain 层纯净，符合 Clean Architecture
- **DataStore 持久化对话历史**: 类型安全、支持 Flow、跨进程安全，优于 SharedPreferences

---

## 附录：架构演进路线图

```
┌─────────────────────────────────────────────────────────────────┐
│                        端侧推理演进路线                           │
├─────────────────────────────────────────────────────────────────┤
│  P0（立即）:                                                    │
│  1. JSON 解析改用 kotlinx.serialization                         │
│  2. System Prompt 提取为 PromptBuilder 接口                      │
│  3. MemoryManager 引入内存缓存 + 批量刷盘                         │
│                                                                 │
│  P1（近期）:                                                    │
│  4. 支持 Batch Function Calling（JSON 数组输出）                  │
│  5. ChatFormat 抽象，支持多模型切换                              │
│  6. Capability 命令映射改为注解驱动或属性声明                      │
│                                                                 │
│  P2（中期）:                                                    │
│  7. Plan-and-Execute 模板引擎                                   │
│  8. 本地意图缓存（高频指令 0ms 响应）                              │
│  9. Token 预算管理与上下文压缩                                    │
│                                                                 │
│  P3（远期）:                                                    │
│  10. 轻量 ReAct（限 2-3 步，超时熔断）                           │
│  11. 记忆摘要（长期对话不丢失上下文）                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 参考文档

- [AGENTS.md](../AGENTS.md) — 顶层治理规则
- [PRODUCT.md](../PRODUCT.md) — 产品需求规格
- [docs/FEATURES.md](FEATURES.md) — 功能交互细节
- [docs/AGENTS_SPEC.md](AGENTS_SPEC.md) — 模块文档编写规范
- `app/src/main/java/com/picme/domain/agent/` — 源码目录
