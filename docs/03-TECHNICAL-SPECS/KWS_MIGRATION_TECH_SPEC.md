# PicMe KWS 唤醒词 + 语音栈迁移技术方案

> **文档编号**: TECH-SPEC-KWS-001
> **关联模块**: `agent-core/platform/voice/`、`agent-core/platform/mnn/`、`app/features/camera/voice/`
> **创建日期**: 2026-06-10
> **目标架构**: sherpa-onnx.aar 统一语音栈（KWS always-on + ASR on-demand + LLM 独立 MNN）
> **当前架构**: sherpa-mnn-jni 耦合 MNN（ASR/LLM/FaceDetect 共享 libMNN.so）

---

## 执行摘要

本方案将 PicMe 的语音栈从 `sherpa-mnn-jni`（MNN 后端）迁移到 `sherpa-onnx.aar`（ONNX Runtime 后端），实现：

1. **KWS always-on 唤醒词检测**：专用 3.3M 参数关键词检测模型（~14MB），替代当前的"VAD→ASR 转录→文本匹配"方案
2. **ASR on-demand 指令转录**：唤醒后启动全量 ASR（~282MB），完成后释放
3. **LLM 与语音栈彻底解耦**：ASR 不再依赖 `libMNN.so`，LLM 成为 `libMNN.so` 唯一使用者
4. **人脸检测完全独立**：NCNN/MediaPipe 路径不再受 MNN 全局状态影响

---

## 1. 当前架构问题诊断

### 1.1 隐式耦合链

```
libsherpa-mnn-jni.so → libMNN.so ← libmnn_llm.so
         ↓                               ↓
    [ASR 推理]                      [LLM 推理]
         ↓
libMNN.so 还被 FaceDetectManager（MNN 路径）引用
```

**核心矛盾**：`libMNN.so` 被三个子系统共享，`MnnResourceManager`（803 行）的引用计数 + 全局释放锁 + 三级释放策略本质上是一个被迫的补丁：

```kotlin
// MnnResourceManager.kt 的设计负担
enum class ReleaseLevel { SOFT, SESSION, FULL }  // 因为 libMNN.so 不能随便释放
object MnnGlobalReleaseLock { ... }               // 因为 libMNN.so 全局状态非线程安全
```

### 1.2 当前唤醒词方案缺陷

```
VAD(RMS 25dB) → 录音(最长4s) → ASR 全量转录(282MB) → 文本匹配"小觅"
                                                    ↑
                                          每次检测都跑 282MB 模型
                                          延迟数秒，无法 always-on
```

---

## 2. 目标架构

### 2.1 分层运行时

```
┌─────────────────────────────────────────────────────────┐
│                    应用层 (App Layer)                      │
│                                                         │
│  CameraScreen  →  VoiceCommandCoordinator                │
│                      ├─ WakeWordEngine (新 KWS)           │
│                      ├─ PushToTalkEngine                  │
│                      └─ AiAgentUseCase                   │
└──────────┬──────────────┬──────────────┬────────────────┘
           │              │              │
┌──────────▼──────┐ ┌─────▼──────┐ ┌────▼──────────────┐
│  语音栈 (ONNX)   │ │ LLM (MNN)  │ │  FaceDetect       │
│                 │ │            │ │  (NCNN/MediaPipe) │
│ libsherpa-      │ │ libMNN.so  │ │  独立 .so         │
│ onnx-jni.so     │ │ +          │ │                   │
│ +               │ │ libmnn_llm │ │                   │
│ libonnxruntime  │ │ .so        │ │                   │
│ .so             │ │            │ │                   │
│                 │ │            │ │                   │
│ ├─ Keyword      │ │ Qwen3.5-2B │ │ Det10G + 2D106   │
│ │  Spotter(KWS) │ │            │ │                   │
│ ├─ OnlineRecog  │ │            │ │                   │
│ │  nizer(ASR)   │ │            │ │                   │
│ └─ Vad(DNN VAD) │ │            │ │                   │
│                 │ │            │ │                   │
│ 完全独立        │ │ MNN 唯一   │ │ 完全独立          │
│ 加载/释放       │ │ 使用者     │ │ 加载/释放         │
└─────────────────┘ └────────────┘ └───────────────────┘
```

**关键性质**：三个栈各自拥有独立的 Native 运行时，互相不共享全局状态。

### 2.2 API 层映射

