# 性能对比诊断报告：开启本地 LLM 前后（ASR + NCNN人脸检测 + 美颜）

> 采集时间：2026-06-06
> 设备：51912a5c（高性能手机）
> 场景A（基准）：ASR + NCNN 人脸检测 + 美颜
> 场景B（对比）：ASR + NCNN 人脸检测 + 美颜 + **本地 LLM**

---

## 1. 核心指标对比

| 指标 | 场景A（无LLM） | 场景B（+LLM） | 变化 | 影响 |
|------|---------------|---------------|------|------|
| **Native Heap** | **1.72 GB** | **3.61 GB** | **+110%** | 严重 |
| **总 PSS** | **2.08 GB** | **6.24 GB** | **+200%** | 严重 |
| **总 RSS** | **2.20 GB** | **4.05 GB** | **+84%** | 严重 |
| **Swap PSS** | **54 MB** | **2.33 GB** | **+4213%** | 严重 |
| **帧率抖动（legacy）** | **0.89%** | **18.42%** | **+1970%** | 严重 |
| **帧率抖动（janky）** | **0.03%** | **0.05%** | +67% | 轻微 |
| **渲染延迟 99th** | **8ms** | **22ms** | **+175%** | 明显 |
| **GPU 延迟 99th** | **3ms** | **7ms** | **+133%** | 明显 |
| **温度（电池）** | **36.1°C** | **41.1°C** | **+5°C** | 明显 |
| **温度（quiet_therm）** | **34.2°C** | **37.9°C** | **+3.7°C** | 明显 |
| **线程数** | **75** | **78** | +3 | 轻微 |
| **总渲染帧数** | 42,305 | 14,727 | -65% | 运行时间差异 |

---

## 2. 内存详细对比

| 内存区域 | 场景A（无LLM） | 场景B（+LLM） | 变化 |
|----------|---------------|---------------|------|
| Native Heap | 1.72 GB | **3.61 GB** | **+2.89 GB** |
| Java Heap | 88 MB | 79 MB | -9 MB |
| EGL mtrack | 75.6 MB | 85.0 MB | +9.4 MB |
| Graphics | 95.4 MB | 104.9 MB | +9.5 MB |
| Unknown | 46.9 MB | 41.5 MB | -5.4 MB |
| Code (.so/.jar/.apk) | 37.6 MB | 10.5 MB | -27.1 MB |

### 关键发现

- **Native Heap 暴涨 2.89 GB**：这是 LLM 模型加载的直接结果
- **Swap PSS 从 54MB 暴涨到 2.33 GB**：系统开始大量换页，内存压力极大
- **总 PSS 6.24 GB**：已接近/超过典型 Android 设备的内存限制（8GB 手机可用约 5-6GB）

---

## 3. 渲染性能对比

| 渲染指标 | 场景A（无LLM） | 场景B（+LLM） | 变化 |
|----------|---------------|---------------|------|
| 50th percentile | 5ms | 6ms | +1ms |
| 90th percentile | 5ms | 10ms | +5ms |
| 95th percentile | 5ms | 13ms | +8ms |
| **99th percentile** | **8ms** | **22ms** | **+14ms** |
| GPU 50th | 1ms | 1ms | 持平 |
| GPU 90th | 2ms | 3ms | +1ms |
| GPU 95th | 3ms | 4ms | +1ms |
| **GPU 99th** | **3ms** | **7ms** | **+4ms** |
| Janky frames (legacy) | 0.89% | **18.42%** | **+17.53%** |
| Missed Vsync | 7 | 7 | 持平 |

### 关键发现

- **99th 渲染延迟从 8ms 恶化到 22ms**：接近 16.6ms（60fps）阈值，已出现掉帧
- **Legacy janky frames 从 0.89% 飙升到 18.42%**：每 5 帧就有 1 帧卡顿
- GPU 延迟也同步恶化，说明 GPU 同样受到内存压力影响

---

## 4. 温度对比

| 传感器 | 场景A（无LLM） | 场景B（+LLM） | 变化 |
|--------|---------------|---------------|------|
| 电池 | 36.1°C | **41.1°C** | **+5°C** |
| quiet_therm | 34.2°C | **37.9°C** | **+3.7°C** |
| CPU 簇0 平均 | ~68°C | ~81°C | **+13°C** |
| CPU 簇1 平均 | ~64°C | ~80°C | **+16°C** |
| GPU 平均 | ~61°C | ~81°C | **+20°C** |

### 关键发现

- **GPU 温度从 ~61°C 飙升到 ~81°C**：LLM 推理大量使用 GPU/NPU
- **CPU 温度同步上升 13-16°C**：整体热负载显著增加
- **电池温度 41.1°C**：已接近用户可感知的温热阈值（>42°C 明显发热）

---

## 5. 线程分析对比

| 线程特征 | 场景A（无LLM） | 场景B（+LLM） |
|----------|---------------|---------------|
| 总线程数 | 75 | 78 |
| PicMe-CameraAna | Running | Sleeping |
| PicMe-CameraCap | Sleeping | Sleeping |
| PicMe-AgentStat | Sleeping | Sleeping |
| CameraPreviewRe | Sleeping | Sleeping |
| AudioRecord | Sleeping | Sleeping |
| RenderThread | 2 × Running | 2 × Sleeping |
| DefaultDispatch | 10+ | 10+ |
| GPU completion | 无 | **有** |

