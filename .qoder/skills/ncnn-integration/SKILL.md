---
name: ncnn-integration
description: |
  NCNN 推理引擎接入专家。预防 AI 在接入 NCNN 模型时重复犯已验证过的错误，涵盖模型转换、Vulkan GPU 配置、param 文件修复与线程安全。
version: 1.0.0
created: 2026-05-25
updated: 2026-05-25
maintainer: [RD] 全栈工程师
tags:
  - ncnn
  - vulkan
  - gpu
  - inference
  - model
  - android
  - threading
---

# NCNN 集成专家 (NCNN Integration Expert)

> **定位**：预防 AI 在接入 NCNN 模型时重复犯已验证过的错误。
> **触发时机**：接入任何新 NCNN 模型、配置 Vulkan GPU、修复 param 文件、处理线程安全问题时。

---

## 接入前必须回答的 5 个问题

| # | 问题 | 常见错误 | 验证方式 |
|---|------|---------|---------|
| 1 | **模型来源** | 直接使用未经转换的 ONNX/PyTorch 模型 | 必须通过 `onnx2ncnn` 或 PyTorch→ONNX→NCNN 链路转换 |
| 2 | **Vulkan 设备索引** | 未显式设置 GPU 设备索引导致初始化失败 | 调用 `ncnn::get_gpu_count()` 并显式 `set_vulkan_compute_device()` |
| 3 | **Param 文件格式** | 层类型名错误、维度不匹配导致加载失败 | 对照 NCNN 源码 `layer/` 目录验证层类型名 |
| 4 | **线程安全** | `Net::load_model` 非线程安全导致崩溃 | 确保模型加载在单线程中串行执行 |
| 5 | **归一化参数** | 预处理均值/方差与训练时不一致 | 对照原始模型训练配置 |

---

## 核心接入流程

### Phase 1: 模型转换验证

```bash
# ONNX → NCNN
onnx2ncnn model.onnx model.param model.bin

# 验证输出文件
ls -la model.param model.bin
```

**检查项**：
- [ ] `.param` 文件第一行为 `7767517`（magic number）
- [ ] `.param` 文件第二行格式：`layer_count blob_count`
- [ ] 无 `Unsupported` 或 `unknown` 层类型

### Phase 2: Vulkan GPU 配置

```cpp
// 必须显式设置设备索引
ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(0);
ncnn::Net net;
net.opt.use_vulkan_compute = true;
net.set_vulkan_device(vkdev);  // 关键！不设置可能初始化失败
```

**常见陷阱**：
- **Adreno 620 性能基准**：RetinaFace det_10g 约 15-25ms/帧
- **必须检查 GPU 可用性**：
  ```cpp
  if (ncnn::get_gpu_count() == 0) {
      // 降级 CPU 路径
      net.opt.use_vulkan_compute = false;
  }
  ```

### Phase 3: 线程安全加载

```cpp
// ❌ 错误：多线程同时加载
std::thread t1([&]{ net1.load_model("model.bin"); });
std::thread t2([&]{ net2.load_model("model.bin"); });

// ✅ 正确：串行加载
net1.load_param("model.param");
net1.load_model("model.bin");
net2.load_param("model.param");
net2.load_model("model.bin");
```

### Phase 4: 输入预处理

```cpp
ncnn::Mat in = ncnn::Mat::from_android_bitmap(bitmap, ncnn::Mat::PIXEL_RGB);

// 归一化（根据模型训练配置调整）
const float mean_vals[3] = {127.5f, 127.5f, 127.5f};
const float norm_vals[3] = {1.0f/128.0f, 1.0f/128.0f, 1.0f/128.0f};
in.substract_mean_normalize(mean_vals, norm_vals);
```

---

## 常见错误模式清单

### ❌ 错误模式 A：Param 文件层类型错误

```
# 错误 param 文件
Convolution  // ❌ 应为 Conv
```

**修复**：对照 NCNN 源码 `src/layer/` 目录中的类名：
- `Convolution` → `Conv`
- `BatchNormalization` → `BatchNorm`
- `Scale` → `Scale`（保持不变）

