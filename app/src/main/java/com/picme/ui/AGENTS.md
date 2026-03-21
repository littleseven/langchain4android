# UI 层智能代理指南 (UI Layer Agents)

本文件定义了 `com.picme.ui` 包下 UI 组件的设计原则、交互规范及实现指导，旨在为 AI 代理和开发者提供清晰的上下文，确保 UI 迭代的一致性。

## 核心设计理念
- **沉浸式体验**: 采用全屏预览，最小化 UI 遮挡。使用半透明背景和动效提升层次感。
- **HyperOS 风格灵感**: 参考小米 HyperOS 相机设计，强调圆角、流体动效和“实时卡片”（如 `HyperOSLiveTile`）。
- **响应式状态管理**: 严格遵循 Unidirectional Data Flow (UDF)，由 ViewModel 管理 UI State。

## 目录结构说明
- `components/`: 通用、可复用的 UI 单元。
  - `CameraControls.kt`: 快门、模式切换、缩略图等核心控制组件。
  - `CameraOverlays.kt`: 构图辅助线、人脸对焦框、HyperOS 风格实时提示信息。
  - `CameraComponents.kt`: 侧边工具栏、滤镜选择器、美颜调节器。
- `screens/`: 顶层页面容器。
  - `CameraScreen.kt`: 主相机拍摄逻辑与 UI 组合。
  - `GalleryScreen.kt`: 智能相册，支持多种分组显示。
  - `ImageEditScreen.kt`: 基础图片编辑、涂鸦和马赛克。
- `viewmodel/`: 业务逻辑与状态持有者。
  - `MediaViewModel.kt`: 管理媒体数据的加载、删除及相册状态。
- `model/`: UI 层专用的数据模型（如 `FilterType`, `GridType`）。
- `navigation/`: 定义页面跳转路由及参数传递。

## 代理工作准则
1. **组件化原则**: 新增 UI 功能应优先考虑在 `components/` 中创建小型、无状态的 Composable，再由 `screens/` 组合。
2. **预览优先**: 每个 UI 组件和页面必须配套 `@Preview`，并使用模拟数据确保在布局编辑器中可见。
3. **性能关注**:
   - 避免在 Compose 重组路径上进行耗时计算。
   - 针对频繁变化的 UI（如对焦框动画），使用 `graphicsLayer` 减少重绘开销。
4. **交互规范**:
   - 按钮点击应有明确的触感反馈（如果适用）。
   - 所有的提示文字必须使用 `stringResource(R.string.*)` 以支持多语言。
5. **HyperOS 风格实现**:
   - 使用 `RoundedCornerShape(18.dp)` 或更高。
   - 提示卡片使用 `AnimatedVisibility` 配合 `expandVertically` 和 `fadeIn` 进入。

## 常见任务指南
- **修改相机覆盖层**: 修改 `CameraOverlays.kt` 中的 `CompositionGrid` 或 `FaceFocusIndicator`。
- **调整控制布局**: 在 `CameraControls.kt` 中调整 `ShutterButton` 或 `ModeSelector`。
- **多语言适配**: 如果新增 UI 文本，务必同步更新 `res/values/strings.xml`、`res/values-zh/strings.xml` 和 `res/values-zh-rCN/strings.xml`。
