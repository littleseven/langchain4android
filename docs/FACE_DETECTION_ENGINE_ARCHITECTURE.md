# 人脸检测引擎技术架构（2026-05）

## 1. 概述

PicMe 项目采用**双引擎人脸检测架构**,支持 InsightFace 2D106 和 MediaPipe Face Mesh 两种检测方案,通过统一的适配层接口实现无缝切换与自动容灾。

### 1.1 设计目标

- **性能优先**: 默认使用 InsightFace + NNAPI GPU/NPU 加速,预期性能提升 3-5x
- **可用性保障**: InsightFace 漏检或初始化失败时自动回退到 MediaPipe
- **统一接口**: 通过 `FaceLandmarkAdapter` 抽象层屏蔽底层引擎差异
- **隐私保护**: 100% 本地推理,严禁云端依赖

---

## 2. 引擎对比

| 特性 | InsightFace 2D106 | MediaPipe Face Mesh |
|------|-------------------|---------------------|
| **定位** | 默认首选引擎 | 备选引擎 |
| **模型类型** | ONNX (RetinaFace Det10G + 2d106det) | TFLite (Face Landmarker) |
| **关键点数量** | 106 点 (标准 Face++ 规范) | 468 点 → 映射到 106 点 |
| **硬件加速** | NNAPI (GPU/DSP/NPU) | CPU (异步分析流) |
| **检测策略** | 两阶段: ROI 检测 → 关键点提取 | 单阶段: 直接输出 468 点 |
| **预期耗时** | Det10G: 0.5-1s, 2D106: 0.2-0.4s | 整体: 0.3-0.6s |
| **适用场景** | 高性能需求、精细美型 | 兼容性兜底、复杂光照 |
| **初始化依赖** | ONNX Runtime Android | MediaPipe Tasks Vision |

---

## 3. 架构设计

### 3.1 核心组件

```
app/src/main/java/com/picme/features/camera/facedetect/
├── FaceDetectorManager.kt              # 统一调度管理器
├── RoiDetector.kt                      # ROI 检测器接口
│   ├── MediaPipeRoiDetector.kt         # MediaPipe ROI 实现
│   └── Det10GRoiDetector.kt            # InsightFace Det10G 实现
├── LandmarkDetector.kt                 # 关键点检测器接口
│   ├── MediaPipeLandmarkDetector.kt    # MediaPipe 468 点检测
│   └── InsightFaceLandmarkDetector.kt  # InsightFace 2D106 检测
├── adapter/
│   ├── FaceLandmarkAdapter.kt          # 适配器接口
│   ├── FaceLandmarkAdapterRegistry.kt  # 适配器注册表
│   ├── MediaPipe468Adapter.kt          # MediaPipe 468→106 映射
│   └── InsightFaceAdapter.kt           # InsightFace 106 点适配
├── DetectionPipelineConfig.kt          # 检测流水线配置
└── DetectionPipelineFactory.kt         # 流水线工厂
```

### 3.2 数据流

```
CameraX ImageProxy
    ↓
FaceDetectorManager.detect()
    ↓
[InsightFace 路径]
    ├─ Det10GRoiDetector.detectRoi()      → RectF (ROI 区域)
    └─ InsightFace2D106Detector.detectInRoi() → FloatArray (106 点)
        └─ InsightFaceAdapter.adapt()     → GpuPixelLandmarks

[MediaPipe 路径]
    └─ MediaPipeFaceDetector.detect()     → Result (468 点)
        └─ MediaPipe468Adapter.adapt()    → GpuPixelLandmarks (106 点)
    ↓
Face106ToWarpParams.convert()             → FaceWarpParams
    ↓
BeautyRenderer.setFaceWarpParams()        → OpenGL Shader Uniforms
```

---

## 4. InsightFace 引擎详解

### 4.1 两阶段检测流程

#### Phase 1: Det10G ROI 检测

