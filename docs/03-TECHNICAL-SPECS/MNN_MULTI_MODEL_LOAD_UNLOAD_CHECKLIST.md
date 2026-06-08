# PicMe MNN 多模型加载/卸载改造清单（对齐官方最佳实践）

## 1. 目标

- 对齐 MNN 官方推荐的资源分层：
  - `Interpreter`（模型持有）
  - `Session`（推理数据持有）
  - 分级释放：`releaseSession` / `releaseModel` / `destroy`
- 让 LLM / ASR / 人脸检测支持**独立加载、独立卸载、互不干扰**。
- **Agent First 架构**：LLM 是应用驱动核心（相机/相册/设置/聊天等多页面均有 Agent 入口），LLM 生命周期策略必须与人脸/ASR 差异化——跨页面常驻，非页面级绑定。
- 在场景切换与内存压力下，行为可预测、可观测、可回归测试。

---

## 2. 现状问题（结合当前代码）

1. 人脸检测场景切换后自动卸载不稳定（依赖条件不合理，常驻引用导致不触发）。
2. `MnnLandmarkDetector` 的 `requireGpu` 没有完整生效（`useGpu` 传参写死）。
3. `LocalLlmEngine` / `SherpaMnnAsrEngine` 注册了资源监听，但缺少明确反注册生命周期，存在监听器累积风险。
4. `OnlineRecognizer` / `OnlineStream` 采用 `finalize()` 触发释放，不符合现代资源管理最佳实践。
5. 缺少统一"释放等级"抽象（软释放/会话释放/彻底释放），模块间语义不一致。
6. **LLM 与 Face/ASR 混用同一卸载策略**：当前未区分模型使用模式——LLM 需要跨页面常驻（Agent 多入口），但现有逻辑可能跟随页面切换误卸载 LLM，导致 Agent 响应延迟（冷启动 2s+）且增加功耗（反复加载）。

---

## 3. 改造清单（P0 / P1 / P2）

## P0（必须先做）

### P0-1 修正人脸检测卸载触发逻辑（场景驱动）
- **改造点**
  - 将人脸检测卸载触发条件从“引用计数”与“场景状态”解耦。
  - 相机离开（`CAMERA -> OTHER/CHAT/SETTINGS`）时可触发卸载，即使仍有 owner 存在。
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
  - `app/src/main/java/com/picme/features/camera/CameraScreen.kt`
- **验收标准**
  - 从相机页切到非相机场景后，5~10s 内触发 face unload 回调。
  - 不依赖手动 `manager.release()` 才能卸载。

