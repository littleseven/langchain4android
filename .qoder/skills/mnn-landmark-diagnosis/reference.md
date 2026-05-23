# MNN Landmark 诊断深度参考

## 1. MNN 张量维度类型详解

### 1.1 DimensionType 枚举

```cpp
namespace MNN {
enum DimensionType {
    CAFFE = 0,      // NCHW - Channel First
    TENSORFLOW = 1, // NHWC - Channel Last
    CAFFE_C4 = 2    // NCHW4 - Channel aligned to 4
};
}
```

### 1.2 从 ONNX 转换后的维度类型变化

ONNX 原生使用 NCHW 布局，但 MNN 转换工具可能根据后端优化将维度类型改为 NHWC（特别是 Vulkan GPU 后端）。

**验证方法**：
```cpp
LOGD("Input dim type: %d", (int)inputTensor_->getDimensionType());
LOGD("Output dim type: %d", (int)outputTensor_->getDimensionType());
```

### 1.3 copyFromHostTensor 的行为

当 host 张量与 device 张量维度类型不同时，`copyFromHostTensor` 会自动进行维度转换：

```cpp
// 如果 inputTensor_ 是 NHWC，tmpInput 是 NCHW
// copyFromHostTensor 会按 [N,C,H,W] → [N,H,W,C] 重排数据
inputTensor_->copyFromHostTensor(&tmpInput);
```

**这正是导致数据错位的根本原因**。

---

## 2. 内置归一化节点检测

### 2.1 InsightFace 2d106det 模型结构

ONNX 模型包含以下归一化节点：
- `_minusscalar0`: 减去均值 (mean=127.5 或 0)
- `_mulscalar0`: 乘以标准差倒数 (1/128 或 1/255)

### 2.2 检测方法

```cpp
bool hasBuiltInNormalization(const std::string& modelPath) {
    std::ifstream file(modelPath.c_str(), std::ios::binary);
    if (!file.is_open()) return false;
    
    std::string content((std::istreambuf_iterator<char>(file)),
                         std::istreambuf_iterator<char>());
    file.close();
    
    return (content.find("_minusscalar0") != std::string::npos) &&
           (content.find("_mulscalar0") != std::string::npos);
}
```

### 2.3 归一化策略矩阵

| 模型 | 内置归一化 | 外部归一化 |
|------|-----------|-----------|
| 2d106det.onnx | 是 (mean=0, std=1) | 直接传递 0-255 |
| 2d106det.mnn | 是 (mean=0, std=1) | 直接传递 0-255 |
| det_10g.onnx | 否 | (x-127.5)/128.0 |
| det_10g.mnn | 否 | (x-127.5)/128.0 |

---

## 3. 并行对比测试实现

### 3.1 Kotlin 层对比代码

```kotlin
// FaceDetectorManager.kt

private fun compareWithOnnx(
    bitmap: Bitmap,
    lensFacing: Int,
    roiResult: RectF?,
    mnnResult: FloatArray
) {
    try {
        // 懒加载 ONNX 检测器
        if (insightFaceDetector == null) {
            insightFaceDetector = InsightFace2D106Detector(appContext)
        }
        
        val onnxResult = insightFaceDetector?.detect(bitmap, lensFacing, roiResult)
        
        if (onnxResult != null && onnxResult.size == mnnResult.size) {
            var maxDiff = 0f
            var totalDiff = 0f
            
            for (i in mnnResult.indices) {
                val diff = kotlin.math.abs(mnnResult[i] - onnxResult[i])
                totalDiff += diff
                if (diff > maxDiff) maxDiff = diff
            }
            
            val avgDiff = totalDiff / mnnResult.size
            Log.i(TAG, "[Diag] MNN vs ONNX: avgDiff=${avgDiff}, maxDiff=${maxDiff}")
            
            // 输出前 10 点对比
            val sb = StringBuilder("[Diag] First 10 points: ")
            for (i in 0 until 10) {
                val dx = kotlin.math.abs(mnnResult[i*2] - onnxResult[i*2])
                val dy = kotlin.math.abs(mnnResult[i*2+1] - onnxResult[i*2+1])
                sb.append("P$i: diff($dx, $dy) ")
            }
            Log.i(TAG, sb.toString())
        }
    } catch (e: Exception) {
        Log.e(TAG, "Comparison failed", e)
    }
}
```

### 3.2 C++ 层输入对比

在 `detect()` 方法中输出前 10 个像素的归一化值：

```cpp
// 在填充 inputData 后
char log[512];
snprintf(log, sizeof(log), "[Diag] First 10 pixels: ");
for (int i = 0; i < 10; i++) {
    char buf[64];
    if (isNCHW) {
        snprintf(buf, sizeof(buf), "[%.2f,%.2f,%.2f] ",
                 inputData[i], inputData[totalPixels+i], inputData[totalPixels*2+i]);
    } else {
        snprintf(buf, sizeof(buf), "[%.2f,%.2f,%.2f] ",
                 inputData[i*3], inputData[i*3+1], inputData[i*3+2]);
    }
    strncat(log, buf, sizeof(log) - strlen(log) - 1);
}
LOGD("%s", log);
```

---

## 4. 性能优化建议

### 4.1 推理耗时基准

| 后端 | 首次初始化 | 单次推理 | 备注 |
|------|-----------|---------|------|
| Vulkan GPU | ~6000ms | ~900ms | 含模型加载 |
| CPU (4线程) | ~2000ms | ~1500ms | 待验证 |
| ONNX NNAPI | ~3000ms | ~800ms | 基准 |

### 4.2 优化方向

1. **模型预热**：首次推理后保持 session 活跃
2. **输入复用**：避免每帧创建新的 Bitmap
3. **异步推理**：ROI 和 Landmark 并行执行
4. **精度降级**：尝试 FP16 推理（MNN 支持）

---

## 5. 相关 ADR 与规范

- [ADR-001] Beauty Engine 架构设计
- [COORDINATE_SYSTEM_STANDARD] 坐标系管理规范
- [MNN_ROI_DIAGNOSIS] MNN ROI 检测路径诊断文档（同系列）