| 当前 (sherpa-mnn) | 目标 (sherpa-onnx) | 说明 |
|---|---|---|
| `com.k2fsa.sherpa.mnn.OnlineRecognizer` | `com.k2fsa.sherpa.onnx.OnlineRecognizer` | ASR，API 几乎一致 |
| `com.k2fsa.sherpa.mnn.OnlineStream` | `com.k2fsa.sherpa.onnx.OnlineStream` | 流对象 |
| `com.k2fsa.sherpa.mnn.FeatureConfig` | `com.k2fsa.sherpa.onnx.FeatureConfig` | 特征配置 |
| — | `com.k2fsa.sherpa.onnx.KeywordSpotter` | **新增** KWS |
| — | `com.k2fsa.sherpa.onnx.HomophoneReplacerConfig` | **新增** 同音字配置 |
| — | `com.k2fsa.sherpa.onnx.Vad` | **可选** Silero DNN VAD |
| `MnnGlobalReleaseLock` | **删除** | ONNX RT 无此全局状态问题 |
| `MnnResourceManager` | `ModelResourceCoordinator` | **简化**：仅管理 LLM 生命周期 |

---

## 3. 生命周期状态机

### 3.1 模型加载状态

```
每个子系统独立管理加载/释放，互不耦合：
```

```
         ┌──────────┐    ┌──────────┐    ┌──────────┐
         │  KWS     │    │  ASR     │    │  LLM     │
         │ (ONNX)   │    │ (ONNX)   │    │ (MNN)    │
         ├──────────┤    ├──────────┤    ├──────────┤
UNLOADED │          │    │          │    │          │
    ↓    │  释放    │    │  释放    │    │  释放    │
LOADED   │  加载    │    │  加载    │    │  加载    │
    ↓    │          │    │          │    │          │
ACTIVE   │  推理中  │    │  推理中  │    │  推理中  │
         └──────────┘    └──────────┘    └──────────┘
              ↑ 独立            ↑ 独立            ↑ 独立
             不共享任何 Native 状态
```

### 3.2 核心原则：分时复用，绝不叠加

```
[休眠态]                    [唤醒态]                     [推理态]                  [休眠态]
KWS ████████████           KWS ░░░░░░░░░░░              KWS ░░░░░░░░░░░            KWS ████████████
ASR ────────────    →      ASR ────────────     →       ASR ████████████    →      ASR ────────────
LLM ────────────           LLM ────────────             LLM ████████████           LLM ────────────

 常驻 ~45MB                 KWS 暂停                     峰值 ~2GB                  常驻 ~45MB
                            ASR 加载 (~400MB)            LLM + ASR 同时存在
                            LLM 按需加载 (~1.5GB)        转录完成 → ASR 立即释放
                                                        LLM 推理完成 → 立即释放
```

**设计约束**：
1. KWS 与 ASR 绝不同时 ACTIVE（KWS 暂停后 ASR 加载）
2. ASR 转录完成后立即释放（不缓存，不等待 GC）
3. LLM 推理完成后立即释放（可选保留 KV cache 用于 follow-up 对话）
4. 人脸检测按需加载/释放（NCNN/MediaPipe，不受语音栈影响）

### 3.3 状态转移表

| 事件 | KWS | ASR | LLM | FaceDetect | 内存峰值 |
|------|-----|-----|-----|------------|---------|
| **App 启动，进入相机页** | ACTIVE | UNLOADED | UNLOADED | ACTIVE | ~125MB |
| **检测到唤醒词** | PAUSED | LOADING→ACTIVE | UNLOADED | ACTIVE | ~525MB |
| **ASR 识别完成** | ACTIVE | UNLOADED | UNLOADED | ACTIVE | ~125MB |
| **LLM 推理指令** | PAUSED | UNLOADED | LOADING→ACTIVE | ACTIVE | ~1.7GB |
| **LLM 推理完成** | ACTIVE | UNLOADED | TRIMMED | ACTIVE | ~125MB |
| **离开相机页** | ACTIVE | UNLOADED | UNLOADED | UNLOADED | ~45MB |
| **App 退到后台 (30s)** | UNLOADED | UNLOADED | UNLOADED | UNLOADED | 0MB |
| **⚠️ LLM + ASR 同时** | PAUSED | ACTIVE | ACTIVE | ACTIVE | ~2.0GB |

> **注意**：LLM + ASR 同时 ACTIVE 只在理论上发生（如：用户需要多轮对话，ASR 持续转录后续指令）。实际实现中应优先释放 ASR 再加载 LLM。

