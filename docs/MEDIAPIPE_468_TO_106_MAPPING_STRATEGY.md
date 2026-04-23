# MediaPipe 468→106 点映射策略

## 概述

本文档定义 MediaPipe Face Landmarker（468点）到火山引擎 106 点标准的映射策略。

**注意**：本文档仅记录核心原则，具体映射关系以代码实现为准：
- [MediaPipeFaceDetector.kt](../app/src/main/java/com/picme/features/camera/facedetect/MediaPipeFaceDetector.kt) - 生产环境映射
- [FaceLandmarkDebugScreen.kt](../app/src/main/java/com/picme/features/debug/FaceLandmarkDebugScreen.kt) - 调试环境映射

## 核心映射原则

### 1. 第一性原理

**MediaPipe 468 点本身的拓扑结构不对称**，因此不能简单使用几何镜像（`x = 1-x`），而必须：

1. **先确定语义对称的固定点**：为每个 106 点找到 MediaPipe 中语义对应的固定点
2. **再插值过渡点**：在固定点之间使用 `midPoint` 插值
3. **验证左右对称性**：确保 M0-M15 与 M17-M32 关于 M16 对称

### 2. 左右对称定义

- **中轴点**：M16=152（下巴中心）
- **右半边**：M0-M15（画面左侧=被摄者右脸，从上到下）
- **左半边**：M17-M32（画面右侧=被摄者左脸，从下到上）
- **对称验证**：每个 M_i 与 M_(32-i) 应关于中轴线对称

### 3. 实施注意事项

1. **前置摄像头镜像**：使用前置摄像头时，MediaPipe 返回的 x 坐标需要镜像：`x = 1 - x`

2. **归一化坐标**：MediaPipe 返回的坐标为归一化坐标（0-1），直接使用即可

3. **插值函数**：使用 `midPoint(a, b)` 计算两点中点

4. **调试方法**：
   - 在 CameraDebugOverlay 中显示 106 点（蓝色）
   - 对比 106 点与 MediaPipe 原始点的位置关系
   - 验证左右对称性

## 参考

- [VOLCANO_ENGINE_106_POINTS.md](./VOLCANO_ENGINE_106_POINTS.md) - 火山引擎 106 点标准定义
- [MEDIAPIPE_468_POINTS.md](./MEDIAPIPE_468_POINTS.md) - MediaPipe 468 点语义定义
- [BIG_BEAUTY_TECH_SPEC.md](./BIG_BEAUTY_TECH_SPEC.md) - 大美丽技术规格
