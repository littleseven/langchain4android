# 人脸检测引擎技术架构（2026-06）

## 1. 概述

PicMe 当前采用三引擎人脸检测架构：`MEDIAPIPE`、`NCNN`、`MNN`。

**零拷贝优化**（2026-06 新增）：两大零拷贝检测路径已落地——MediaPipe Image 路径（CameraX `ImageProxy` → `MPImage`，跳过 YUV→ARGB 转换，节省约 5ms）和 NCNN NV21 路径（`DirectByteBuffer` → C++ 层一体式预处理+推理，跳过 Bitmap→RGB 转换）。

**并发优化**（2026-06）：MNN ROI/Landmark 检测器初始化从阻塞 `synchronized` 切换为非阻塞 `AtomicBoolean` CAS 模式，重型模型加载从 ResourceManager 回调中延迟到下一次 detect 调用，避免阻塞渲染线程。

架构目标：

- 统一输出 106 点（归一化坐标），供大美丽渲染链路直接消费
- 通过可配置流水线组合 ROI 与 Landmark 检测器，便于调优与回归
- 将检测引擎实现下沉到 `beauty-engine`，App 层只依赖 `api/facedetect` 契约
- 最小化 CPU-GPU 数据拷贝，优先使用零拷贝路径（NV21 DirectByteBuffer / CameraX ImageProxy）

## 2. 代码落点（当前实现）

### 2.1 对外契约层

`beauty-engine/src/main/java/com/picme/beauty/api/facedetect/`

- `FaceDetector.kt`：统一检测接口（含 `detect(bitmap)` 和 `detectFromImage(image)` 零拷贝重载）
- `FaceDetectorFactory.kt`：创建 `FaceDetectorManager`
- `FaceDetectionResult.kt`：返回 `landmarks106 + detectionSource + roiRect`
- `EngineType.kt`：`MEDIAPIPE` / `NCNN` / `MNN`（已移除 `INSIGHTFACE`，改用 `MNN`/`NCNN` 枚举值）
- `DetectionPipelineConfig.kt`：ROI 与 Landmark 组合配置
- `InferenceBackendType.kt`：推理后端类型枚举（CPU / Vulkan GPU / TFLite GPU）

### 2.2 内部实现层

`beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/`

- `FaceDetectorManager.kt`：三引擎调度核心，含 `detectFromImage()` 零拷贝入口和 `detectRoiFromNv21()` 路由（MNN/NCNN 双后端）
- `DetectionPipelineFactory.kt`：创建 ROI/Landmark 检测器
- `MediaPipeFaceDetector.kt`：MediaPipe 检测入口（预览 VIDEO + 静态图 IMAGE；新增 `detect(mediaImage: Image)` 零拷贝重载）
- `MediaPipeLandmarkDetector.kt`：MediaPipe Landmark 检测（新增 `detectLandmarks(mediaImage: Image)` 零拷贝重载）
- `MnnRoiDetector.kt`：MNN ROI 检测（RetinaFace det_10g），`AtomicBoolean` CAS 非阻塞初始化
- `MnnLandmarkDetector.kt`：MNN Landmark 检测（2D106），`AtomicBoolean` CAS 非阻塞初始化
- `NcnnRoiDetector.kt`：NCNN ROI 检测（新增 `detectRoiFromYuv()` NV21 零拷贝方法）
- `NcnnLandmarkDetector.kt`：NCNN Landmark 检测（2D106）
- `mnn/MnnFaceDetector.kt`：MNN JNI 桥接
- `ncnn/NcnnFaceDetector.kt`：NCNN JNI 桥接（新增 `detectRetinaFaceFromNv21()` 原生方法）
- `adapter/MediaPipe468Adapter.kt`：468 -> 106 映射
- `adapter/MnnLandmarkAdapter.kt`：MNN 原生 106 -> 统一 106 重排
- `adapter/NcnnLandmarkAdapter.kt`：NCNN 原生 106 -> 统一 106 重排
- `Face106ToWarpParams.kt`：106 -> `FaceWarpParams`

### 2.3 C++ 原生层（零拷贝检测）

`beauty-engine/src/main/cpp/`

- `ncnn_face_detector.cpp/h`：NCNN RetinaFace 检测器（新增 `preprocessFromNv21()` NV21→RGB 一体式预处理 + `detectRetinaFaceFromNv21()` 完整检测管线）
- `ncnn_jni_bridge.cpp`：JNI 桥接（新增 `nativeDetectRetinaFaceFromNv21`，接收 `DirectByteBuffer` 直接推理，返回最佳人脸框+10 个关键点）

### 2.4 App 集成层

- `app/src/main/java/com/picme/PicMeApplication.kt`
  - 启动时执行 `FaceLandmarkAdapterRegistry.initDefaults()`