**模型**: `det_10g.onnx` (RetinaFace 简化版)

**输入**:
- 尺寸: 动态 (保持宽高比,最长边 ≤ 640px)
- 格式: BGR, HWC
- 归一化: `mean=[127.5, 127.5, 127.5]`, `std=[128.0, 128.0, 128.0]`

**输出**:
- `bbox_pred`: 边界框预测 (4 个值: x1, y1, x2, y2)
- `cls_pred`: 置信度分数 (1 个值: 0-1)
- `landmark_pred`: 5 个关键点 (10 个值: 5×(x,y))

**解码公式**:
```kotlin
// distance2bbox 解码 (RetinaFace 标准)
val x1 = center_x - distance[0] * stride
val y1 = center_y - distance[1] * stride
val x2 = center_x + distance[2] * stride
val y2 = center_y + distance[3] * stride
```

**过滤策略**:
- 置信度阈值: `score ≥ 0.5`
- NMS (非极大值抑制): IoU 阈值 0.4
- 误检处理: 人脸框面积 < 图像面积 1% 则丢弃

#### Phase 2: 2D106 关键点提取

**模型**: `2d106det.onnx`

**输入**:
- 尺寸: 固定 192×192
- 来源: 从 Det10G ROI 裁剪并 resize
- 格式: BGR, CHW
- 归一化: `mean=[127.5, 127.5, 127.5]`, `std=[128.0, 128.0, 128.0]`

**输出**:
- `landmarks`: 106 个点 (212 个值: 106×(x,y)),归一化坐标 [0,1]

**坐标转换**:
```kotlin
// 从 192×192 归一化坐标 → 原始图像像素坐标
val pixelX = normalizedX * roiWidth + roiLeft
val pixelY = normalizedY * roiHeight + roiTop
```

### 4.2 NNAPI GPU 加速配置

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

**兼容性**:
- Android 8.1+ (API 27+)
- 设备需支持 NNAPI Driver (GPU/DSP/NPU)
- 不支持时自动降级到 CPU

---

## 5. MediaPipe 引擎详解

### 5.1 单阶段检测流程

**模型**: `face_landmarker.task` (TFLite)

**输入**:
- 尺寸: 动态 (保持宽高比)
- 格式: RGB, HWC
- 归一化: 自动 (MediaPipe 内部处理)

**输出**:
- `face_landmarks`: 468 个 3D 关键点 (x, y, z)
- `face_blendshapes`: 52 个表情系数 (可选)
- `transformation_matrix`: 4×4 变换矩阵 (可选)

### 5.2 468→106 点映射策略

**映射文件**: `docs/face-detection/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md`

**映射原则**:
1. **语义对齐**: 确保 MediaPipe 索引点与火山引擎 106 点语义一致
2. **左右对称**: 正确处理镜像翻转 (前置摄像头)
3. **缺失插值**: MediaPipe 无直接对应点时,通过邻近点线性插值

**关键映射示例**:
```kotlin
// 左眼中心 (106 点索引 52)
val leftEyeCenter = mediaPipePoints[468]  // MediaPipe 索引 468

// 鼻尖 (106 点索引 54)
val noseTip = mediaPipePoints[4]  // MediaPipe 索引 4

// 嘴角左 (106 点索引 76)
val mouthLeft = mediaPipePoints[61]  // MediaPipe 索引 61
```

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

---

## 6. 容灾与回退机制

### 6.1 自动回退触发条件

**InsightFace → MediaPipe**:
1. Det10G 连续 3 帧未检测到人脸
2. 2D106 模型加载失败 (ONNX Runtime 异常)
3. NNAPI 初始化失败且 CPU 推理超时 (>3s)
4. 关键点数量异常 (<106 点或 >111 点)

**回退流程**:
```
InsightFace 检测失败
    ↓
FaceDetectorManager 捕获异常
    ↓
切换 detectionSource = MEDIAPIPE
    ↓
调用 MediaPipeFaceDetector.detect()
    ↓
记录日志: "Fallback to MediaPipe due to [reason]"
    ↓
冷却窗口: 30s 内不再尝试 InsightFace
```

