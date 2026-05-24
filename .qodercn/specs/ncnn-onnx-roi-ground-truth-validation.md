# NCNN vs ONNX ROI 检测 Ground Truth 验证方案

## Context

NCNN ROI 检测器（RetinaFace det_10g）的运行结果与 ONNX/MNN 差异很大。已尝试修复 NCNN 输出数据的 reordering 问题，但用户反馈结果仍然不对。需要一个系统化的 Ground Truth 验证方案，使用同一张静态图片对比 ONNX（作为基准）和 NCNN 的每一步输出，逐层定位差异根因。

## Problem Analysis

### 已发现的潜在差异点

1. **输入预处理差异**
   - ONNX: 使用 `Bitmap.createScaledBitmap()` (双线性插值) + Canvas draw → getPixels → CHW 归一化
   - NCNN: 手动双线性插值 letterbox resize → NCHW float → 归一化 `(x-127.5)/128.0`
   - 两者 resize 算法实现细节可能不同（边界处理、像素采样坐标）

2. **归一化参数**
   - ONNX: 动态检测模型内置归一化 (`_minusscalar0` / `_mulscalar0`)，无内置时用 `mean=127.5, std=128.0`
   - NCNN: 固定 `mean=127.5, norm=1/128.0`
   - det_10g ONNX 模型**没有**内置归一化节点，所以 ONNX 外部做归一化

3. **输出数据布局** (已尝试修复)
   - ONNX: 输出按 `y*x*anchor` 顺序排列
   - NCNN Permute+Reshape 后按 `y*anchor*x` 顺序排列
   - 已添加 reorderNcnnData() 修复，但可能仍有其他问题

4. **后处理差异**
   - ONNX: 合并 3 个尺度的 scores 和 boxes 到统一数组，然后统一解码
   - NCNN: 逐尺度提取、逐尺度解码，然后合并 NMS
   - NMS 阈值不同: ONNX=0.4, NCNN=0.3
   - 置信度阈值不同: ONNX=0.8, NCNN=0.5

5. **模型转换本身的问题**
   - onnx2ncnn 转换可能存在算子精度差异
   - NCNN 的某些算子实现与 ONNX Runtime 不完全一致

## Verification Strategy

采用**分层对比**策略，从输入到输出逐层对比 ONNX 和 NCNN 的中间结果。

### Layer 1: 输入图像预处理验证

**目标**: 确保 ONNX 和 NCNN 接收到的输入张量完全一致。

**方法**:
1. 准备一张测试图片 (如 `app/src/androidTest/assets/face.jpg`)
2. 在 ONNX `createInputTensor()` 中保存预处理后的像素值（前 10 个像素）
3. 在 NCNN `preprocess()` 中保存预处理后的像素值（前 10 个像素）
4. 对比两者是否一致

**关键检查点**:
- 归一化后的值范围是否一致（应在 [-1, 1] 附近）
- Letterbox padding 是否一致（黑色区域值应为 -1.0）
- 图像中心区域的像素值是否一致

### Layer 2: 原始模型输出验证（绕过所有后处理）

**目标**: 直接对比 ONNX 和 NCNN 模型的原始输出（未经过解码和 NMS）。

**方法**:
1. 在 ONNX `runInference()` 后，打印每个输出张量的 shape 和前 20 个值
2. 在 NCNN `detectRetinaFace()` 中，提取每个 blob 的前 20 个值
3. 对比同一位置 anchor 的 score、bbox offset、landmark 值

**关键检查点**:
- 最高置信度 anchor 的位置是否一致
- 对应 anchor 的 bbox offset 是否一致
- 如果原始输出就不一致，说明问题在模型推理层（输入或模型本身）

### Layer 3: Anchor 解码验证

**目标**: 对比解码后的检测框坐标。

**方法**:
1. 在 ONNX `parseOutputs()` 中，打印每个有效 face box 的坐标（640x640 空间）
2. 在 NCNN `processRetinaFaceOutput()` 中，打印每个有效 face box 的坐标
3. 对比排序后的 top-5 检测框

**关键检查点**:
- 检测框数量是否一致
- 检测框坐标是否接近（允许 <5px 误差）
- 置信度排序是否一致

### Layer 4: NMS 后验证

**目标**: 对比 NMS 后的最终结果。