- `app/src/main/java/com/picme/features/camera/CameraRuntimeState.kt`
  - 监听用户设置，将 ROI/Landmark 检测器类型转为 `DetectionPipelineConfig`
- `app/src/main/java/com/picme/features/camera/CameraFrameAnalyzer.kt`
  - 检测入口：优先走 `detectFromImage()` MediaPipe 零拷贝路径（若配置 MediaPipe 统一管线）
  - 非 MediaPipe 路径走 `detectRoiFromNv21()` 零拷贝 ROI → Landmark 检测
  - 智能帧跳过优化（每 N 帧检测 + 运动触发重检）

## 3. 运行流程

### 3.1 预览实时检测（三路径）

#### 路径 A：MediaPipe Image 零拷贝（首选，~5ms 节省）

```text
CameraX ImageProxy
  -> FaceDetectorManager.detectFromImage(image, rotation, lensFacing)
     （跳过 YUV→ARGB CPU 转换）
  -> MediaPipeFaceDetector.detect(mediaImage: Image, ...)
     （MediaImageBuilder 包装为 MPImage，C++ 层原生消费 YUV）
  -> MediaPipe468Adapter：468 -> 106
  -> Face106ToWarpParams.convert()
  -> BeautyRenderer 使用 FaceWarpParams
```

#### 路径 B：NCNN NV21 零拷贝（ROI + Landmark 双阶段）

```text
CameraX ImageProxy
  -> FaceDetectorManager.detectRoiFromNv21(yuvData, width, height, ...)
     （DirectByteBuffer 零拷贝 → NCNN C++ 层）
  -> NcnnFaceDetector.detectRetinaFaceFromNv21(nv21Data, width, height)
     ├── C++ preprocessFromNv21(): NV21→RGB（BT.601）+ 双线性缩放 + letterbox + 归一化
     ├── C++ detectRetinaFaceFromNv21(): 三 stride 推理 + NMS
     └── JNI 返回: [x1,y1,x2,y2,confidence,10个landmarks]
  -> NcnnRoiDetector.detectRoiFromYuv(): letterbox → 原图坐标逆映射
  -> LandmarkDetector.detectLandmarks()（基于 ROI 裁剪区域）
  -> NcnnLandmarkAdapter：106 点重排
  -> Face106ToWarpParams.convert()
  -> BeautyRenderer 使用 FaceWarpParams
```

#### 路径 C：Bitmap 降级路径（Legacy）

```text
CameraX ImageProxy
  -> 转 Bitmap（YUV→ARGB CPU 转换）
  -> FaceDetectorManager.detect(bitmap, rotation, lensFacing)
      -> 按 EngineType 分流
         - MEDIAPIPE: MediaPipeFaceDetector.detect(bitmap)
         - MNN/NCNN: RoiDetector.detectRoi() + LandmarkDetector.detectLandmarks()
      -> 通过 Adapter 统一到 106 点
  -> Face106ToWarpParams.convert()
  -> BeautyRenderer 使用 FaceWarpParams
```

### 3.2 静态图检测（拍照后）

`FaceDetector.detectPhoto(bitmap, lensFacing)` 当前实现默认走 `MediaPipeFaceDetector.detectForPhoto()`。

说明：静态图链路不会走 `FaceDetectorManager` 的 `EngineType` 分支，也不会走 MNN/NCNN ROI + Landmark 组合。FaceDetectorManager 新增的 `detectFromImage()` 和 `detectRoiFromNv21()` 零拷贝路径目前仅用于预览实时检测。

## 4. 引擎与流水线配置

### 4.1 引擎选择（EngineType）

- `MEDIAPIPE`：统一管线，468 点 → 106 点映射（优先使用 `detectFromImage()` 零拷贝路径）
- `NCNN`：可配置流水线（ROI RetinaFace + Landmark 2D106），支持 NV21 零拷贝 ROI 检测
- `MNN`：可配置流水线（ROI RetinaFace + Landmark 2D106），`AtomicBoolean` CAS 非阻塞懒加载

### 4.2 MNN/NCNN 流水线组合（DetectionPipelineConfig）

> **⚠️ 审计备注（2026-06）**：原 InsightFace 流水线已随 ONNX 路径移除。当前 MNN/NCNN 备选引擎使用独立 ROI+Landmark 配置。

ROI 检测器：

- `RoiDetectorType.MEDIAPIPE`
- `RoiDetectorType.MNN`（RetinaFace）
- `RoiDetectorType.NCNN`

Landmark 检测器：

- `LandmarkDetectorType.MEDIAPIPE_468`
- `LandmarkDetectorType.MNN_2D106`
- `LandmarkDetectorType.NCNN_2D106`

## 5. 关键映射与坐标约束

### 5.1 MediaPipe 468 -> 统一 106

由 `MediaPipe468Adapter` 实现：

