# MNN-LLM (Qwen3-1.7B) 性能优化指南

> 基于 2026-06-06 性能诊断数据
> 当前默认模型：Qwen3-1.7B-MNN（weight 1.2GB，约 3.5GB 运行时内存）
> 设备：高性能手机（8GB RAM，Adreno GPU）

---

## 1. 当前配置分析

### 1.1 已部署模型

| 模型 | 文件大小 | 运行时内存 | 配置路径 |
|------|---------|-----------|---------|
| **Qwen3-1.7B** | weight 1.2GB | ~3.5GB | `files/llm_models/qwen3-1.7b/config.json` |
| Qwen3.5-0.8B | weight 470MB | ~1.5GB（预估） | `files/llm_models/qwen3.5-0.8b/config.json` |

### 1.2 当前 config.json（Qwen3-1.7B）

```json
{
    "llm_model": "llm.mnn",
    "llm_weight": "llm.mnn.weight",
    "backend_type": "cpu",
    "thread_num": 4,
    "precision": "low",
    "memory": "low",
    "sampler_type": "mixed",
    "mixed_samplers": ["penalty", "topK", "topP", "min_p", "temperature"],
    "penalty": 1.1,
    "temperature": 0.6,
    "topP": 0.95,
    "topK": 20,
    "min_p": 0
}
```

### 1.3 当前问题

| 问题 | 现象 | 根因 |
|------|------|------|
| 内存占用过高 | Native Heap 3.61GB | 1.7B 模型 weight 1.2GB + KV Cache + 激活值 |
| 应用被 OOM Kill | 相机预览 + LLM 同时运行时被杀 | 总 PSS 6.24GB 超过 LMK 阈值 |
| 渲染卡顿 | Janky frames 18.42% | 内存压力导致 Swap 换页，GPU 竞争 |
| 高温 | GPU 81°C | CPU 后端推理，未使用 GPU/NPU |

---

## 2. MNN-LLM 配置参数详解

### 2.1 内存相关参数

| 参数 | 类型 | 当前值 | 可选值 | 说明 |
|------|------|--------|--------|------|
| `memory` | string | `low` | `low` / `normal` / `high` | 内存使用模式。`low` 减少中间缓存，`high` 加速但占内存 |
| `precision` | string | `low` | `low` / `normal` / `high` / `low_bf16` | 精度模式。`low` 使用 FP16/INT8，`high` 使用 FP32 |
| `backend_type` | string | `cpu` | `cpu` / `gpu` / `npu` | 推理后端。GPU 可减少 CPU 占用但增加 GPU 内存 |
| `thread_num` | int | 4 | 1-N | CPU 线程数。过多线程增加内存和调度开销 |

### 2.2 MNN 高级 Hint（C++ API）

```cpp
// KV Cache 大小限制（单层）
// 超过限制会换出到磁盘
interpreter->setHint(MNN::Interpreter::KVCACHE_SIZE_LIMIT, 1024); // KB

// Attention 量化选项
// 0: 不量化, 1: Q/K Int8 V Float, 2: Q/K/V Int8
interpreter->setHint(MNN::Interpreter::ATTENTION_OPTION, 2);

// Flash Attention
// 0: 不使用, 1: 使用（减少内存占用）
interpreter->setHint(MNN::Interpreter::ATTENTION_OPTION, 2 + 8); // Int8 + FlashAttn

// 使用 mmap 加载模型（减少内存占用）
interpreter->setHint(MNN::Interpreter::USE_CACHED_MMAP, 1);
```

### 2.3 关键发现

> **Memory 经验**：MNN LLM 推理时出现"Memory not Enough"错误，可能是配置中使用了 `use_mmap: true` 导致内存占用过高。建议参考历史版本配置：使用 `thread_num: 4`（而非2），不启用 `use_mmap`，保持 `memory: low` 和 `precision: low`。

---

## 3. 优化策略（按优先级排序）

### 3.1 P0：模型量化（效果最显著）

**当前模型**：Qwen3-1.7B FP16/FP32（weight 1.2GB）
**目标**：INT4 量化（weight ~350MB，减少 70%）

| 量化级别 | 模型大小 | 运行时内存 | 质量损失 | 适用场景 |
|---------|---------|-----------|---------|---------|
| FP16（当前） | 1.2GB | ~3.5GB | 无 | 高端设备 |
| INT8 | ~600MB | ~1.8GB | 轻微 | 中端设备 |
| **INT4** | **~350MB** | **~1.0GB** | 可接受 | **推荐** |

**操作步骤**：
1. 从 ModelScope 下载 Qwen3-1.7B-INT4-MNN 或自行转换
2. 替换 `llm.mnn` + `llm.mnn.weight`
3. 更新 `config.json` 中的 `precision: "low"`

**MNN 转换命令参考**：
```bash
# INT4 量化转换（需 MNN 工具链）
mnnconvert -f ONNX --modelFile qwen3-1.7b.onnx \
  --MNNModel qwen3-1.7b-int4.mnn \
  --weightQuantBits 4 \
  --weightQuantAsymmetric
```

### 3.2 P0：切换到更小的模型

**备选模型**：Qwen3.5-0.8B（已下载，weight 470MB）

| 对比 | Qwen3-1.7B | Qwen3.5-0.8B |
|------|-----------|-------------|
| Weight | 1.2GB | 470MB |
| 预估运行时 | ~3.5GB | ~1.5GB |
| 推理质量 | 较高 | 中等 |
| 适用场景 | 纯聊天页 | 相机预览共存 |

**建议**：
- 相机预览场景自动切换到 0.8B 模型
- 聊天页可手动选择 1.7B 模型