### 6.2 冷却恢复机制

**冷却窗口**: 30 秒

**恢复条件**:
1. 冷却时间到期
2. 用户手动切换引擎 (设置页)
3. 应用重启

**状态持久化**:
```kotlin
// DataStore 保存最后成功的引擎
preferences.edit {
    putBoolean("insight_face_available", lastSuccess == INSIGHTFACE)
    putLong("last_fallback_timestamp", System.currentTimeMillis())
}
```

---

## 7. 性能优化策略

### 7.1 智能帧跳过

**策略**:
- 默认每 3 帧检测一次 (10fps @ 30fps 预览)
- 无人脸时降低到每 5 帧 (6fps)
- 检测到快速运动时提高到每 2 帧 (15fps)

**运动检测**:
```kotlin
val motionScore = calculateMotionScore(currentFrame, previousFrame)
detectionInterval = when {
    motionScore > HIGH_MOTION_THRESHOLD -> 2
    motionScore < LOW_MOTION_THRESHOLD -> 5
    else -> 3
}
```

### 7.2 Bitmap 缓存

**问题**: ImageProxy → Bitmap 转换耗时 (~50ms)

**解决方案**:
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

### 7.3 EMA 关键点平滑 (已撤销)

**原方案**: 指数移动平均平滑,避免妆容"甩飞"

**撤销原因**: 引入延迟,影响跟手性

**替代方案**: 
- 提高检测频率 (从 10fps → 15fps)
- 运动自适应平滑 (仅在低速时启用)
- 预测性跟踪 (卡尔曼滤波,待实现)

---

## 8. 调试与可观测性

### 8.1 调试浮层指标

**显示内容**:
- FPS (帧率)
- 检测耗时 (Det10G + 2D106 总耗时)
- 检测来源 (`INSIGHTFACE` / `MEDIAPIPE` / `NONE`)
- 请求引擎 (设置页配置)
- ROI 矩形框 (橙色虚线)
- 关键点可视化 (蓝色点位)

### 8.2 结构化日志

**日志标签**: `PicMe:FaceDetect`

**关键日志**:
```kotlin
Logger.i(TAG, "InsightFace Det10G initialized: mean=$inputMean, std=$inputStd")
Logger.i(TAG, "NNAPI execution provider enabled")
Logger.w(TAG, "NNAPI not available, falling back to CPU", e)
Logger.i(TAG, "Detection result: source=$detectionSource, points=${landmarks.size}")
Logger.w(TAG, "Fallback to MediaPipe due to consecutive failures")
```

---

## 9. 未来演进方向

### 9.1 Phase 2 (4-8 周)

- **卡尔曼滤波跟踪**: 预测下一帧关键点位置,减少检测频率
- **多帧融合**: 结合历史帧关键点,提升稳定性
- **光线自适应**: 根据环境光强度动态调整检测参数

### 9.2 Phase 3 (8-16 周)

- **ML Kit Selfie Segmentation**: 人像分割 Mask,支撑背景虚化
- **MediaPipe Pose**: 人体姿态估计,支撑身材管理 (丰胸/长腿)
- **3D 人脸重建**: 基于 106 点生成 3D Mesh,支撑更精细的美型

---

## 10. 相关文档索引

- **产品需求**: `PRODUCT.md §3.1`
- **交互规范**: `docs/FEATURES.md §1.3.6`
- **映射策略**: `docs/face-detection/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md`
- **坐标系统**: `docs/COORDINATE_SYSTEM_STANDARD.md`
- **模块规范**: `app/src/main/java/com/picme/features/camera/AGENTS.md`

---

**文档版本**: v1.0 (2026-05-05)  
**维护者**: RD Team  
**审核状态**: ✅ 已接受