- 轮廓 33 点：沿 `FACE_OVAL` 两段路径插值生成
- 非轮廓 73 点：`NON_CONTOUR_MAPPING` 固定表映射
- 前置镜头：`x = 1 - x`

参考：`docs/03-TECHNICAL-SPECS/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md`

### 5.2 MNN 原生 106 -> 统一 106

由 `MnnLandmarkAdapter` 实现：使用 FULL_REMAP 对 106 点完整重排。不存在 `index -> same index` 直通点。前置镜头：`x = 1 - x`。

### 5.3 NCNN 原生 106 -> 统一 106

由 `NcnnLandmarkAdapter` 实现：使用 FULL_REMAP 对 106 点完整重排。前置镜头：`x = 1 - x`。

> **⚠️ 已废弃（2026-06）**：原 `InsightFaceAdapter.kt` 及所有 InsightFace ONNX 相关代码已删除。如需恢复 InsightFace 支持，需从模型仓库重新引入 ONNX 模型并重新实现适配器。

## 6. 当前行为边界（避免文档误读）

### 6.1 当前没有检测引擎自动冷却回退状态机

`FaceDetectorManager` 当前行为是：

- 按 `EngineType` 直接执行对应分支
- 异常时记录日志并返回 `null`
- 不维护“连续失败自动切引擎 + 冷却恢复”状态机

### 6.2 当前有以下降级行为

- MediaPipe 初始化：GPU delegate 失败会 fallback 到 CPU delegate
- MNN（RetinaFace/2D106）：Vulkan GPU 优先，不可用时 fallback CPU
- 若当帧检测失败，调用方使用“无人脸”参数继续渲染，不阻塞主流程

## 7. 性能相关实现点与优化

### 7.1 零拷贝优化（2026-06 新增）

| 优化项 | 实现方式 | 预期收益 |
|--------|----------|----------|
| MediaPipe Image 路径 | CameraX `ImageProxy` → `MediaImageBuilder` → `MPImage` | 跳过 YUV→ARGB 转换（约 5ms） |
| NCNN NV21 路径 | CameraX `DirectByteBuffer` → C++ 预处理+推理 | 跳过 Bitmap→RGB 拷贝（约 3-5ms） |
| C++ 一体式预处理 | BT.601 颜色转换 + 双线性缩放 + letterbox + 归一化 | 减少 CPU 端多次数据搬运 |

### 7.2 MNN 并发初始化优化（2026-06）

| 优化项 | 旧行为 | 新行为 |
|--------|--------|--------|
| 懒加载同步方式 | `synchronized` 阻塞调用线程 | `AtomicBoolean` CAS 非阻塞，初始中进行中跳过本帧 |
| 重型模型加载时机 | 在 `ResourceManager.onLoad()` 回调中同步执行（约 1s+） | 回调中仅重置标记，推迟到下一次 `detect()` 调用 |
| 线程行为 | 初始化进行中时其他线程被阻塞等待 | 其他线程立即返回 false，跳过本帧不阻塞渲染 |

### 7.3 Perf 日志体系

- `FaceDetectorManager` 记录 `[Perf]` 分段耗时（ROI / Landmark / Total），NV21 路径额外输出 `[Perf] NV21 path breakdown`
- NCNN C++ 层记录预处理/输入/提取/NMS 各阶段耗时（`LOG_NCNN_PERF_STEP`）
- `MediaPipeFaceDetector` / `MediaPipeLandmarkDetector` Image 路径记录 `[Perf]` 耗时
- `MnnRoiDetector` / `MnnLandmarkDetector` 初始化跳过帧记录 `[Perf]` 日志
- `CameraFrameAnalyzer` 启用智能帧跳过优化
- 调试浮层可显示"请求引擎 + 实际命中来源 + ROI + 106 点"

## 8. 相关文档

- `docs/03-TECHNICAL-SPECS/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md`
- InsightFace 106 映射文档（已移除，2026-05）
- `docs/03-TECHNICAL-SPECS/VOLCANO_106_POINTS.md`
- `docs/07-STANDARDS/COORDINATE_SYSTEM.md`
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`

## 8. 零拷贝路径选择策略（2026-06）

`FaceDetectorManager` 按以下优先级选择检测路径：

1. **若配置为 MediaPipe 统一管线** → 走 `detectFromImage()`（MediaPipe Image 零拷贝）
2. **若配置 MNN/NCNN ROI + Landmark 组合** → 走 `detectRoiFromNv21()`（优先 NCNN NV21 零拷贝，MNN 降级路径）
3. **Bitmap 降级**（仅当零拷贝路径不可用时）

---

**文档版本**: v3.0 (2026-06-08)
**维护者**: RD Team  
**说明**: 本文档已按 2026-06 零拷贝路径和并发优化更新。三引擎架构（MediaPipe/NCNN/MNN）已替代原双引擎描述。