### 3.3 P1：GPU 后端切换

**当前**：`backend_type: "cpu"`
**建议**：`backend_type: "gpu"`（Vulkan）

| 后端 | CPU 占用 | GPU 内存 | 温度 | 适用场景 |
|------|---------|---------|------|---------|
| CPU | 高 | 低 | 高（CPU发热） | 低端设备 |
| GPU | 低 | 高 | 中（GPU发热） | 有 GPU 的设备 |
| NPU | 极低 | 中 | 低 | 支持 NPU 的设备 |

**注意事项**：
- GPU 后端会增加 GPU 内存占用（~500MB-1GB）
- 需要设备支持 Vulkan
- 首次加载可能有编译延迟

### 3.4 P1：动态加载/卸载

**当前问题**：LLM 模型常驻内存，即使不在聊天页

**优化方案**：

```kotlin
// 相机预览页：不加载 LLM
class CameraViewModel {
    init {
        // 进入相机页时卸载 LLM
        agentOrchestrator.unloadModel()
    }
}

// 聊天页：按需加载
class ChatViewModel {
    fun onEnter() {
        viewModelScope.launch {
            agentOrchestrator.loadModel("qwen3_1_7b")
        }
    }
    
    fun onExit() {
        agentOrchestrator.unloadModel()
    }
}
```

**收益**：
- 相机预览场景释放 ~3.5GB 内存
- 避免 OOM Kill
- 聊天页首次进入有加载延迟（~2-3秒）

### 3.5 P1：KV Cache 限制

**当前问题**：KV Cache 随对话长度无限增长

**优化方案**：

```json
{
    "max_new_tokens": 512,
    "max_context_length": 2048
}
```

或在 C++ 层设置：
```cpp
// 限制 KV Cache 大小（单层 1024KB = 1MB）
llm->set_config("{\"kv_cache_limit\": 1024}");

// 或限制最大历史长度
llm->set_config("{\"max_history\": 10}");
```

**收益**：
- 长对话场景内存不再无限增长
- 限制后最大额外内存 ~500MB

### 3.6 P2：推理参数优化

| 参数 | 当前值 | 建议值 | 说明 |
|------|--------|--------|------|
| `thread_num` | 4 | 2 | 减少线程数和内存开销 |
| `temperature` | 0.6 | 0.3 | 降低随机性，减少采样计算 |
| `topK` | 20 | 10 | 减少候选 token 数 |
| `max_new_tokens` | 8192 | 128 | 限制生成长度（Agent 场景 128 足够） |

### 3.7 P2：内存监控与自动降级

```kotlin
class MemoryMonitor {
    fun checkMemory(): LlmMode {
        val nativeHeap = getNativeHeapSize()
        return when {
            nativeHeap > 3_000_000_000 -> LlmMode.REMOTE_ONLY // >3GB 用远程
            nativeHeap > 2_000_000_000 -> LlmMode.SMALL_MODEL  // >2GB 用小模型
            else -> LlmMode.LOCAL_FULL
        }
    }
}
```

---

## 4. 推荐配置组合

### 4.1 方案 A：相机预览场景（推荐）

```json
{
    "llm_model": "llm.mnn",
    "llm_weight": "llm.mnn.weight",
    "backend_type": "cpu",
    "thread_num": 2,
    "precision": "low",
    "memory": "low",
    "max_new_tokens": 128,
    "sampler_type": "mixed",
    "temperature": 0.3,
    "topK": 10,
    "topP": 0.9
}
```

**配合**：不加载 LLM 模型，使用远程 LLM 或本地 0.8B 模型

### 4.2 方案 B：聊天页（高质量）

```json
{
    "llm_model": "llm.mnn",
    "llm_weight": "llm.mnn.weight",
    "backend_type": "gpu",
    "thread_num": 4,
    "precision": "low",
    "memory": "normal",
    "max_new_tokens": 512,
    "sampler_type": "mixed",
    "temperature": 0.6,
    "topK": 20,
    "topP": 0.95
}
```

**配合**：INT4 量化模型，限制 KV Cache

### 4.3 方案 C：低端设备（极致省内存）

```json
{
    "llm_model": "llm.mnn",
    "llm_weight": "llm.mnn.weight",
    "backend_type": "cpu",
    "thread_num": 1,
    "precision": "low",
    "memory": "low",
    "max_new_tokens": 64,
    "sampler_type": "greedy"
}
```

**配合**：0.8B 模型，动态加载

---

## 5. 验证清单

- [ ] 量化模型转换并测试推理质量
- [ ] GPU 后端兼容性测试（Vulkan 支持检测）
- [ ] 动态加载/卸载功能实现
- [ ] 内存监控 + 自动降级逻辑
- [ ] 相机预览场景不加载 LLM
- [ ] 长对话 KV Cache 增长测试
- [ ] 温度/帧率回归测试

---

## 6. 预期收益

| 优化项 | 当前 | 目标 | 收益 |
|--------|------|------|------|
| 模型量化（INT4） | 3.61GB | ~1.2GB | 内存减少 67% |
| 切换 0.8B 模型 | 3.61GB | ~1.5GB | 内存减少 58% |
| 动态加载 | 常驻 | 按需 | 相机场景释放 3.5GB |
| GPU 后端 | CPU 40% | GPU 承担 | CPU 占用降低 |
| 综合优化 | OOM 被杀 | 稳定运行 | 可用性提升 |

---

> **维护者**：RD Agent
> **最后更新**：2026-06-06
> **相关文档**：
> - `docs/06-QA/perf_trace_2026-06-06_ncnn_llm_comparison.md`
> - `docs/06-QA/perf_trace_2026-06-06_ncnn_highperf.md`
