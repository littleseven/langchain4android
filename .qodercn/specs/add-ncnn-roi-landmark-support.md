# 添加 NCNN 推理引擎支持 ROI 与 Landmark 检测

## Context

PicMe 当前已支持 ONNX Runtime、MNN（Vulkan GPU）和 MediaPipe（TFLite）三种推理引擎用于人脸检测。`InferenceBackendType` 和 `InferenceEngineType` 枚举中已预留 `NCNN` 值，设置页 UI 也已展示 NCNN 选项，但底层尚无实际实现。用户选择 NCNN 时，流水线会 fallback 到 ONNX Runtime（`DetectionPipelineFactory` 中 `else -> Det10GRoiDetector`）。

本计划参照现有 MNN 实现（JNI + C++ 桥接模式），为 NCNN 补齐 ROI 检测（RetinaFace / Det10G）和 Landmark 检测（2D106）的完整实现，使用与 MNN 相同的 InsightFace 模型转换后的 NCNN 格式（`.param` + `.bin`）。

## 关键决策

- **NCNN 模型**: 使用 InsightFace `det_10g` 和 `2d106det` 转换后的 NCNN 格式，模型文件放 `assets/insightface/`。
- **实现方式**: JNI + C++ 桥接（与 MNN 完全一致），新建 `ncnn_jni_bridge.cpp` + `ncnn_face_detector.cpp/h`。
- **Landmark 点序**: 假设与 InsightFace/MNN 一致，复用 `MnnLandmarkAdapter` 的逻辑（新建 `NcnnLandmarkAdapter` 但映射表相同）。
- **设备偏好**: NCNN 支持 Vulkan GPU 后端，参照 MNN 的 `requireGpu` 策略。

## 文件清单与修改要点

### 1. 新建 C++ Native 层

| 文件 | 说明 |
|------|------|
| `beauty-engine/src/main/cpp/ncnn_face_detector.h` | NCNN 检测器 C++ 类声明，接口与 `MnnFaceDetector` 对齐 |
| `beauty-engine/src/main/cpp/ncnn_face_detector.cpp` | NCNN 推理实现：load / detect（2D106） / detectRetinaFace / NMS |
| `beauty-engine/src/main/cpp/ncnn_jni_bridge.cpp` | JNI 桥接，暴露 `nativeCreate` / `nativeDestroy` / `nativeDetect` / `nativeDetectRetinaFace` |

**关键实现要点**:
- NCNN 使用 `ncnn::Net` 加载 `.param` + `.bin`。
- 输入预处理：Kotlin 层已将 Bitmap 转为 RGB ByteArray，C++ 层做归一化（mean=127.5, std=128）并填充 `ncnn::Mat`。
- NCNN 默认 NCHW，但 `ncnn::Mat` 创建时需注意 `ncnn::Mat::from_pixels_resize` 或手动填充。
- RetinaFace 后处理：参照 MNN 的 `processRetinaFaceOutput`，解析 score/bbox/landmark 三层输出，做 decode + NMS。
- 2D106 后处理：直接取输出 `FloatArray`，长度应为 212（106*2），值域 [-1, 1]。
- Vulkan GPU：通过 `ncnn::Net::opt.use_vulkan_compute = true` 开启，需在 CMake 中链接 `libncnn` 和 Vulkan。

### 2. 修改 CMake 构建配置

**文件**: `beauty-engine/src/main/cpp/CMakeLists.txt`

- 添加 NCNN 头文件路径（`beauty-engine/libs/ncnn/include`）。
- 添加 NCNN 库路径（`beauty-engine/src/main/jniLibs/${ANDROID_ABI}/libncnn.so`），使用 `SHARED IMPORTED`。
- 若启用 Vulkan，链接 `libvulkan.so`。
- 将 `ncnn_jni_bridge.cpp` 和 `ncnn_face_detector.cpp` 加入 `NATIVE_SOURCES`。
- `target_link_libraries` 追加 `ncnn`。

### 3. 新建 Kotlin JNI 桥接类

**文件**: `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/ncnn/NcnnFaceDetector.kt`

