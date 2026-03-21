# Camera 模块开发指令 (Module-Specific Instructions)

你是相机功能专家。在处理 `features/camera/` 目录下的任务时，必须遵守以下指令。

## 1. 核心产品逻辑 (Camera Product Logic)
(省略...)

## 2. 多语言与术语规范 (I18N & Terminology)
- **[MUST] 同步翻译**：新增拍摄模式（如“人像”、“专业”）时，必须检查 `res/values*/strings.xml` 确保四种语言对齐。
- **术语对齐**：
    - **PHOTO**: 拍照 / 拍照 / Photo
    - **VIDEO**: 录像 / 錄影 / Video
    - **PORTRAIT**: 人像 / 人像 / Portrait
    - **PRO**: 专业 / 專業 / Pro
- **[UI] 长度适配**：在繁体中文（字数可能较多）或英文环境下，确保模式切换条的文本不会重叠。

## 3. 模块 SOP (标准作业程序)
(省略...)
