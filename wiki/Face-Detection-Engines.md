# 人脸检测双引擎架构

PicMe 采用 **InsightFace 2D106 (默认首选)** + **MediaPipe Face Mesh 468→106 (备选)** 的双引擎架构,通过统一的适配层实现无缝切换与自动容灾。

## 🎯 设计目标

- **性能优先**: InsightFace + NNAPI GPU/NPU 加速,预期性能提升 3-5x
- **可用性保障**: InsightFace 漏检时自动回退到 MediaPipe
- **统一接口**: `FaceLandmarkAdapter` 抽象层屏蔽底层差异
- **隐私保护**: 100% 本地推理,严禁云端依赖

## 📊 引擎对比

| 特性 | InsightFace 2D106 | MediaPipe Face Mesh |
|------|-------------------|---------------------|
| **定位** | 默认首选引擎 | 备选引擎 |
| **模型类型** | ONNX (RetinaFace Det10G + 2d106det) | TFLite (Face Landmarker) |
| **关键点数量** | 106 点 (标准 Face++ 规范) | 468 点 → 映射到 106 点 |
| **硬件加速** | NNAPI (GPU/DSP/NPU) | CPU (异步分析流) |
| **检测策略** | 两阶段: ROI 检测 → 关键点提取 | 单阶段: 直接输出 468 点 |
| **预期耗时** | Det10G: 0.5-1s, 2D106: 0.2-0.4s | 整体: 0.3-0.6s |
| **适用场景** | 高性能需求、精细美型 | 兼容性兜底、复杂光照 |

## 🔧 InsightFace 两阶段检测

### Phase 1: Det10G ROI 检测

**模型**: `det_10g.onnx` (RetinaFace 简化版)

**输入**:
- 尺寸: 动态 (保持宽高比,最长边 ≤ 640px)
- 格式: BGR, HWC
- 归一化: `mean=[127.5, 127.5, 127.5]`, `std=[128.0, 128.0, 128.0]`

**输出**:
- `bbox_pred`: 边界框预测 (x1, y1, x2, y2)
- `cls_pred`: 置信度分数 (0-1)
- `landmark_pred`: 5 个关键点 (10 个值)

**过滤策略**:
- 置信度阈值: `score ≥ 0.5`
- NMS (非极大值抑制): IoU 阈值 0.4
- 误检处理: 人脸框面积 < 图像面积 1% 则丢弃

### Phase 2: 2D106 关键点提取

**模型**: `2d106det.onnx`

**输入**:
- 尺寸: 固定 192×192
- 来源: 从 Det10G ROI 裁剪并 resize
- 格式: BGR, CHW

**输出**:
- `landmarks`: 106 个点 (212 个值),归一化坐标 [0,1]

### NNAPI GPU 加速配置

```kotlin
val sessionOptions = OrtSession.SessionOptions()
try {
    sessionOptions.addNnapi()  // 启用 Android NNAPI
    Logger.i(TAG, "NNAPI execution provider enabled")
} catch (e: Exception) {
    Logger.w(TAG, "NNAPI not available, falling back to CPU", e)
}
```

**预期性能提升**:
- Det10G: 2.5s (CPU) → 0.5-1s (NNAPI)
- 2D106: 1.0s (CPU) → 0.2-0.4s (NNAPI)

## 🔄 MediaPipe 468→106 映射

**映射文件**: `docs/face-detection/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md`

**映射原则**:
1. **语义对齐**: MediaPipe 索引点与火山引擎 106 点语义一致
2. **左右对称**: 正确处理镜像翻转 (前置摄像头)
3. **缺失插值**: 无直接对应点时,通过邻近点线性插值

**坐标系转换**:
```kotlin
// MediaPipe NDC [-1, 1] → 归一化 [0, 1]
val normalizedX = (ndcX + 1.0f) / 2.0f
val normalizedY = (ndcY + 1.0f) / 2.0f

// 前置摄像头镜像翻转
if (isFrontCamera) {
    normalizedX = 1.0f - normalizedX
}
```

## 🛡️ 容灾与回退机制

### 自动回退触发条件

**InsightFace → MediaPipe**:
1. Det10G 连续 3 帧未检测到人脸
2. 2D106 模型加载失败 (ONNX Runtime 异常)
3. NNAPI 初始化失败且 CPU 推理超时 (>3s)
4. 关键点数量异常 (<106 点或 >111 点)

### 冷却恢复机制

**冷却窗口**: 30 秒

**恢复条件**:
1. 冷却时间到期
2. 用户手动切换引擎 (设置页)
3. 应用重启

## 📈 性能优化策略

### 智能帧跳过

- 默认每 3 帧检测一次 (10fps @ 30fps 预览)
- 无人脸时降低到每 5 帧 (6fps)
- 检测到快速运动时提高到每 2 帧 (15fps)

### Bitmap 缓存

避免重复 ImageProxy → Bitmap 转换 (~50ms):

```kotlin
private var cachedBitmap: Bitmap? = null
private var lastTimestamp: Long = 0

fun getOrConvertBitmap(imageProxy: ImageProxy): Bitmap {
    if (imageProxy.timestamp == lastTimestamp && cachedBitmap != null) {
        return cachedBitmap!!  // 复用缓存
    }
    cachedBitmap = imageProxy.toBitmap()
    lastTimestamp = imageProxy.timestamp
    return cachedBitmap!!
}
```

## 📚 相关文档

- [完整技术架构](../docs/FACE_DETECTION_ENGINE_ARCHITECTURE.md)
- [468→106 映射策略](../docs/face-detection/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md)
- [坐标系统标准](Coordinate-System)

---

**最后更新**: 2026-05-05  
**维护者**: PicMe RD Team