### ❌ 错误模式 B：未初始化 Vulkan

```cpp
// ❌ 错误：直接创建 Net 并设置 use_vulkan_compute
ncnn::Net net;
net.opt.use_vulkan_compute = true;  // 可能崩溃，未初始化 Vulkan

// ✅ 正确：先初始化 Vulkan
ncnn::create_gpu_instance();  // 在 Application 或初始化时调用
ncnn::Net net;
net.opt.use_vulkan_compute = true;
```

### ❌ 错误模式 C：版本不匹配

**症状**：`layer XXX not exists` 或加载模型时崩溃

**根因**：NCNN 库版本与模型转换时的版本不一致

**修复**：
1. 记录转换时使用的 NCNN commit hash
2. 确保 Android 项目使用的 NCNN 版本一致
3. 升级时重新转换所有模型

### ❌ 错误模式 D：Blob 名称不匹配

```cpp
// ❌ 错误：使用 ONNX 输出名
ncnn::Extractor ex = net.create_extractor();
ex.input("input", in);           // ONNX 名
ncnn::Mat out;
ex.extract("output", out);      // ONNX 名

// ✅ 正确：使用 NCNN 转换后的 blob 名
ex.input("data", in);            // NCNN blob 名
ex.extract("detection_out", out); // NCNN blob 名
```

**查看 blob 名**：查看 `.param` 文件中的 `Input` 和输出层名称。

---

## 调试诊断

### 日志启用

```cpp
// 开启 NCNN 详细日志
net.opt.lightmode = false;
setenv("NCNN_VERBOSE", "1", 1);
```

### 模型结构检查

```bash
# 查看 param 文件结构
head -20 model.param

# 检查层数是否匹配
grep -c "^[^#]" model.param
```

### 内存检查

```cpp
// 检查输入/输出 Mat 尺寸
LOGD("Input: %dx%dx%d", in.w, in.h, in.c);
ncnn::Extractor ex = net.create_extractor();
ex.input("data", in);
ncnn::Mat out;
ex.extract("output", out);
LOGD("Output: %dx%dx%d", out.w, out.h, out.c);
```

---

## 与项目现有 Skill 的联动

| 场景 | 联动 Skill |
|------|-----------|
| 模型从 ONNX 转换而来 | [onnx-model-integration](.qoder/skills/onnx-model-integration/SKILL.md) |
| GPU 渲染相关问题 | [av-gl-expert](.qoder/skills/av-gl-expert/SKILL.md) |
| 人脸关键点对齐问题 | [mnn-landmark-diagnosis](.qoder/skills/mnn-landmark-diagnosis/SKILL.md) |
| 编译错误 | [error-healer](.qoder/skills/error-healer/SKILL.md) |

---

## 审查清单（CR 必须检查）

- [ ] 模型是否通过标准工具转换（onnx2ncnn / PyTorch→ONNX→NCNN）？
- [ ] Vulkan 设备索引是否显式设置？
- [ ] `load_param` / `load_model` 是否在单线程中串行执行？
- [ ] 预处理均值/方差是否与训练配置一致？
- [ ] 输入/输出 blob 名称是否与 `.param` 文件一致？
- [ ] 是否有 GPU 不可用时的 CPU 降级路径？
- [ ] NCNN 库版本是否与模型转换版本一致？
- [ ] 是否启用了适当的日志以便调试？

---

## 参考文档

- [NCNN GitHub](https://github.com/Tencent/ncnn)
- [NCNN 参数文档](https://github.com/Tencent/ncnn/wiki/param-and-model-file-structure)
- [NCNN Vulkan 使用](https://github.com/Tencent/ncnn/wiki/vulkan-notes)
- `docs/03-TECHNICAL-SPECS/MNN_LANDMARK_DIAGNOSIS.md` — 多引擎对齐诊断

## 相关文件

- [TEMPLATE.md](.qoder/skills/TEMPLATE.md) — Skill 编写模版
## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-25 | 初始版本 |