### P0-2 增加监听器反注册，避免泄漏
- **改造点**
  - 给 `LocalLlmEngine` / `SherpaMnnAsrEngine` 增加 `close()/releaseAll()` 生命周期收口。
  - 在销毁时调用：
    - `unregisterSoftTrimListener`
    - `unregisterSafeUnloadListener`
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/LocalLlmEngine.kt`
  - `agent-core/src/main/java/com/picme/agent/core/voice/SherpaMnnAsrEngine.kt`
- **验收标准**
  - 反复进入/退出页面、切换 ASR/LLM 100 次后，监听器数量不增长。

### P0-3 移除 `finalize()` 释放依赖
- **改造点**
  - `OnlineRecognizer` / `OnlineStream` 改为 `Closeable/AutoCloseable` + 显式 `close()`。
  - 业务层统一在 `try/finally` 或 `use {}` 中释放。
- **涉及文件**
  - `agent-core/src/main/java/com/k2fsa/sherpa/mnn/OnlineRecognizer.kt`
  - `agent-core/src/main/java/com/k2fsa/sherpa/mnn/OnlineStream.kt`
- **验收标准**
  - 不再依赖 GC 时机回收 native 资源。
  - 长时间运行 native 内存曲线稳定。

### P0-4 统一释放等级 API（跨模块一致）
- **改造点**
  - 统一定义释放等级（建议）：
    - `SOFT`: 清缓存（如 LLM KV cache、ASR stop streaming）
    - `SESSION`: 释放 Session/Tensor（保留模型）
    - `FULL`: 释放权重与解释器（彻底卸载）
  - 人脸、ASR、LLM 都对齐这三档语义。
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
  - `agent-core/src/main/java/com/picme/agent/core/llm/MnnLlmClient.kt`
  - `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/mnn/MnnFaceDetector.kt`
  - `beauty-engine/src/main/cpp/mnn_jni_bridge.cpp`
- **验收标准**
  - 设置页或调试页可独立触发某模块三档释放，行为一致。

### 差异化生命周期原则（Agent First 架构约束）

三种模型的**使用模式**根本不同，卸载策略必须差异化：

| 模型 | 使用模式 | 作用域 | 卸载触发条件 | 保活等级 |
|------|----------|--------|-------------|----------|
| **LLM** (Qwen3-1.7B) | 跨页面常驻，Agent 驱动核心 | 全局（相机/相册/设置/聊天） | 仅内存压力 | **跨页面保活**，页面切换不触发卸载 |
| **ASR** (Sherpa) | 按需激活，语音场景独占 | 语音交互开启期间 | 语音关闭 + 冷却计时 | 页面级，语音关闭后延迟释放 |
| **Face** (ROI+Landmark) | 场景绑定，相机页独占 | 相机预览期间 | 离开相机页 + 冷却计时 | 页面级，离开即触发卸载 |

**核心规则**：
- `MnnResourceManager.onSceneChanged()` 必须区分模型类型：**LLM 永远不响应场景切换**，仅响应内存压力。
- Face 离开相机页即卸载（保留 P0-1 行为）。
- ASR 语音关闭后延迟释放（冷却时间防止频繁切换）。

---

## P1（建议紧接）

### P1-1 建立模型状态机（每模型独立）
- **改造点**
  - 每个模型维护状态：
    - `UNLOADED` -> `MODEL_LOADED` -> `SESSION_READY` -> `ACTIVE`
  - 将“是否被请求”与“是否已加载”分离。
- **收益**
  - 支持精细化策略（如：保留模型、释放会话）。
- **涉及文件**
  - `MnnResourceManager` + 各模块 wrapper（LLM/ASR/Face）

### P1-2 引入共享 Runtime（多模型串行链路）
- **改造点**
  - 对串行模型（例如 ROI + Landmark）评估 `Interpreter::createRuntime(...)` 共享 runtime。
- **收益**
  - 降低总内存和重复初始化开销。
- **注意**
  - 输入必须使用映射/拷贝填充，避免直接指针写入导致内存重分配风险。

### P1-3 增强缓存策略（GPU 初始化）
- **改造点**
  - 对 GPU 后端增加 cache 文件策略：
    - `setCacheFile`
    - `updateCacheFile`（resize 后更新）
- **收益**
  - 显著降低二次冷启动耗时。

### P1-4 LLM 跨页面保活策略（Agent First 核心）
- **改造点**
  - `MnnResourceManager.onSceneChanged()` 增加模型类型判断：LLM 跳过场景卸载逻辑，仅响应内存压力。
  - 引入 `ModelUsagePattern` 枚举（`CROSS_PAGE_PERSISTENT` / `PAGE_SCOPED` / `SESSION_SCOPED`），各模型注册时声明。
  - LLM 保活等级定义：
    - **HOT**：模型 + Session 全就绪，首 Token 延迟 < 100ms（高功耗 ~800MB+ native）
    - **WARM**：仅保留模型（Interpreter），无 Session，首 Token 延迟 ~500ms（中功耗 ~600MB）
    - **COLD**：模型未加载，首 Token 延迟 ~2s（低功耗 ~0MB native 额外占用）
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
  - `agent-core/src/main/java/com/picme/agent/core/LocalLlmEngine.kt`
  - `agent-core/src/main/java/com/picme/agent/core/model/AgentModels.kt`（新增 `ModelUsagePattern`）
- **验收标准**
  - 相机 → 相册 → 设置 → 聊天，LLM 全程保持 WARM+，不触发 FULL 卸载。
  - Face 离开相机页后正常卸载（不受 LLM 策略影响）。
  - 可通过调试面板查看每个模型的保活等级。

---

## P2（优化项）

### P2-1 结构化可观测性
- 统一埋点：
  - `model_load_start/end`
  - `session_create/release`
  - `model_release`
  - `memory_before/after`
- 输出模块：`LLM` / `ASR` / `FaceROI` / `FaceLandmark`

### P2-2 压测与回归自动化
- 增加场景切换压测脚本：
  - 相机 <-> 设置 <-> 聊天循环
  - 语音开关循环
  - 模型下载后热切换
- 产出：崩溃率、加载耗时、native 内存峰值

### P2-3 LLM 热恢复策略（降低感知延迟）
- **改造点**
  - 当 LLM 处于 WARM 状态（模型已加载，无 Session）时，用户触发 Agent 交互自动创建 Session 并恢复到 HOT，延迟控制在 500ms 内。
  - 当 LLM 处于 COLD 状态时，用户触发 Agent 交互显示 "Agent 启动中..." 加载态，异步加载模型（~2s），加载完成后自动恢复。
  - 预加载策略：App 启动时后台异步加载 LLM 到 WARM，无需等待首次交互。
  - LRU 驱逐：当 native 内存压力达到阈值时，优先 SOFT 降级 LLM（释放 KV Cache），其次 SESSION 降级（释放 Session），最后 FULL 卸载 Face（已离开相机页则直接卸载）。此驱逐顺序由手动或内存压力信号触发，**不依赖电量自动降级**。
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
  - `app/src/main/java/com/picme/features/common/chat/AgentLoadingIndicator.kt`（新增或修改）
  - `app/src/main/java/com/picme/domain/usecase/AiAgentUseCase.kt`
- **验收标准**
  - WARM → HOT 恢复延迟 < 500ms。
  - COLD → HOT 加载期间显示 loading UI，不阻塞主线程。
  - App 启动后 5s 内 LLM 达到 WARM 状态。

---

## 4. 验收清单（DoD）

- [ ] 场景切换可稳定触发 face unload，不依赖手动 release。
- [ ] LLM / ASR / Face 可分别执行 SOFT / SESSION / FULL 三档释放。
- [ ] 移除 `finalize` 依赖后，无 native 资源泄漏回归。
- [ ] 连续 30 分钟压力切换测试无崩溃。
- [ ] **Agent First 验收**：
  - [ ] 相机 → 相册 → 设置 → 聊天，LLM 全程保持 WARM+，不触发 FULL 卸载。
  - [ ] 任意页面触发 Agent 交互时，WARM 状态下恢复延迟 < 500ms。
  - [ ] Face 离开相机页正常卸载，不受 LLM 保活策略干扰。
- [ ] 内存指标符合目标（建议）：
  - 离开相机页后 face 相关 native 内存在 10s 内明显下降。
  - 语音关闭后 ASR 在冷却窗口内释放到预期水平。
  - LLM 跨页面切换时 native 内存无明显波动（不触发反复加载/卸载）。

---

## 5. 推荐实施顺序（最短路径）

1. `P0-1` 场景卸载逻辑修复（先修 Face 独立卸载）
2. `P0-2` 监听器反注册
3. `P0-3` 去 finalize
4. `P0-4` 统一释放等级 API
5. `P1-4` LLM 跨页面保活（Agent First 核心，依赖 P0-4 释放等级 + P0-1 场景解耦）
6. `P1-1` 模型状态机（依赖 P0-4 + P1-4 策略明确后建模）
7. `P1-2` 共享 Runtime + `P1-3` GPU cache
8. `P2-3` LLM 热恢复（依赖 P1-4 保活等级）
9. `P2-1` 结构化可观测性 + `P2-2` 压测回归

---

## 6. 任务化执行表（负责人 / 工时 / 风险）

| ID | 任务 | 负责人 | 预计工时 | 依赖 | 风险等级 | 风险说明 | 缓解措施 |
|---|---|---|---|---|---|---|---|
| P0-1 | 修正 FaceDetection 场景卸载触发逻辑 | RD | 1.5 人天 | 无 | 高 | 场景切换与引用计数耦合，改错会导致误卸载 | 先加日志与单测，灰度开关控制 |
| P0-2 | LLM/ASR 监听器反注册与生命周期收口 | RD | 1 人天 | 无 | 中 | 误删监听导致内存策略不生效 | 引入注册计数日志与回归脚本 |
| P0-3 | 移除 `finalize` 依赖，改 `Closeable` 显式释放 | RD | 1.5 人天 | P0-2 | 高 | 释放时机变化可能触发 native 崩溃 | 全链路压测 + `try/finally` 审计 |
| P0-4 | 统一 SOFT/SESSION/FULL 释放等级 API | RD | 2 人天 | P0-1, P0-2 | 高 | 跨模块语义对齐复杂，易出现行为回归 | 先定义接口契约，再分模块接入 |
| P1-4 | LLM 跨页面保活策略（Agent First 核心） | RD | 2 人天 | P0-4, P0-1 | 高 | 页面切换误触发 LLM 卸载影响 Agent 体验 | 差异化表格先行，开关控制灰度 |
| P1-1 | 建立每模型独立状态机 | RD | 2 人天 | P0-4, P1-4 | 中 | 状态迁移遗漏导致卡死或重复加载 | 状态图 + 穷举迁移单测 |
| P1-2 | 评估并接入共享 Runtime | RD | 2 人天 | P1-1 | 中 | 内存复用导致输入指针失效 | 强制映射/拷贝输入，禁用直接填充 |
| P1-3 | GPU cache 策略（`setCacheFile/updateCacheFile`） | RD | 1 人天 | P1-2 | 低 | cache 脏数据导致初始化异常 | 版本化 cache key + 失败回退 |
| P2-3 | LLM 热恢复策略（预加载 + loading UI） | RD | 1.5 人天 | P1-4 | 中 | COLD 恢复时 UI 卡顿 | 异步加载 + loading 态兜底 |
| P2-1 | 结构化可观测性埋点 | RD | 1.5 人天 | P0-4 | 低 | 指标口径不一致 | 统一事件 schema 与命名规范 |
| P2-2 | 压测与回归自动化脚本 | QA + RD | 2 人天 | P0 全部 | 中 | 用例覆盖不足 | 按场景矩阵维护必测集 |

### 6.1 里程碑建议

| 里程碑 | 范围 | 目标产出 | 通过标准 |
|---|---|---|---|
| M1（本周） | P0-1 ~ P0-2 | 卸载触发修复 + 生命周期闭环 | 无监听器增长，Face 场景切换可卸载 |
| M2（下周） | P0-3 ~ P0-4 | 释放语义统一（SOFT/SESSION/FULL） | 三档释放可独立触发且行为一致 |
| M3（第 3 周） | P1-4 | **Agent First 保活** | LLM 跨页面不卸载 |
| M4（第 4 周） | P1-1 ~ P1-3, P2-3 | 状态机 + 共享 Runtime + GPU cache + 热恢复 | WARM → HOT < 500ms，冷启动耗时下降 |
| M5（持续） | P2-1 ~ P2-2 | 可观测性与自动化回归 | 压测 30 分钟稳定无崩溃 |

### 6.2 进度跟踪模板（可直接复制到任务系统）

- [x] P0-1 FaceDetection 场景卸载触发逻辑修复
- [x] P0-2 LLM/ASR 监听器反注册
- [x] P0-3 去 `finalize` 显式释放
- [x] P0-4 统一释放等级 API
- [x] P1-4 LLM 跨页面保活策略
- [ ] P1-5 功耗感知动态降级
- [x] P1-1 每模型状态机
- [ ] P1-2 共享 Runtime 接入
- [ ] P1-3 GPU cache 策略
- [ ] P2-3 LLM 热恢复策略
- [ ] P2-1 结构化可观测性
- [ ] P2-2 压测与回归自动化

### 6.3 风险门禁（上线前必须满足）

- [ ] 连续场景切换 100 次，无 native crash
- [ ] LLM/ASR/Face 任一模块可单独 FULL 卸载，不影响其余模块
- [ ] 退出相机页后 Face 模型在目标时间窗内释放，LLM 不受影响
- [ ] 语音关闭后 ASR 释放符合预期
- [ ] LLM 跨页面切换时保持 WARM+，不触发 FULL 卸载
- [ ] WARM → HOT 恢复延迟 < 500ms
- [ ] 内存压力下 LRU 驱逐顺序正确：LLM SOFT → LLM SESSION → Face FULL

