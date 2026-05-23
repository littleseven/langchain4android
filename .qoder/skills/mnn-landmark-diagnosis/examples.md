# MNN Landmark 诊断示例

## 示例 1: 维度类型不匹配导致的完全错误输出

### 症状

MNN 输出稳定但关键点位置完全错误（例如：所有点集中在图像左上角）。

### 诊断日志

```
// C++ 层输入诊断
[Diag] Input tensor dimension type: 1 (CAFFE=0, TENSORFLOW=1, CAFFE_C4=2)
[Diag] MNN First 10 pixels normalized: [0.00,0.00,0.00] [0.00,0.00,0.00] ...

// Kotlin 层输出诊断
[Diag] MNN raw output first 10 points: (-0.165,0.762) (-0.689,-0.416) ...
[Diag] ONNX raw output first 10 points: (-0.682,0.653) (-0.521,-0.697) ...
```

### 分析

1. 输入维度类型为 `TENSORFLOW` (1)，但代码硬编码使用 `CAFFE` (0)
2. 数据按 NCHW 填充，但模型期望 NHWC
3. `copyFromHostTensor` 重排后，RGB 通道完全错位
4. 模型接收到"全黑"或"通道错位"的输入

### 修复

```cpp
// 修复前（错误）
MNN::Tensor tmpInput(inputTensor_, MNN::Tensor::DimensionType::CAFFE);

// 修复后（正确）
MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
MNN::Tensor tmpInput(inputTensor_, inputDimType);
bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);

// 根据维度类型填充
for (int i = 0; i < totalPixels; i++) {
    for (int c = 0; c < 3; c++) {
        if (isNCHW) {
            inputData[c * totalPixels + i] = normalizedVal;
        } else {
            inputData[i * 3 + c] = normalizedVal;
        }
    }
}
```

### 验证结果

```
[Diag] MNN vs ONNX comparison: avgDiff=0.0001, maxDiff=0.0007
```

---

## 示例 2: 归一化参数不匹配导致的系统性偏差

### 症状

MNN 输出接近正确但所有点有系统性偏移（例如：所有点向右下方偏移 5-10 像素）。

### 诊断日志

```
// MNN 归一化参数（错误）
normMean = 127.5, normStd = 128.0  // 外部归一化

// ONNX 归一化参数（正确）
inputMean = 0, inputStd = 1  // 内置归一化，直接传递原始像素
```

### 分析

模型包含内置归一化节点（`_minusscalar0`, `_mulscalar0`），但外部又做了一次归一化，导致输入被"双重归一化"。

### 修复

```cpp
// 检测内置归一化
std::ifstream modelCheck(modelPath.c_str(), std::ios::binary);
std::string modelContent(...);
hasBuiltInNormalization_ = (modelContent.find("_minusscalar0") != std::string::npos) &&
                           (modelContent.find("_mulscalar0") != std::string::npos);

// 根据检测结果选择归一化参数
float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;
```

---

## 示例 3: INPUT_SIZE 不一致导致的缩放错误

### 症状

MNN 检测到的人脸关键点整体缩放比例错误（例如：关键点区域比实际人脸小一圈）。

### 诊断日志

```
// MNN
INPUT_SIZE = 128

// ONNX
INPUT_SIZE = 192
```

### 分析

MNN 模型输入尺寸与 ONNX 不一致，导致 Kotlin 层 crop 和 C++ 层推理使用的尺寸不同。

### 修复

```kotlin
// MnnLandmarkDetector.kt
companion object {
    private const val INPUT_SIZE = 192  // 对齐 InsightFace2D106Detector
}
```

---

## 示例 4: 完整的诊断流程示例

### 场景

新接入 MNN 引擎，Landmark 检测结果与 ONNX 不一致。

### 执行步骤

```bash
# Step 1: 添加诊断日志，编译安装
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# Step 2: 启动应用，收集日志
adb logcat -c
adb shell am start -n com.picme/.MainActivity

# Step 3: 等待 10 秒后收集日志
sleep 10
adb logcat -d > /tmp/picme_log.txt

# Step 4: 分析日志
grep "dimension type" /tmp/picme_log.txt
grep "First 10 pixels" /tmp/picme_log.txt
grep "raw output" /tmp/picme_log.txt
```

### 决策树

```
输入维度类型是否正确？
├── 否 → 修复维度类型动态获取
└── 是 → 输入像素值是否与 ONNX 一致？
    ├── 否 → 修复归一化参数
    └── 是 → 输出是否与 ONNX 一致？
        ├── 否 → 修复输出维度类型
        └── 是 → 检查坐标变换/点序映射
```

---

## 示例 5: auto-dev-loop 集成验证

### 执行

```bash
./scripts/auto-dev-loop.sh
```

### 预期输出

```
=== Build Report ===
Status: SUCCESS
Tasks: 96 executed

=== Installation ===
Device: 小米15 (codename: aurora)
Status: Installed

=== Screenshot Analysis ===
screen_startup.png: OK
screen_camera_preview.png: OK
landmark_overlay.png: OK (106 points detected)

=== Log Analysis ===
MNN vs ONNX avgDiff: 0.0001
MNN vs ONNX maxDiff: 0.0007
Frame stability: < 0.01

=== Final Report ===
Status: PASS
MNN Landmark detection aligned with ONNX baseline.
```
