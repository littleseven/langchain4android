# 拍摄模块智能代理指南 (Camera Module Agents)

本文件详细说明了相机模块的设计模式、技术实现和集成规范，指导 AI 代理进行功能优化和特性开发。

## 目录结构 (符合 AGENTS.md)
根据全局 `AGENTS.md` 规范，相机模块位于 `features/camera/`：
- `components/`: 包含 `CameraOverlays.kt` (对焦框、构图线), `CameraControls.kt` (底部控制) 等。
- `CameraScreen.kt`: 顶层 Compose 页面，负责相机生命周期绑定与组件组合。
- `CameraViewModel.kt`: 处理相机交互逻辑、传感器数据及拍照状态。

## 核心功能
- **混合拍照模式**: 支持标准拍照 (`PHOTO`)、视频录制 (`VIDEO`)、人像 (`PORTRAIT`) 及专业模式 (`PRO`)。
- **AI 智能辅助**: 
  - 集成 ML Kit 实现实时人脸检测。
  - 自动触发人脸区域对焦 (`CameraControl.startFocusAndMetering`)。
- **专业手动控制**: 实时调节曝光补偿 (EV)、白平衡 (WB) 和变焦。
- **小米 HyperOS 体验**: 圆角 UI、动态提示卡片以及流体动效。

## 技术实现
- **CameraX 核心**: 使用 `ImageCapture`, `VideoCapture` 和 `ImageAnalysis`。
- **传感器集成**: 监测设备稳定性以支持特定拍摄场景。
- **状态管理**: 遵循 UDF 模式，由 `CameraViewModel` 统一管理。

## 代理工作准则
1. **CameraX 生命周期**: 确保所有用例正确绑定到 `LifecycleOwner`。
2. **组件化**: 新增 UI 必须放入 `components/`，保持 `CameraScreen` 的简洁。
3. **Xiaomi 风格**: 保持与 HyperOS 一致的视觉语言（圆角、动效）。
4. **性能**: 图像分析回调中严禁耗时操作。