---

## 4. 内存压力分析

### 4.1 各组件内存预算（实测/估算）

#### KWS（sherpa-onnx-kws-zipformer-wenetspeech-3.3M）

| 项目 | 大小 | 说明 |
|------|------|------|
| 模型文件（INT8） | ~14MB | encoder + decoder + joiner .onnx |
| 权重张量 | ~3.3MB | 3.3M params × 1 byte (INT8) |
| 编码器前向缓冲区 | ~15MB | 注意力矩阵 + CNN 中间层 |
| 解码器 + Joiner | ~5MB | 自回归解码状态 |
| ONNX Runtime 分摊 | ~12MB | 线程池、Arena 分配器（与 ASR 共享） |
| **KWS 独占** | **~25MB** | — |
| **KWS + ONNX RT 基础** | **~45MB** | always-on 休眠态 |

#### ASR（sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20）

| 项目 | 大小 | 说明 |
|------|------|------|
| 模型文件（INT8） | ~282MB | encoder + decoder + joiner .onnx |
| 权重张量 | ~282MB | INT8 量化，与 ONNX RT 内部对齐 |
| 编码器前向缓冲区 | ~80-120MB | 流式 chunk（100ms），带上下文缓存 |
| 解码器 + Joiner | ~20-30MB | 波束搜索 / 贪婪解码状态 |
| CTC/RNNT 格 | ~15MB | — |
| **ASR 总计** | **~380-430MB** | 转录完成后释放 |

#### LLM（Qwen3.5-2B-MNN）

| 项目 | 大小 | 说明 |
|------|------|------|
| 模型文件 | ~1.2GB | — |
| 权重张量 | ~1.2GB | MNN Interpreter 加载 |
| KV Cache（2048 tokens） | ~512MB | 按上下文长度增长 |
| MNN Runtime | ~20MB | libMNN.so + libmnn_llm.so |
| **LLM 总计** | **~1.8GB** | 推理完成后释放 |

#### FaceDetect（NCNN 路径）

| 项目 | 大小 | 说明 |
|------|------|------|
| 模型文件 | ~21MB | Det10G + 2D106（NCNN 格式） |
| 运行时 | ~50MB | CNN 中间激活 + 特征点缓存 |
| **FaceDetect 总计** | **~70MB** | 相机页常驻 |

#### ONNX Runtime 共享基础

| 项目 | 大小 | 说明 |
|------|------|------|
| libonnxruntime.so | ~25MB | Native 库 |
| Arena 分配器 | ~10-15MB | 线程池 + 内存池 |
| **ONNX RT 基础** | **~35-40MB** | KWS 和 ASR 共享，仅加载一次 |

### 4.2 各阶段内存账本

| 阶段 | KWS | ASR | LLM | FaceDetect | ONNX RT | 系统 | **Native 总计** | **进程总计** |
|------|-----|-----|-----|------------|---------|------|----------------|-------------|
| ① 休眠态 | 25MB | — | — | 70MB | 35MB | 700MB | **830MB** | ~1.5GB |
| ② ASR 转录 | 25MB(PAUSED) | 400MB | — | 70MB | 35MB | 700MB | **1.23GB** | ~1.9GB |
| ③ LLM 推理 | 25MB(PAUSED) | — | 1.8GB | 70MB | 35MB | 700MB | **2.63GB** | ~3.3GB |
| **④ ⚠️ ASR+LLM** | **25MB** | **400MB** | **1.8GB** | **70MB** | **35MB** | **700MB** | **3.03GB** | **~3.7GB** |

> 进程总计 = Native 总计 + Java Heap (~200MB) + Graphics (~300-500MB for GL/CameraX)

### 4.3 设备分级评估

| 设备 | RAM | 进程上限* | 休眠态 | ASR | LLM | ASR+LLM |
|------|-----|----------|--------|-----|-----|---------|
| 低端 (4GB) | 4GB | ~1.5-2GB | ✅ | ⚠️ 临界 | ❌ OOM | ❌ OOM |
| 中端 (6GB) | 6GB | ~2-2.5GB | ✅ | ✅ | ⚠️ 临界 | ❌ OOM |
| 中高端 (8GB) | 8GB | ~3-3.5GB | ✅ | ✅ | ⚠️ 临界 | ⚠️ 临界 |
| 高端 (12GB+) | 12GB | ~4-5GB | ✅ | ✅ | ✅ | ✅ |