### 关键发现

- 线程数仅增加 3 个，**线程爆炸不是主要问题**
- 新增 `GPU completion` 线程，与 LLM GPU 推理相关
- 所有关键线程从 Running 转为 Sleeping，可能因为 **内存压力导致系统调度受限**

---

## 6. 瓶颈分析

### 6.1 内存瓶颈（P0）
- **Native Heap 3.61 GB** 是最大问题
- **Swap 2.33 GB** 说明系统已开始将内存换出到 ZRAM/Swap
- 换页操作导致 CPU 额外开销，形成恶性循环

### 6.2 渲染瓶颈（P1）
- 18.42% legacy janky frames 已影响用户体验
- 99th 延迟 22ms 意味着部分帧超过 16.6ms，出现肉眼可见的掉帧

### 6.3 温度瓶颈（P2）
- GPU 81°C 可能触发温控降频
- 如果持续运行，温度会继续上升

---

## 7. 根因推断

本地 LLM（MNN-LLM/Qwen）加载后：

1. **模型权重占用大量 Native Heap**（~2-3 GB）
2. **推理过程中激活值/ KV Cache 持续占用内存**
3. **GPU/NPU 高负载导致温度上升**
4. **内存压力触发系统换页，Swap PSS 暴涨**
5. **换页和内存竞争导致渲染线程延迟增加**

---

## 8. 优化建议

### 8.1 内存优化（P0）
- **量化模型**：使用 INT4/INT8 量化减少模型内存占用 50-75%
- **动态加载/卸载**：LLM 非对话时段卸载模型，释放内存
- **限制 KV Cache 长度**：设置最大上下文长度，避免无限增长
- **内存预警**：当 Native Heap > 3GB 时自动降级到远程 LLM

### 8.2 渲染优化（P1）
- **降低预览分辨率**：从 1920×1080 降至 1280×720 或更低
- **减少 CameraX ImageReader 缓冲数**
- **LLM 推理与渲染错峰**：避免推理高峰与渲染 vsync 冲突

### 8.3 温控优化（P2）
- **监控 GPU 温度**：>80°C 时降低 LLM 推理频率
- **CPU/GPU 频率限制**：在相机预览场景限制最大频率
- **散热设计**：考虑在 LLM 运行时降低美颜效果

### 8.4 架构优化（P3）
- **远程 LLM 回退**：内存不足时自动切换到云端推理
- **LLM 推理批处理**：合并多个请求减少重复加载
- **模型分片加载**：按需加载模型层，而非全量加载

---

## 9. 稳定性事故：应用被系统 OOM Kill

### 9.1 事故现象

采集结束后发现：

| 现象 | 说明 |
|------|------|
| PID 从 32397 消失 | 应用进程已终止 |
| 重启后内存 2.07GB | 回到无 LLM 时的基线水平 |
| 无显式崩溃日志 | 系统 LMK（Low Memory Killer）行为 |

### 9.2 事故分析

- **6.24 GB PSS + 2.33 GB Swap** 超过了设备的 LMK 阈值
- 当系统内存紧张时，PicMe 作为最大内存占用者成为首选 kill 目标
- 即使高性能手机（2.75GHz CPU）也无法承受这种内存压力

### 9.3 业务影响

| 场景 | 结果 |
|------|------|
| 仅相机预览（无LLM） | 正常运行 |
| 相机预览 + 本地LLM | **OOM 被杀** |
| 聊天页（无相机）+ 本地LLM | 可能存活（需验证） |

**核心矛盾**：相机预览（2GB）+ 本地 LLM（3GB）= **5GB+ 内存需求**，超过了 Android 单应用的安全内存上限（通常 3-4GB）。

---

## 10. 结论

| 场景 | 可用性评估 |
|------|-----------|
| 无 LLM | 流畅运行，无性能问题 |
| **+ 本地 LLM** | **OOM 被杀，无法稳定运行** |

**开启本地 LLM 后，应用从「流畅」变为「被杀+卡顿+高发热+高内存压力」状态。**

核心矛盾：**LLM 模型内存占用（~3GB）与相机美颜管线内存占用（~2GB）叠加，总内存需求超过设备安全上限。**

**本地 LLM 当前无法在相机预览场景稳定运行，必须实施量化或动态加载后才能上线。**

---

## 11. 附录

### 11.1 Trace 文件
- 场景A（无LLM）：`/tmp/picme_trace_new.perfetto-trace`（128MB）
- 场景B（+LLM）：`/tmp/picme_trace_llm.perfetto-trace`（98MB）

### 11.2 采集命令参考

```bash
# 性能数据快照
adb shell dumpsys cpuinfo | grep com.picme
adb shell dumpsys meminfo com.picme
adb shell dumpsys gfxinfo com.picme
adb shell dumpsys battery | grep temperature
adb shell cat /sys/class/thermal/thermal_zone*/temp

# 线程列表
adb shell ps -T -p <PID>

# Perfetto 采集
adb shell "perfetto --txt -c /data/misc/perfetto-configs/perfetto_config.pbtxt \
  -o /data/misc/perfetto-traces/trace.perfetto-trace --background"
```