**方法**:
1. 打印 NMS 后的 top-1 检测框（640x640 空间）
2. 对比坐标差异

### Layer 5: 坐标映射验证

**目标**: 对比映射回原图后的 ROI。

**方法**:
1. 打印最终 ROI（原图坐标空间）
2. 对比差异

## Implementation Plan

### Step 1: 创建增强版对比 Instrumentation Test

基于现有的 `RoiDetectorComparisonTest.kt`，创建 `NcnnOnnxRoiGroundTruthTest.kt`：

**文件**: `app/src/androidTest/java/com/picme/NcnnOnnxRoiGroundTruthTest.kt`

功能：
- 加载同一张静态测试图片
- 分别调用 ONNX Det10G 和 NCNN Det10G
- 收集并对比每一层的中间结果
- 输出详细的差异报告

**需要修改的检测器类**（添加诊断日志）：

1. `InsightFaceDet10GDetector.kt` (ONNX):
   - 在 `createInputTensor()` 中打印归一化后的前 20 个像素值
   - 在 `runInference()` 后打印每个输出张量的 shape 和前 20 个值
   - 在 `parseOutputs()` 中打印解码后的 top-5 face boxes
   - 在 `applyNMS()` 后打印 NMS 结果

2. `NcnnRoiDetector.kt` (Kotlin 层):
   - 在 `detectRoiLocked()` 中打印原始返回的 result 数组

3. `ncnn_face_detector.cpp` (NCNN C++ 层):
   - 在 `preprocess()` 中打印归一化后的前 20 个像素值
   - 在 `detectRetinaFace()` 中打印每个 blob 提取后的前 20 个值
   - 在 `processRetinaFaceOutput()` 中打印解码后的 top-5 face boxes
   - 在 `applyNMS()` 后打印 NMS 结果

### Step 2: 准备测试图片

将一张包含清晰人脸的测试图片放入：
`app/src/androidTest/assets/face.jpg`

图片要求：
- 分辨率适中（如 720x1280）
- 人脸清晰、无遮挡
- 单人脸（便于对比）

### Step 3: 运行测试并分析日志

```bash
# 编译并运行 Instrumentation Test
./gradlew :app:connectedDebugAndroidTest --tests "com.picme.NcnnOnnxRoiGroundTruthTest"

# 收集日志
adb logcat -d -s "PicMe:*" > ncnn_onnx_compare.log
```

### Step 4: 根据日志定位问题并修复

根据 Layer 1-5 的对比结果，定位差异首次出现的层级：

- **如果 Layer 1 就不一致**: 修复输入预处理（归一化、resize 算法）
- **如果 Layer 2 不一致**: 修复模型输入或检查模型转换问题
- **如果 Layer 3 不一致**: 修复 anchor 解码逻辑
- **如果 Layer 4 不一致**: 修复 NMS 逻辑
- **如果 Layer 5 不一致**: 修复坐标映射逻辑

### Step 5: 验证修复

修复后重新运行测试，确认差异在可接受范围内（<5px）。

## Critical Files to Modify

### 新增文件
- `app/src/androidTest/java/com/picme/NcnnOnnxRoiGroundTruthTest.kt` — 主测试类
- `app/src/androidTest/assets/face.jpg` — 测试图片

### 修改文件（仅添加诊断日志，不修改业务逻辑）
- `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/InsightFaceDet10GDetector.kt`
  - `createInputTensor()`: 添加输入像素诊断日志
  - `runInference()`: 添加原始输出诊断日志
  - `parseOutputs()`: 添加解码后 box 诊断日志

- `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/NcnnRoiDetector.kt`
  - `detectRoiLocked()`: 添加原始 result 诊断日志

- `beauty-engine/src/main/cpp/ncnn_face_detector.cpp`
  - `preprocess()`: 添加输入像素诊断日志
  - `detectRetinaFace()`: 添加 blob 提取后诊断日志
  - `processRetinaFaceOutput()`: 添加解码后 box 诊断日志

## Verification

1. 编译通过: `./gradlew :app:assembleDebug`
2. 测试运行: `./gradlew :app:connectedDebugAndroidTest --tests "com.picme.NcnnOnnxRoiGroundTruthTest"`
3. 日志分析: 检查 `adb logcat` 输出，确认每一层的 ONNX/NCNN 数值差异
4. 修复后重跑: 确认最终 ROI 差异 < 5px