> \* Android 进程上限取决于厂商 ROM 配置和系统版本，并非固定值。上表为典型估算。

### 4.4 关键结论

1. **Always-on KWS (~45MB)** 在所有设备上都安全
2. **ASR 转录 (~1.23GB)** 在 6GB+ 设备安全，4GB 设备可能触发 LMK
3. **LLM 推理 (~2.63GB)** 需要 8GB+ 设备，中低端需要降级到远程推理
4. **ASR + LLM 同时 (~3.03GB)** 仅在 12GB+ 设备安全，**不应作为默认行为**

---

## 5. 内存安全策略

### 5.1 设备分级策略

```kotlin
enum class DeviceTier {
    LOW,      // < 6GB RAM, LLM 使用远程推理, ASR 可能触发警告
    MID,      // 6-8GB, LLM 可用但监控 Native Heap, ASR 安全
    HIGH,     // >= 8GB, 所有模型本地运行
}

fun getDeviceTier(): DeviceTier {
    val totalRam = getTotalRamMb()
    return when {
        totalRam < 6000 -> DeviceTier.LOW
        totalRam < 8000 -> DeviceTier.MID
        else -> DeviceTier.HIGH
    }
}
```

### 5.2 LLM 加载前检查

```kotlin
fun canLoadLlm(): Boolean {
    val currentNativeHeap = Debug.getNativeHeapAllocatedSize() / 1048576L
    val llmEstimatedMb = 1800L
    val availableMb = getAvailableNativeMemoryMb()

    return when (deviceTier) {
        DeviceTier.LOW -> false  // 低端设备不使用本地 LLM
        DeviceTier.MID -> availableMb > llmEstimatedMb + 200L  // 留 200MB 缓冲
        DeviceTier.HIGH -> true
    }
}
```

### 5.3 三级释放策略（简化版）

相比当前 `MnnResourceManager.ReleaseLevel(SHARD/SESSION/FULL)` 的复杂三级体系，解耦后简化为：

| 层级 | 操作 | 适用对象 | 延迟 |
|------|------|---------|------|
| **TRIM** | 清 KV Cache / 停止流式 / 释放 Intermediate Tensors | LLM / ASR | 立即 |
| **UNLOAD** | 释放模型权重 + 销毁 Interpreter/Session | LLM / ASR / KWS | 立即 |

不再需要 "SESSION" 中间层级，因为不存在跨子系统的共享状态。

### 5.4 后台行为

```kotlin
// App 后台 30s: TRIM 所有活跃模型
// App 后台 60s: UNLOAD 所有模型（释放 KWS，停止 always-on）
```

---

## 6. 迁移实施计划

### 6.1 改动范围

| 模块 | 文件 | 改动类型 | 估时 |
|------|------|---------|------|
| **agent-core** | `SherpaMnnAsrEngine.kt` → `SherpaOnnxAsrEngine.kt` | 重写（包名+模型格式） | 2h |
| **agent-core** | 新增 `KeywordSpotterEngine.kt` | 新建 | 1.5h |
| **agent-core** | `MnnResourceManager.kt` → `ModelResourceCoordinator.kt` | 简化 | 1h |
| **agent-core** | 删除 `MnnGlobalReleaseLock` | 删除 | 0.5h |
| **agent-core** | `AudioRecorder.kt` | 微调（不变） | — |
| **app** | `WakeWordEngine.kt` | 重写（KWS 集成） | 2h |
| **app** | `VoiceCommandCoordinator.kt` | 适配 | 1h |
| **app** | `PushToTalkEngine.kt` | 适配 | 0.5h |
| **app** | `CameraScreen.kt` / `PicMeApplication.kt` | 适配资源管理器 | 0.5h |
| **build** | `settings.gradle.kts` / `app/build.gradle.kts` | 切换 AAR 依赖 | 0.5h |
| **数据** | `llm_models.json` | 新增 KWS 模型 + 更新 ASR 模型源 | 0.5h |
| **下载** | `LlmModelDownloadManager.kt` | 新增 KWS 模型类型 | 1h |
| **测试** | 新增 `KeywordSpotterEngineTest.kt` | 单元测试 | 1.5h |
| **测试** | 更新 `WakeWordEngineTest.kt` | 适配 | 1h |

**总计**: ~13 小时

### 6.2 实施顺序

