# Camera 模块开发指令 (Module-Specific Instructions)

你是相机功能专家。在处理 `features/camera/` 目录下的任务时，必须遵守以下指令。

## 1. 核心产品逻辑 (Camera Product Logic)

### A. 智能模式触发规则 (Smart Mode Rules)
- **[MOON_MODE] 月亮模式**：当缩放倍率 `zoomRatio >= 3.0x` 且识别到圆形高亮度物体时，必须建议或自动切换至月亮预设（降低曝光，增强对比度）。
- **[NIGHT_MODE] 夜景自适应**：当 `ImageAnalysis` 反馈的光照强度低于阈值时，UI 应提示用户“保持稳定”，并自动增加 `exposureCompensation`。
- **[FACE_FOCUS] 人脸优先**：只要 ML Kit 检测到人脸，必须锁定该区域对焦，并在 UI 上显示动态追踪框。

### B. 拍摄反馈三位一体 (The "Click" Feedback)
- 每次拍摄必须同时触发：
  1. **声**：播放系统快门音（或自定义轻快音效）。
  2. **震**：触发一次短促的线性马达触感（Haptic Feedback）。
  3. **画**：预览画面进行一次 50ms 的 0.8 透明度黑场闪烁。

### C. 美颜交互规范 (BEAUTY_UX)
- **弹性反馈**：所有美颜滑杆必须实现滑块按压缩放反馈，提升操控确定性。
- **算法映射**：磨皮算法应通过非线性幂函数（如 x^0.7）增强低数值区间的感知度。
- **快捷重置**：点击滑杆左侧的图标或标签，必须立即将该项参数重置为 0。
- **精准隔离**：磨皮算法必须基于人脸分割（Face Segmentation），严禁全图模糊。

## 2. 多语言与术语规范 (I18N & Terminology)
- **[MUST] 同步翻译**：新增拍摄模式（如“人像”、“专业”）时，必须检查 `res/values*/strings.xml` 确保四种语言对齐。
- **术语对齐**：
    - **PHOTO**: 拍照 / 拍照 / Photo
    - **VIDEO**: 录像 / 錄影 / Video
    - **PORTRAIT**: 人像 / 人像 / Portrait
    - **PRO**: 专业 / 專業 / Pro
- **[UI] 长度适配**：在繁体中文（字数可能较多）或英文环境下，确保模式切换条的文本不会重叠。

## 3. 模块 SOP (标准作业程序)
1. 修改预览逻辑前，必须检查 `CameraScreen.kt` 中的 `ProcessCameraProvider` 绑定流程，确保 `unbindAll()` 逻辑正确。
2. 任何涉及到 CameraX 的配置更改，必须先调用 `analyze_current_file` 检查是否有生命周期悬空问题。
3. 性能核查：`ImageAnalysis` 的 `setAnalyzer` 内部逻辑必须在 `cameraExecutor` 中运行，严禁阻塞主线程。

## 4. 视觉规范
- 控件必须使用 `PicMeTheme` 定义的 `BlurCard` 或 `Glassmorphism` 效果。
- 按钮大圆角标准：`24.dp` 或 `CircleShape`。