- 与 `MnnFaceDetector.kt` 接口完全一致：
  - `companion object.create(...)` -> `nativeCreate`
  - `detect(bitmap): FloatArray?` -> `nativeDetect`
  - `detectRetinaFace(bitmap, conf, nms): FloatArray?` -> `nativeDetectRetinaFace`
  - `release()` -> `nativeDestroy`
- Bitmap -> RGB ByteArray 的转换逻辑直接复用 MNN 版本。
- `System.loadLibrary("picme_native")` 已在 MNN 中加载，无需重复。

### 4. 新建 Kotlin 检测器类

| 文件 | 说明 |
|------|------|
| `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/NcnnRoiDetector.kt` | 参照 `MnnRoiDetector.kt`，懒加载、Bitmap 复用池、letterbox 映射、ROI 扩展 |
| `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/NcnnLandmarkDetector.kt` | 参照 `MnnLandmarkDetector.kt`，懒加载、ROI 裁剪、逆变换矩阵、[-1,1] 映射 |

**关键差异点**:
- 模型路径改为 `assets/insightface/det_10g.param` + `.bin` 和 `2d106det.param` + `.bin`。
- `ensureModelFile` 需同时确保 `.param` 和 `.bin` 两个文件存在。
- 输入层名 / 输出层名需与 NCNN 转换后的模型一致（NCNN 默认使用 `data` / `output` 或保留 ONNX 原始名，需在 C++ 层通过 `ex.input` / `ex.extract` 指定 blob name）。

### 5. 修改检测流水线工厂

**文件**: `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/DetectionPipelineFactory.kt`

在 `createRoiDetector` 和 `createLandmarkDetector` 的 `when (engine)` 分支中，为 `InferenceBackendType.NCNN` 添加：

```kotlin
InferenceBackendType.NCNN -> {
    val requireGpu = device != DevicePreference.FORCE_CPU
    NcnnRoiDetector(context, requireGpu = requireGpu)
}
```

（Landmark 同理）

### 6. 修改人脸检测管理器

**文件**: `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/FaceDetectorManager.kt`

- 添加 `ncnnRoiDetector` 和 `ncnnLandmarkDetector` 成员变量。
- `initialize()` 中尝试初始化 NCNN 检测器（GPU 优先）。
- `detectNcnn()` 方法（参照 `detectMnn()`），使用 `NcnnLandmarkAdapter` 适配结果。
- `EngineType` 枚举中是否需新增 `NCNN`？当前已有 `MEDIAPIPE / INSIGHTFACE / MNN`，若用户想在顶层引擎模式中选择 NCNN，需与 PM 确认。本计划先通过 **PipelineConfig** 的 `roiEngine` / `landmarkEngine` 独立选择 NCNN，不新增顶层 `EngineType`。

### 7. 新建 Landmark 适配器

**文件**: `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/adapter/NcnnLandmarkAdapter.kt`

- 与 `MnnLandmarkAdapter` 完全相同（复用 `FULL_REMAP` 映射表）。
- `detectionSource = FaceDetectionSource.NCNN`（需先在 `FaceDetectionSource` 枚举中新增 `NCNN`）。

### 8. 修改 FaceDetectionSource 枚举

**文件**: `beauty-engine/src/main/java/com/picme/beauty/api/facedetect/FaceDetectionSource.kt`

新增 `NCNN` 值。

### 9. 修改适配器注册表

**文件**: `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/adapter/FaceLandmarkAdapterRegistry.kt`

在 `initDefaults()` 中注册 `NcnnLandmarkAdapter()`。

### 10. 设置页与数据流

**已有支持，无需修改**:
- `SettingsScreen.kt` 的 `inferenceEngineSelection` 已包含 `InferenceEngineType.NCNN`。
- `UserPreferences.kt` 的 `InferenceEngineType` 已有 `NCNN`。
- `UserPreferencesRepository.kt` 已持久化 `roiEngineType` / `landmarkEngineType`。
- `CameraRuntimeState.kt` 已通过 `roiStageConfig` / `landmarkStageConfig` 将配置同步到 `FaceDetectorManager`。