```
Phase 1: 基础迁移（2天）          Phase 2: KWS 集成（1天）        Phase 3: 解耦优化（1天）
┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│ 1. 添加 AAR 依赖      │    │ 5. 实现 KWS 引擎       │    │ 8. 简化资源管理器     │
│ 2. 更新模型配置        │    │ 6. 重写 WakeWordEngine │    │ 9. 删除 MnnReleaseLock│
│ 3. 重写 ASR 引擎       │    │ 7. KWS 单元测试        │    │ 10. 集成测试          │
│ 4. 下载 ONNX ASR 模型  │    │                      │    │ 11. 全链路验证        │
└──────────────────────┘    └──────────────────────┘    └──────────────────────┘
         ↓                          ↓                          ↓
    ASR 功能持平              唤醒词体验质变               代码质量提升
```

---

## 7. 模型配置

### 7.1 更新 `llm_models.json`

```json
[
  {
    "id": "sherpa-onnx-zipformer-zh-en",
    "name": "Sherpa-ONNX Zipformer 中英双语",
    "description": "ONNX Runtime 流式语音识别模型，支持中文+英文，端侧实时推理",
    "type": "ASR",
    "size": 295334441,
    "sources": {
      "ModelScope": "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
    },
    "files": [
      "encoder-epoch-99-avg-1.int8.onnx",
      "decoder-epoch-99-avg-1.int8.onnx",
      "joiner-epoch-99-avg-1.int8.onnx",
      "tokens.txt"
    ],
    "tags": ["ASR", "speech", "chinese", "english"]
  },
  {
    "id": "sherpa-onnx-kws-zipformer-zh",
    "name": "Sherpa-ONNX KWS Zipformer 中文唤醒词",
    "description": "3.3M 参数关键词检测模型，支持自定义唤醒词，适合 always-on",
    "type": "KWS",
    "size": 14000000,
    "sources": {
      "ModelScope": "csukuangfj/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
    },
    "files": [
      "encoder-epoch-12-avg-2.int8.onnx",
      "decoder-epoch-12-avg-2.int8.onnx",
      "joiner-epoch-12-avg-2.int8.onnx",
      "tokens.txt",
      "keywords.txt"
    ],
    "tags": ["KWS", "keyword", "wake", "chinese"]
  }
]
```

### 7.2 新增 `keywords.txt`

```
小觅
小蜜
小秘
小米
小咪
```

### 7.3 AAR 依赖

```kotlin
// app/build.gradle.kts
dependencies {
    // 替换:
    // implementation(files("libs/libsherpa-mnn-jni.so"))
    // 为:
    implementation("com.k2fsa:sherpa-onnx:1.13.2")
}
```

---

## 8. 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| ONNX ASR 模型精度与 MNN 不一致 | 中 | 高 | Phase 1 对比测试转录质量 |
| ONNX Runtime 在低端设备崩溃 | 低 | 高 | 设备分级 + 回退到 Android System ASR |
| KWS 误触发率高于预期 | 中 | 中 | 可调关键词分数 + 冷却期 + VAD 辅助 |
| 低端设备 LLM OOM | 高 | 中 | 设备分级：低端用远程推理 |
| AAR 54MB 导致 APK 过大 | 中 | 低 | AAB 按架构拆分的 .so 在实际安装中只包含 arm64 |

---

## 9. 验收标准

- [ ] KWS always-on 在休眠态常驻 < 45MB Native Heap
- [ ] KWS 唤醒延迟 < 100ms（从用户说话到检测到关键词）
- [ ] KWS 误触发率 < 1次/小时（正常环境）
- [ ] ASR 转录质量不低于当前 MNN 版本（对比测试 ≥ 95% 字符准确率）
- [ ] LLM 可以完全卸载（Native Heap 回落到休眠态水平）
- [ ] 多次 KWS→ASR→LLM 循环无内存泄漏（Native Heap 不持续增长）
- [ ] 低端设备（4GB）不触发 OOM（KWS + 远程 LLM）
- [ ] `MnnGlobalReleaseLock` 完全删除，无残留引用
- [ ] 人脸检测（NCNN）不受语音栈切换影响

---

## 10. 相关文档

- `docs/03-TECHNICAL-SPECS/SHERPA_MNN_COMPARISON_ANALYSIS.md` — 当前 Sherpa-MNN 实现分析
- `AGENTS.md` — 顶层治理文档
- `agent-core/AGENTS.md` — Agent Core 模块规范
- `app/src/main/java/com/picme/features/camera/voice/WakeWordEngine.kt` — 当前唤醒词引擎

