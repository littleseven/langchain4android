# 性能诊断报告：ASR + NCNN人脸检测 + 美颜（高性能手机）

> 采集时间：2026-06-06 20:05
> 设备：51912a5c（高性能手机）
> 场景：同时开启 ASR、NCNN 人脸检测、美颜
> Trace 文件：`/tmp/picme_trace_new.perfetto-trace`（128MB）

---

## 1. 设备信息

| 指标 | 值 |
|------|-----|
| 设备序列号 | 51912a5c |
| CPU 最大频率 | 2.75 GHz |
| 电量 | 100% |
| 当前温度（电池） | 36.1°C |
| 当前温度（quiet_therm） | 34.2°C |

---

## 2. 核心性能指标

| 指标 | 当前值 | 状态 |
|------|--------|------|
| **CPU 占用** | **49%**（40% user + 9.5% kernel） | 高负载 |
| **总线程数** | **75** | 偏多 |
| **Native Heap** | **1.72 GB** | 偏高 |
| **总 PSS** | **2.08 GB** | 偏高 |
| **帧率抖动** | **0.89%**（legacy）/ **0.03%**（janky） | 优秀 |
| **渲染延迟** | 99th: **8ms** / GPU 99th: **3ms** | 流畅 |
| **总渲染帧数** | 42,305 | — |

---

## 3. 线程分析

关键线程列表（共 75 线程）：

| 线程名 | TID | 状态 | 说明 |
|--------|-----|------|------|
| `com.mamba.picme` (主线程) | 30590 | **R**unning | 主线程活跃 |
| `PicMe-CameraAna` | 2227 | **R**unning | 相机分析线程忙碌 |
| `Jit thread pool` | 420 | **R**unning | JIT 编译活跃 |
| `Profile Saver` | 444 | **R**unning | ART Profile |
| `RenderThread` | 457, 471 | **R**unning | 双渲染线程 |
| `PicMe-CameraCap` | 564 | Sleeping | 相机采集 |
| `PicMe-AgentStat` | 567 | Sleeping | Agent 状态 |
| `CameraPreviewRe` | 623 | Sleeping | 预览渲染 |
| `AudioRecord` | 1476 | Sleeping | ASR 音频录制 |
| `pool-3/5/6-thread-1` | 475, 481, 484 | Sleeping | 线程池（各1线程） |
| `DefaultDispatch` × 10+ | — | Sleeping | Kotlin Coroutines |

### 发现

- 没有显式的 `ncnn`、`mnn` 或 `mediapipe` 命名线程
- NCNN 推理可能运行在 `PicMe-CameraAna` 或匿名线程中
- `pool-*-thread-1` 各仅1线程，比之前 MediaPipe 的线程爆炸（10+）好很多

---

## 4. 内存分析

| 区域 | 大小 | 占比 |
|------|------|------|
| Native Heap | 1.72 GB | 82.5% |
| Java Heap | 88 MB | 4.2% |
| EGL mtrack | 75.6 MB | 3.6% |
| Unknown | 46.9 MB | 2.2% |
| Graphics | 95.4 MB | 4.6% |

**Native Heap 1.72GB 是主要内存消耗**，推测来自：
- NCNN 模型加载（人脸检测模型）
- CameraX ImageReader 缓冲池（1280×720 × 多帧）
- GPU 纹理/Framebuffer（美颜渲染管线）

---

## 5. 温度监控

| 传感器 | 温度 |
|--------|------|
| 电池 | 36.1°C |
| quiet_therm | 34.2°C |
| CPU 簇0 | 66.4°C ~ 69.5°C |
| CPU 簇1 | 60.2°C ~ 67.2°C |
| GPU | 59.4°C ~ 63.3°C |

**温度正常**，未触发降频。

---

## 6. 瓶颈定位

### 6.1 CPU 占用 49% — 主要瓶颈
- 主进程消耗了整机 **49%** 的 CPU（整机 24%）
- 用户态 40% + 内核态 9.5%，说明计算密集型任务主导

### 6.2 Native Heap 1.72GB — 内存瓶颈
- 远超普通相机应用（通常 200-500MB）
- 主要贡献者：NCNN 模型 + CameraX 图像缓冲 + GPU 美颜管线

### 6.3 线程数 75 — 潜在调度开销
- 虽然比之前的 MediaPipe 线程爆炸好很多
- 但 `DefaultDispatch` 线程池创建了 10+ 线程，可能存在过度并行

---

## 7. 与之前 MediaPipe 设备的对比

| 指标 | 之前设备（MediaPipe） | 当前设备（NCNN） | 变化 |
|------|----------------------|------------------|------|
| CPU 占用 | 235% | **49%** | 大幅下降 |
| 温度 | 37.7°C → 持续上升 | 36.1°C | 更凉爽 |
| 帧率抖动 | 22% | **0.89%** | 极大改善 |
| 线程爆炸 | 10+ MediaPipe 线程 | 75 线程（可控） | 改善 |
| Native Heap | ~1.7GB | **1.72GB** | 持平 |
| 渲染延迟 99th | 较高 | **8ms** | 流畅 |

**结论**：换用 NCNN + 高性能手机后，**CPU 占用和帧率稳定性大幅改善**，但 **Native Heap 1.72GB 仍是主要瓶颈**。

---

## 8. 优化建议

### 8.1 Native Heap 优化（优先级 P0）
- 检查 NCNN 模型是否重复加载（多个模型实例）
- CameraX ImageReader 缓冲数量是否可精简
- 美颜渲染 FBO/纹理复用

### 8.2 线程池优化（优先级 P1）
- `DefaultDispatch` 线程池限制最大并发数
- 考虑使用 `Dispatchers.IO` 的 `limitedParallelism`

### 8.3 ASR 音频缓冲（优先级 P2）
- `AudioRecord` 线程存在，确认音频采样率/缓冲配置是否合理

---

## 9. 附录

### 9.1 Perfetto Trace
- 文件路径：`/tmp/picme_trace_new.perfetto-trace`
- 大小：128MB
- 可用 [ui.perfetto.dev](https://ui.perfetto.dev) 打开进行火焰图分析

### 9.2 采集命令参考

```bash
# 设备状态检查
adb devices
adb shell dumpsys battery | grep temperature
adb shell ps | grep com.mamba.picme

# 实时性能数据
adb shell dumpsys cpuinfo | grep com.mamba.picme
adb shell dumpsys meminfo com.mamba.picme
adb shell dumpsys gfxinfo com.mamba.picme

# 温度读取
adb shell cat /sys/class/thermal/thermal_zone*/temp

# Perfetto 采集
adb shell "perfetto --txt -c /data/misc/perfetto-configs/perfetto_config.pbtxt \
  -o /data/misc/perfetto-traces/picme_trace_new.perfetto-trace --background"
```
