# PicMe MNN 多模型加载/卸载改造清单（对齐官方最佳实践）

## 1. 目标

- 对齐 MNN 官方推荐的资源分层：
  - `Interpreter`（模型持有）
  - `Session`（推理数据持有）
  - 分级释放：`releaseSession` / `releaseModel` / `destroy`
- 让 LLM / ASR / 人脸检测支持**独立加载、独立卸载、互不干扰**。
- 在场景切换与内存压力下，行为可预测、可观测、可回归测试。

---

## 2. 现状问题（结合当前代码）

1. 人脸检测场景切换后自动卸载不稳定（依赖条件不合理，常驻引用导致不触发）。
2. `MnnLandmarkDetector` 的 `requireGpu` 没有完整生效（`useGpu` 传参写死）。
3. `LocalLlmEngine` / `SherpaMnnAsrEngine` 注册了资源监听，但缺少明确反注册生命周期，存在监听器累积风险。
4. `OnlineRecognizer` / `OnlineStream` 采用 `finalize()` 触发释放，不符合现代资源管理最佳实践。
5. 缺少统一“释放等级”抽象（软释放/会话释放/彻底释放），模块间语义不一致。

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

### P0-2 修正 `requireGpu` 参数链路
- **改造点**
  - `MnnLandmarkDetector` 初始化时 `useGpu = requireGpu`，禁止写死 `true`。
  - 对 `requireGpu = false` 路径实现明确 CPU fallback（或明确 fail-fast + 文档说明）。
- **涉及文件**
  - `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/MnnLandmarkDetector.kt`
  - `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/MnnRoiDetector.kt`
- **验收标准**
  - 配置 `FORCE_CPU` 时，不再尝试 GPU-only 路径。
  - GPU 不可用设备上可按策略回退或明确失败。

### P0-3 增加监听器反注册，避免泄漏
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

### P0-4 移除 `finalize()` 释放依赖
- **改造点**
  - `OnlineRecognizer` / `OnlineStream` 改为 `Closeable/AutoCloseable` + 显式 `close()`。
  - 业务层统一在 `try/finally` 或 `use {}` 中释放。
- **涉及文件**
  - `agent-core/src/main/java/com/k2fsa/sherpa/mnn/OnlineRecognizer.kt`
  - `agent-core/src/main/java/com/k2fsa/sherpa/mnn/OnlineStream.kt`
- **验收标准**
  - 不再依赖 GC 时机回收 native 资源。
  - 长时间运行 native 内存曲线稳定。

### P0-5 统一释放等级 API（跨模块一致）
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

---

## 4. 验收清单（DoD）

- [ ] 场景切换可稳定触发 face unload，不依赖手动 release。
- [ ] LLM / ASR / Face 可分别执行 SOFT / SESSION / FULL 三档释放。
- [ ] `requireGpu` 策略在 ROI 与 Landmark 一致生效。
- [ ] 移除 `finalize` 依赖后，无 native 资源泄漏回归。
- [ ] 连续 30 分钟压力切换测试无崩溃。
- [ ] 内存指标符合目标（建议）：
  - 离开相机页后 face 相关 native 内存在 10s 内明显下降。
  - 关闭语音/AI 面板后 ASR/LLM 在策略窗口内释放到预期水平。

---

## 5. 推荐实施顺序（最短路径）

1. `P0-1` 场景卸载逻辑修复
2. `P0-2` GPU 参数链路修复
3. `P0-3` 监听器反注册
4. `P0-4` 去 finalize
5. `P0-5` 统一释放等级
6. 再推进 `P1`（状态机 + runtime 共享 + cache）