**唯一需确认**: `FaceDetectionEngineMode` 枚举（`MEDIAPIPE / INSIGHTFACE / MNN`）没有 `NCNN`。如果用户希望在设置页顶层的 "Face Detection Engine" 中选择 NCNN，需要新增。但当前通过 ROI/Landmark 阶段的独立引擎选择已可覆盖需求。

### 11. I18N 字符串

已有字符串无需修改：
- `inference_engine_ncnn` / `inference_engine_ncnn` 在 `values` / `values-zh-rCN` / `values-zh-rTW` 中均已存在。

### 12. 模型文件放置

需在 `beauty-engine/src/main/assets/insightface/` 下放置：
- `det_10g.param` + `det_10g.bin`
- `2d106det.param` + `2d106det.bin`

**注意**: 模型文件不在本代码计划的修改范围内，需用户自行提供或 CI 构建时下载。

### 13. NCNN 库文件放置

需在 `beauty-engine/src/main/jniLibs/` 下按 ABI 放置：
- `arm64-v8a/libncnn.so`
- `arm64-v8a/libncnn_vulkan.so`（如需 GPU）
- `armeabi-v7a/libncnn.so`
- `armeabi-v7a/libncnn_vulkan.so`（如需 GPU）

头文件放 `beauty-engine/libs/ncnn/include/`。

## 实现顺序

1. **C++ 层**: `ncnn_face_detector.h` -> `ncnn_face_detector.cpp` -> `ncnn_jni_bridge.cpp`
2. **CMake**: 修改 `CMakeLists.txt` 添加 NCNN 源文件和库链接
3. **Kotlin JNI 桥接**: `NcnnFaceDetector.kt`
4. **Kotlin 检测器**: `NcnnRoiDetector.kt` -> `NcnnLandmarkDetector.kt`
5. **工厂集成**: 修改 `DetectionPipelineFactory.kt`
6. **适配器**: 修改 `FaceDetectionSource.kt` -> 新建 `NcnnLandmarkAdapter.kt` -> 修改 `FaceLandmarkAdapterRegistry.kt`
7. **管理器**: 修改 `FaceDetectorManager.kt` 添加 `detectNcnn()` 和初始化逻辑
8. **编译验证**: `./gradlew :beauty-engine:assembleDebug`
9. **运行验证**: `./scripts/auto-dev-loop.sh`

## 风险与注意事项

1. **NCNN 输出 blob 名称**: NCNN 转换后的 blob 名称可能与 ONNX/MNN 不同，需在 C++ 层通过 `ncnn::Extractor` 的 `input` / `extract` 方法确认正确的输入输出名。建议先用 `ncnn2mem` 或 netron 查看转换后的模型结构。
2. **RetinaFace 后处理差异**: NCNN 的 NCHW 输出排布可能与 MNN 不同，需仔细验证 score/bbox/landmark 的索引计算。
3. **Vulkan 兼容性**: NCNN 的 Vulkan 后端在某些设备上可能不稳定，建议默认 AUTO（尝试 GPU，失败不降级到 CPU，与 MNN 策略一致）。
4. **模型文件缺失**: 若 assets 中缺少 NCNN 模型文件，`ensureModelFile` 会抛出异常，需在初始化时 catch 并优雅降级。
5. **APK 体积**: NCNN 库 + 模型文件会增加 APK 体积，需确认是否接受。

## 验证步骤

1. **编译**: `./gradlew :beauty-engine:assembleDebug` 通过，无 native 编译错误。
2. **单元测试**: 运行 `beauty-engine/src/test/` 下的现有测试，确保未破坏 ONNX/MNN 路径。
3. **设置页**: 进入 Settings -> ROI Detection / Landmark Detection，选择 NCNN 引擎，确认选择可保存。
4. **运行时日志**: 启动相机，在 logcat 中过滤 `PicMe:NcnnRoi` / `PicMe:NcnnLandmark`，确认初始化成功且检测到人脸。
5. **功能验证**: 人脸关键点正常渲染（无飞点、无偏移），美颜效果（瘦脸、大眼）正常工作。
6. **对比测试**: 与 MNN/ONNX 的检测结果做坐标对比，确保一致性在可接受范围内。
