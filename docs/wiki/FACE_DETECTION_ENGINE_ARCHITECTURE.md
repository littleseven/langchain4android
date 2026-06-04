# 人脸检测引擎技术架构（2026-05）

## 1. 概述

PicMe 当前采用双引擎人脸检测架构：`INSIGHTFACE` 与 `MEDIAPIPE`。

架构目标：

- 统一输出 106 点（归一化坐标），供大美丽渲染链路直接消费
- 通过可配置流水线组合 ROI 与 Landmark 检测器，便于调优与回归
- 将检测引擎实现下沉到 `beauty-engine`，App 层只依赖 `api/facedetect` 契约

## 2. 代码落点（当前实现）

### 2.1 对外契约层

`beauty-engine/src/main/java/com/picme/beauty/api/facedetect/`

- `FaceDetector.kt`：统一检测接口
- `FaceDetectorFactory.kt`：创建 `FaceDetectorManager`
- `FaceDetectionResult.kt`：返回 `landmarks106 + detectionSource + roiRect`
- `EngineType.kt`：`MEDIAPIPE` / `INSIGHTFACE`
- `DetectionPipelineConfig.kt`：ROI 与 Landmark 组合配置

### 2.2 内部实现层

`beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/`

- `FaceDetectorManager.kt`：双引擎调度核心
- `DetectionPipelineFactory.kt`：创建 ROI/Landmark 检测器
- `MediaPipeFaceDetector.kt`：MediaPipe 检测入口（预览 VIDEO + 静态图 IMAGE）
- `InsightFaceDet10GDetector.kt`：Det10G 人脸框检测
- `InsightFace2D106Detector.kt`：2d106det 关键点检测
- `adapter/MediaPipe468Adapter.kt`：468 -> 106 映射
- `adapter/InsightFaceAdapter.kt`：InsightFace 原生 106 -> 统一 106 重排
- `Face106ToWarpParams.kt`：106 -> `FaceWarpParams`

### 2.3 App 集成层

- `app/src/main/java/com/picme/PicMeApplication.kt`
  - 启动时执行 `FaceLandmarkAdapterRegistry.initDefaults()`
- `app/src/main/java/com/picme/features/camera/CameraRuntimeState.kt`
  - 监听用户设置，将 `InsightFaceRoiDetectorType` / `InsightFaceLandmarkDetectorType` 转为 `DetectionPipelineConfig`
- `app/src/main/java/com/picme/features/camera/CameraFrameAnalyzer.kt`
  - 检测入口调用 `faceDetector.detect(...)`
  - InsightFace 模式下启用智能帧跳过（每 3 帧检测 + 运动触发重检）

## 3. 运行流程

### 3.1 预览实时检测

```text
CameraX ImageProxy
  -> 转 Bitmap
  -> FaceDetectorManager.detect(bitmap, rotation, lensFacing)
      -> 按 EngineType 分流
         - MEDIAPIPE: MediaPipeFaceDetector.detect()
         - INSIGHTFACE: RoiDetector.detectRoi() + LandmarkDetector.detectLandmarks()
      -> 通过 Adapter 统一到 106 点
  -> Face106ToWarpParams.convert()
  -> BeautyRenderer 使用 FaceWarpParams
```

### 3.2 静态图检测（拍照后）

`FaceDetector.detectPhoto(bitmap, lensFacing)` 当前实现固定走 `MediaPipeFaceDetector.detectForPhoto()`。

说明：静态图链路不会走 `FaceDetectorManager` 的 `EngineType` 分支，也不会走 InsightFace ROI + Landmark 组合。

## 4. 引擎与流水线配置

### 4.1 引擎选择（EngineType）

- `MEDIAPIPE`：直接使用 `MediaPipeFaceDetector.detect()`
- `INSIGHTFACE`：使用可配置流水线（ROI + Landmark）后再统一映射

### 4.2 InsightFace 流水线组合（DetectionPipelineConfig）

ROI 检测器：

- `RoiDetectorType.MEDIAPIPE`
- `RoiDetectorType.DET10G`

Landmark 检测器：

- `LandmarkDetectorType.INSIGHTFACE_2D106`
- `LandmarkDetectorType.MEDIAPIPE`

有效组合示例：

- MediaPipe ROI + InsightFace 2D106（默认）
- Det10G ROI + InsightFace 2D106
- Det10G ROI + MediaPipe Landmark（用于诊断对照）

## 5. 关键映射与坐标约束

### 5.1 MediaPipe 468 -> 统一 106

由 `MediaPipe468Adapter` 实现：

- 轮廓 33 点：沿 `FACE_OVAL` 两段路径插值生成
- 非轮廓 73 点：`NON_CONTOUR_MAPPING` 固定表映射
- 前置镜头：`x = 1 - x`

参考：`docs/03-TECHNICAL-SPECS/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md`

### 5.2 InsightFace 原生 106 -> 统一 106

由 `InsightFaceAdapter` 实现：

- 使用 `FULL_REMAP` 对 106 点完整重排
- 不存在 `index -> same index` 直通点
- 前置镜头：`x = 1 - x`

参考：`docs/03-TECHNICAL-SPECS/INSIGHTFACE_106_MAPPING.md`

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

## 7. 性能相关实现点

- `FaceDetectorManager` 记录并输出分段耗时（ROI / Landmark / Total）
- `CameraFrameAnalyzer` 在 InsightFace 模式启用帧跳过优化
- 调试浮层可显示“请求引擎 + 实际命中来源 + ROI + 106 点”

## 8. 相关文档

- `docs/03-TECHNICAL-SPECS/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md`
- `docs/03-TECHNICAL-SPECS/INSIGHTFACE_106_MAPPING.md`
- `docs/03-TECHNICAL-SPECS/VOLCANO_106_POINTS.md`
- `docs/07-STANDARDS/COORDINATE_SYSTEM.md`
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`

---

**文档版本**: v2.0 (2026-05-09)
**维护者**: RD Team  
**说明**: 本文档已按当前 `beauty-engine` 实现更新。
