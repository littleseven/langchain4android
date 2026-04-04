# PicMe 功能细节与业务逻辑规范

本文件记录了 PicMe 核心功能的**用户交互流程、体验规范和业务规则**，作为 `PRODUCT.md` 的补充。

**文档职责**：
- ✅ 定义用户可见的交互流程和反馈规则
- ✅ 规定用户体验的定性标准（如“流畅”、“即时”）
- ✅ 描述业务场景和判定规则（如人脸分组、重复检测）
- ✅ 提供 UI 设计的视觉风格指引

**阅读对象**：产品经理、UI 设计师、测试工程师

**技术实现说明**：具体技术选型、架构设计、API 调用等实现细节由各功能模块的 `AGENTS.md` 定义，由 RD 团队决策。

**相关技术文档**：
- 相机预览实现 → [`CAMERA_PREVIEW_GUIDE.md`](CAMERA_PREVIEW_GUIDE.md)
- 实时美颜方案 → [`R_PLAN_GUIDE.md`](R_PLAN_GUIDE.md)
- PixelFree SDK → [`PIXELFREE_INTEGRATION.md`](PIXELFREE_INTEGRATION.md)
- 代码规范 → [`../AGENTS.md`](../AGENTS.md)

## 目录
1. [智能相机](#1-智能相机-camera)
   - [场景识别能力](#11-场景识别能力)
   - [拍摄交互与反馈](#12-拍摄交互与反馈)
   - [美颜系统](#13-美颜系统)
   - [滤镜系统](#14-滤镜系统)
   - [OCR 智能服务](#15-ocr-智能服务)
2. [动态相册](#2-动态相册-gallery)
   - [智能聚类逻辑](#21-智能聚类逻辑)
   - [媒体查看器交互](#22-媒体查看器交互)
3. [设计系统与规范](#3-设计系统与规范)
   - [HyperOS 视觉风格](#31-hyperos-视觉风格)
   - [色彩系统](#32-色彩系统)
   - [摄影美学原则](#33-摄影美学原则)
4. [通用规范](#4-通用规范)
   - [国际化](#41-国际化)
   - [启动与性能优化](#42-启动与性能优化)
   - [隐私与安全](#43-隐私与安全)

## 1. 智能相机 (Camera)

### 1.1 场景识别能力
- **夜景模式 (Night)**：当环境光度值 (Lux) < 10 时建议自动触发。增加曝光补偿 (+1.0 to +2.0)，并锁定 ISO 范围以平衡噪点。
- **月亮模式 (Moon)**：
    - **触发条件**：变焦倍率 > 3.0x 且检测到高对比度圆形发光体。
    - **逻辑**：自动降低曝光值 (EV) 以清晰显示月面纹理，应用特定的锐化滤镜。

### 1.2 拍摄交互与反馈
- **快门触感**：调用 `Vibrator` 的 `HapticFeedbackConstants.LONG_PRESS` 模拟机械快门震动（时长 50ms）
- **黑场闪烁**：拍摄瞬时，UI 层插入 50ms 的纯黑 Overlay，模拟单反反光板跳动
- **音效反馈**：同步播放快门音效，音量跟随系统媒体音量
- **视觉确认**：快门按钮按下时立即缩放至 0.95x，释放后恢复

### 1.3 美颜系统

#### 1.3.1 面部精修功能
- **磨皮 (Smoothing)**：
    - **算法原理**：基于双边滤波 (Bilateral Filter) 的肤色平滑，保留边缘细节
    - **参数范围**：0-100，默认值 35
    - **效果描述**：去除皮肤瑕疵，保留五官清晰度，避免过度模糊
    - **性能要求**：处理延迟 < 50ms，支持实时预览

- **美白 (Whitening)**：
    - **算法原理**：智能色调提升，在 YUV 空间调整亮度和色度
    - **参数范围**：0-100，默认值 25
    - **效果描述**：均匀肤色，自然提亮，避免假白
    - **适用场景**：人像摄影、逆光环境

- **瘦脸 (Slim Face)**：
    - **算法原理**：基于人脸 68/106 landmarks 的脸型重塑，使用网格变形技术
    - **参数范围**：-50~+50，默认值 0（负值为丰满脸型）
    - **安全约束**：下颌角调整幅度不超过原尺寸的 30%，避免失真
    - **效果描述**：V 脸效果，保持面部比例协调

- **大眼 (Big Eyes)**：
    - **算法原理**：眼睛区域局部放大，基于眼球中心的径向变换
    - **参数范围**：0-100，默认值 20
    - **安全约束**：放大比例不超过 1.3x，保持眼部自然
    - **效果描述**：双眼更有神，保持眼神光

#### 1.3.2 妆容调节功能
- **唇色 (Lip Color)**：
    - **色号选择**：提供 12 种预设色号（豆沙色、正红色、珊瑚色、玫瑰色等）
    - **算法原理**：智能唇部识别，基于语义分割的上色
    - **参数范围**：强度 0-100，默认值 40
    - **效果描述**：自然上色，保留唇部纹理

- **腮红 (Blush)**：
    - **算法原理**：苹果肌区域识别，渐变晕染
    - **参数范围**：0-100，默认值 20
    - **色号选择**：3 种色系（粉色、橙色、梅子色）
    - **效果描述**：好气色，自然红润

- **眉毛 (Eyebrow)**：
    - **算法原理**：眉形加深与填充，基于眉毛 landmarks
    - **参数范围**：0-100，默认值 15
    - **效果描述**：眉形更清晰，弥补稀疏

#### 1.3.3 身材管理功能
- **丰胸 (Body Enhancement)**：
    - **算法原理**：基于人体上半身关键点检测，安全的视觉优化
    - **参数范围**：-30~+30，默认值 0（负值为缩小）
    - **安全约束**：调整幅度不超过原尺寸的 20%，保持身体比例协调
    - **隐私保护**：仅在本地处理，不上传任何数据

- **长腿 (Leg Extension)**：
    - **算法原理**：下半身视觉比例优化，基于人体姿态估计
    - **参数范围**：0-50，默认值 0
    - **安全约束**：拉长比例不超过 1.15x，避免身体变形
    - **效果描述**：视觉上更显高挑，保持自然比例

#### 1.3.4 美颜交互规范

##### 1.3.4.1 入口与导航
- **一级入口**：拍摄页右上角控制栏采用“总开关 + 三类子功能”四按钮结构
- **图标设计**：使用 Material Icons (Rounded)，避免相近人脸图标造成识别混淆
- **图标与排序**（由上到下）：
- 美颜总开关：`Icons.Rounded.AutoFixHigh`
- 面部精修：`Icons.Rounded.Face`
- 妆容调节：`Icons.Rounded.ColorLens`
- 身材管理：`Icons.Rounded.SelfImprovement`
- **布局逻辑**：先全局开关，再同层展示三类美颜子功能；控制栏进一步按“美颜组 -> 构图组 -> 风格组”分组
- **组内顺序**：
- 构图组：画幅 -> 网格
- 风格组：场景 -> 滤镜
- **按钮间距**：组内垂直间距 12dp，组间额外增加 6dp 视觉分隔
- **选中状态**：总开关按美颜启停高亮；子功能按钮按当前打开面板高亮（Primary 色）


##### 1.3.4.2 面板设计
- **面板高度**：半屏高度（屏幕高度的 50%），使用 `LocalConfiguration.current.screenHeightDp` 动态计算
- **面板样式**：
    - 背景：深色渐变遮罩（黑色 70% → 透明）
    - 内容区：圆角卡片（28dp+），符合 HyperOS 风格
    - 标题栏：左侧标题文字 + 右侧关闭按钮
- **动画效果**：
    - 进入：从底部滑入 + 淡入（`slideInVertically` + `fadeIn`）
    - 退出：向底部滑出 + 淡出（`slideOutVertically` + `fadeOut`）

##### 1.3.4.3 面板互斥逻辑
- **互斥规则**：三个子功能面板互斥，同一时间只能打开一个
- **切换行为**：
    - 点击按钮 A 时，如果面板 B 已打开，先关闭面板 B，再打开面板 A
    - 点击已打开面板的按钮，关闭当前面板
- **关闭方式**：
    - 点击面板外部遮罩区域
    - 点击面板右上角关闭按钮
    - 点击其他功能按钮（触发切换）
    - 系统返回键（可选）

##### 1.3.4.4 参数调节交互
- **实时预览**：参数调整实时生效，延迟 < 100ms
- **参数范围**：所有美颜强度限制在 0-100 范围内，部分功能支持负值
- **滑块设计**：
    - 左侧显示功能图标
    - 中间显示参数名称和当前值
    - 右侧提供重置按钮（点击恢复默认值）
- **对比切换**：长按预览按钮可查看原图，松手立即恢复美颜效果
- **一键重置**：每个功能支持单独重置为默认值
- **批量操作**：支持"全部重置"和"推荐配置"快捷操作
- **记忆功能**：记住用户上次使用的参数组合

##### 1.3.4.5 R Plan 引擎策略与用户体验
- **双引擎共存**：R 计划与 PixelFreeEffects 长期保留，支持配置开关切换。
- **默认引擎**：R 计划（自主实现，性能与可控性优先）。
- **备用引擎**：PixelFreeEffects（稳定兜底）。
- **切换入口**：设置页「美颜引擎」单选项（即时生效，切换后立即影响预览）。
- **故障回退**：R 计划初始化失败或运行中异常时，自动回退至 PixelFreeEffects。
- **用户提示**：发生自动回退时，使用 Toast 提示“已切换稳定模式，可在设置中重试 R Plan”。
- **可恢复性**：异常恢复后允许用户手动切回 R 计划，不需要重启应用。
- **长期库化方向**：R Plan 逐步从 App 内部实现演进为独立视觉能力基础库，App 仅通过统一能力接口接入。
- **能力范围**：基础库负责美颜、滤镜、妆容能力与参数模型；业务层仅处理交互和编排。
- **兼容策略**：库化过程中保留 PixelFree 兜底与回退语义，确保线上体验稳定。
- **技术细节**：详见 [`R_PLAN_GUIDE.md`](R_PLAN_GUIDE.md) 与 [`PIXELFREE_INTEGRATION.md`](PIXELFREE_INTEGRATION.md)。

### 1.3.5 R Plan 实时预览性能与调试控制（2026-04 更新）
- **调试总开关（设置页）**：默认开启；关闭后统一隐藏拍摄页调试浮层、调试工具入口和 Log 入口。
- **调试浮层指标**：实时展示 `FPS`、`processingTime(ms)`、`adaptiveDelay(ms)`、`CPU(%)`、`nullFrameCount`。
- **人脸检测模式开关**：
    - **Landmark 模式（默认）**：性能优先，满足瘦脸/大眼依赖的关键点需求。
    - **Contour 模式**：精度优先，计算开销更高。
- **动态检测间隔开关**：默认开启；仅在开启且存在瘦脸/大眼效果时生效。
- **动态间隔强度档位**：
    - **保守**：`320~520ms`，更省电、温控更稳。
    - **平衡（默认）**：`280~450ms`，综合体验。
    - **激进**：`220~360ms`，效果跟手优先。
- **交互联动规则**：
    - 用户调整任意美颜参数时自动打开美颜总开关。
    - 当所有美颜参数归零时自动关闭美颜总开关。

#### 1.3.5.1 产品验收口径（R Plan）
- **首帧可见时间**：进入拍摄后 500ms 内出现可用预览画面（弱网/离线场景同标准）。
- **参数跟手性**：滑杆变更到画面变化的体感延迟 < 100ms。
- **稳定性底线**：R 计划异常时，2s 内完成自动回退并保持拍摄按钮可用。
- **可用性优先**：回退后拍照、录像、切镜头流程不阻断，用户可继续核心任务。
- **可观测性**：自动回退、手动切引擎、检测模式切换必须有结构化日志，便于 QA 回归。

#### 1.3.5.2 灰度发布与回滚规则（R Plan）
- **灰度入口**：通过设置页「美颜引擎」进行用户可控灰度，默认保持 R Plan 为主引擎。
- **灰度阶段**：建议按 5% -> 20% -> 50% -> 100% 分阶段放量，每阶段至少观察 24 小时。
- **准入条件**：上一阶段需满足崩溃率无显著上升、FPS 达标率稳定、自动回退率可控。
- **回滚触发**：出现大面积黑屏、首帧超时、连续回退或关键链路不可拍时，立即停止放量。
- **回滚策略**：优先服务端/配置下发切回 PixelFreeEffects；极端情况下发版本热修复。
- **回滚验收**：回滚后需确认拍照、录像、切镜头、参数调节全部可用，且回退提示文案正确展示。

#### 1.3.5.3 用户提示与反馈文案（R Plan）
- **自动回退提示**：`已切换稳定模式，可在设置中重试 R Plan`（短 Toast，2s）。
- **手动切换成功**：`已启用 R Plan 实时美颜` / `已启用稳定美颜模式`。
- **重试提示**：用户在设置页切回 R Plan 时，若初始化失败需提示 `R Plan 启动失败，已为你保持稳定模式`。
- **提示原则**：文案应短句、低打扰、结果导向，避免技术术语堆叠。
- **I18N 要求**：以上提示必须同步覆盖 `values`、`values-zh-rCN`、`values-zh-rTW`。

### 1.3.6 三大目标驱动的重构计划（产品视角）

#### 1.3.6.1 阶段划分
- **Phase 1（2~4 周）**：补齐 P0 自动化真实断言与 CR 阻断规则，优先稳住商业级可交付能力。
- **Phase 2（4~8 周）**：按 Clean Architecture 收敛边界，逐步去除 feature 对 data 实现的直接依赖。
- **Phase 3（8~16 周）**：推进 R Plan 库化，形成可独立迭代的美颜/滤镜/妆容基础能力模块。

#### 1.3.6.2 用户体验约束（迁移期间）
- **可用性优先**：迁移过程中必须保持双引擎容灾可用，禁止因为重构影响拍照主流程。
- **感知稳定**：引擎切换、回退、重试的用户提示文案保持一致，避免认知抖动。
- **性能不退化**：首帧、跟手性、回退耗时不得劣化于当前基线。

#### 1.3.6.3 里程碑验收口径
- **M1（质量底座）**：P0 用例全部自动化并纳入门禁。
- **M2（架构收敛）**：核心场景完成分层收敛，domain 无跨层污染。
- **M3（库化能力）**：R Plan 以能力接口对外，App 仅作为消费者接入。

### 1.4 滤镜系统

#### 1.4.1 滤镜视觉风格
- **LEICA_CLASSIC**:
    - 视觉风格：强调暗部细节，低饱和度，轻微提高对比度
    - 适用场景：街头摄影、建筑、纪实
- **FILM_GOLD**:
    - 视觉风格：暖色调，模拟柯达胶片质感，高光柔和
    - 适用场景：人像、旅行、美食
- **COOL**:
    - 视觉风格：冷色调，清爽干净，轻微提高饱和度
    - 适用场景：风景、城市夜景
- **WARM**:
    - 视觉风格：暖色调，温馨氛围，肤色自然
    - 适用场景：日落、室内、亲子

#### 1.4.2 滤镜性能要求
- **实时预览**：应用滤镜后，预览画面应保持流畅，无明显延迟或卡顿
- **内存占用**：滤镜功能不应导致应用占用过多内存，避免系统杀后台
- **切换延迟**：切换不同滤镜时，动画过渡应流畅（目标 < 200ms）

### 1.5 OCR 智能服务
- **定位**：无感后台服务。集成于拍摄流与相册查看器。
- **触发时机**：
    - **拍摄中**：检测到矩形边缘且拍摄完成后，立即启动 `OcrUseCase`。识别结果通过 `TextOverlay` 形式覆盖在原图上。
    - **相册内 (MediaPager)**：
        - **主动入口**：点击工具栏"提取文字"图标。
        - **快捷操作**：长按图片文字区域触发。触发时必须同步执行 `HapticFeedbackConstants.LONG_PRESS` 触感反馈。
- **交互与展示规范 (Interaction REFINED)**：
    - **视觉呈现**：识别结果采用原位浮层 (Overlay) 展示，卡片使用白色背景 + 70% 黑色背景遮罩。
    - **卡片样式**：大圆角设计 (28dp+)，符合 HyperOS 审美。
    - **卡片结构 [NEW]**：
        - **Header 区**：主标题"提取文字"（Primary 色，Bold）+ 右侧字数统计（灰色，12sp）
        - **Divider**：浅灰色分割线，增加层次感
        - **Content 区**：可滚动的文字内容（最大高度 300dp，行高 22sp），超出部分可滚动查看
        - **Footer 区**：两个等宽操作按钮（各占 50% 宽度）
            - **复制按钮**：左侧 OutlinedButton，圆角 16dp，内含复制图标 + "复制"文字，点击触发 Impact 触感及 Toast
            - **分享按钮**：右侧 Filled Button（强调色），圆角 16dp，内含分享图标 + "分享"文字，调起系统分享面板
    - **功能闭环**：
        - **一键复制**：按钮触发剪贴板写入，同步触发 `Impact` 触感及 Toast 提示（文案见 I18N）。
        - **一键分享**：通过系统原生 `Intent.ACTION_SEND` 调起分享面板。
        - **快速关闭**：支持点击右上角关闭按钮或点击卡片外部遮罩区域退出，退出时必须重置 OCR 状态流。
- **性能与资源**：OCR 引擎应在空闲 30s 后自动释放，以节省内存。
- **产品需求**：见 `../PRODUCT.md Section 3.1 & 3.2`

### 1.6 人脸跟踪十字星定位系统

#### 1.6.1 核心问题定义
**问题现象**：前后置摄像头切换、设备旋转、缩放时，十字星与预览画面中的人脸位置错位。

**根本原因**：
1. **坐标系差异**：ML Kit 检测坐标 (Image Coordinate) ≠ 屏幕显示坐标 (Screen Coordinate)
2. **旋转变换**：设备旋转导致图像传感器坐标系与屏幕坐标系不一致
3. **镜像变换**：前置摄像头预览需要水平翻转
4. **缩放变换**：FIT_CENTER 模式下的 letterbox 效应
5. **宽高比不匹配**：预览宽高比 ≠ 屏幕宽高比

#### 1.6.2 数学模型与计算流程

**数据流与处理时机**：

```
Camera Sensor (原始图像)
    ↓
ImageProxy (YUV_420_888 格式)
    ├── imageProxy.width/height (传感器分辨率，如 1280x720)
    ├── imageProxy.imageInfo.rotationDegrees (0/90/180/270)
    └── imageProxy.image (MediaImage)
         ↓
ML Kit InputImage.fromMediaImage()
    ├── 输入：MediaImage + rotationDegrees
    ├── ML Kit 内部自动处理旋转补偿
    └── 输出：人脸检测结果（基于旋转后的图像坐标）
         ↓
Face.boundingBox
    ├── centerX() / centerY() (已考虑旋转的坐标)
    └── 坐标系：相对于旋转后图像的左上角原点
         ↓
transformFaceCoordinate()
    ├── Step 1: 归一化 (基于 imageProxy.width/height)
    ├── Step 2: 旋转 + 镜像补偿
    └── Step 3: FIT_CENTER 映射到屏幕
```

**关键说明**：
- ✅ **ML Kit 自动处理旋转**：`InputImage.fromMediaImage(mediaImage, rotationDegrees)` 会在内部根据 `rotationDegrees` 调整检测坐标系
- ✅ **检测结果已是旋转后坐标**：`face.boundingBox` 返回的坐标是相对于**旋转后**图像的坐标，无需再次应用旋转到检测坐标
- ✅ **归一化使用原始尺寸**：虽然 ML Kit 处理了旋转，但 `imageProxy.width/height` 始终是传感器的物理尺寸（未旋转），因此归一化直接使用即可
- ✅ **Step 2 的目的是什么**：不是旋转检测坐标，而是将**已经旋转过的坐标系**映射到**屏幕显示坐标系**

#### 1.6.2.1 四个坐标系的关系

**详细技术文档请查看**：[`CAMERA_PREVIEW_GUIDE.md`](CAMERA_PREVIEW_GUIDE.md) - 第 3 节：坐标系统与人脸跟踪

**快速概览**：

```
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│  原始图像坐标系  │      │   ML Kit 坐标系   │      │    窗口坐标系    │      │   屏幕坐标系     │
│ (Sensor Space)  │ ───► │ (Image Space)   │ ───► │ (Window Space)  │ ───► │ (Screen Space)  │
└─────────────────┘      └─────────────────┘      └─────────────────┘      └─────────────────┘
     ▲                        ▲                        ▲                        ▲
     │                        │                        │                        │
Camera Sensor          ML Kit 检测后           PreviewView            Compose Canvas
YUV_420_888            Face.boundingBox       FIT_CENTER             绘制十字星
```

**关键结论**：
- ✅ 所有坐标系都使用左上角作为原点，X 轴向右为正，Y 轴向下为正
- ✅ **ML Kit 返回的坐标已经是相对于旋转后图像的坐标**（faceX 始终水平，faceY 始终垂直）
- ✅ 坐标转换只需要关注：镜像处理（前置）、方向翻转、缩放映射
- ❌ **不需要交换 XY 轴**（rot=90°和 rot=270°都不需要）

**详细说明**：完整的技术细节（包括 rotationDegrees 的物理含义、各坐标系的原点和坐标轴方向、常见错误示例等）请参考独立技术文档 `COORDINATE_SYSTEMS.md`。

**完整数据流中的四个关键坐标系**：

```
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│  原始图像坐标系  │      │   ML Kit 坐标系   │      │    窗口坐标系    │      │   屏幕坐标系     │
│ (Sensor Space)  │ ───► │ (Image Space)   │ ───► │ (Window Space)  │ ───► │ (Screen Space)  │
└─────────────────┘      └─────────────────┘      └─────────────────┘      └─────────────────┘
     ▲                        ▲                        ▲                        ▲
     │                        │                        │                        │
Camera Sensor          ML Kit 检测后           PreviewView            Compose Canvas
YUV_420_888            Face.boundingBox       FIT_CENTER             绘制十字星
```

**1️⃣ 原始图像坐标系 (Sensor Space)**

- **来源**：Camera Sensor 直接输出的 YUV 图像
- **特点**：
  - 保持传感器的物理方向（通常 width > height）
  - 没有经过任何旋转或镜像处理
  - **坐标系原点**：左上角
  - **坐标轴方向**：X 轴向右为正，Y 轴向下为正
- **数据示例**：
  ```kotlin
  imageProxy.width = 1280   // 传感器物理宽度
  imageProxy.height = 720   // 传感器物理高度
  imageProxy.rotationDegrees = 270  // 需要顺时针旋转 270°
  ```
- **关键点**：这是**物理真实世界**的坐标，用户看到的手机方向

**rotationDegrees 的含义详解**：

`rotationDegrees` 表示**Camera Sensor 的物理安装方向**相对于**设备自然坐标系**（Device Natural Orientation）的旋转角度。

```
设备自然坐标系（0° 基准）：
    ┌──────────┐
    │   Top    │  ← 听筒/前置摄像头
    │          │
    │          │
    │          │
    │  Bottom  │  ← Home 键/充电口
    └──────────┘
    
当 rotationDegrees = 270° 时：

实际设备方向：       Camera Sensor 方向:
┌──────────┐        ┌──────────┐
│          │        │   Top →  │
│          │        │          │
│  Top →   │   =    │          │  ← Sensor 物理朝上
│          │        │          │     需要逆时针转 270°
│          │        │          │     (或顺时针转 90°)
└──────────┘        └──────────┘
顶部朝左             Sensor 物理方向
(用户视角)           (需要旋转补偿)
```

**常见 rotationDegrees 值**：
- **0°**：Sensor 方向与设备自然方向一致（通常发生在后置摄像头，设备倒立）
- **90°**：Sensor 需要顺时针旋转 90°（通常发生在后置摄像头，横屏右握持）
- **180°**：Sensor 需要旋转 180°（通常发生在前置摄像头，设备倒立）
- **270°**：Sensor 需要逆时针旋转 270°（通常发生在前置摄像头，横屏左握持）

**2️⃣ ML Kit 坐标系 (Image Space)**

- **来源**：ML Kit 在旋转后的图像上检测人脸
- **特点**：
  - ML Kit 根据 `rotationDegrees` 在内部旋转了图像
  - 返回的坐标是相对于**旋转后图像**的坐标
  - **坐标系原点**：旋转后图像的左上角
  - **坐标轴方向**：X 轴向右为正，Y 轴向下为正
  - **没有镜像**（仍然是传感器视角）
- **数据示例**：
  ```kotlin
  val face = faces[0]
  val bounds = face.boundingBox
  val centerX = bounds.centerX()  // 323 (相对于 720x1280 的图像)
  val centerY = bounds.centerY()  // 729 (相对于 720x1280 的图像)
  ```
- **关键点**：这是**旋转后但未镜像**的坐标，ML Kit 的"数学世界"

**3️⃣ 窗口坐标系 (Window Space)**

- **来源**：PreviewView 使用 FIT_CENTER 显示的预览窗口
- **特点**：
  - PreviewView 会自动镜像前置摄像头（让用户看到"镜子里的自己"）
  - 使用 FIT_CENTER 保持宽高比，可能产生 letterbox（黑边）
  - **坐标系原点**：PreviewView 控件的左上角
  - **坐标轴方向**：X 轴向右为正，Y 轴向下为正
- **数据示例**：
  ```kotlin
  previewView.width = 1200   // PreviewView 控件宽度
  previewView.height = 2670  // PreviewView 控件高度
  // 实际显示区域可能是：displayRect(0, 100, 1200, 2470)
  ```
- **关键点**：这是**用户看到的预览画面**，已经过镜像和缩放

**4️⃣ 屏幕坐标系 (Screen Space)**

- **来源**：Compose Canvas 绘制十字星的绝对像素坐标
- **特点**：
  - 相对于整个屏幕的绝对像素坐标
  - **坐标系原点**：屏幕左上角
  - **坐标轴方向**：X 轴向右为正，Y 轴向下为正
  - 用于最终绘制十字星
- **数据示例**：
  ```kotlin
  val screenX = 661f  // 距离屏幕左边 661 像素
  val screenY = 1522f // 距离屏幕上边 1522 像素
  ```
- **关键点**：这是**最终绘制的绝对位置**

#### 1.6.2.2 坐标系原点与坐标轴方向总结

**四个坐标系的统一特性**：

| 坐标系 | 原点位置 | X 轴方向 | Y 轴方向 | 单位 |
|-------|---------|---------|---------|------|
| **Sensor Space** | 图像左上角 | → 向右为正 | ↓ 向下为正 | 像素 |
| **ML Kit Space** | 旋转后图像左上角 | → 向右为正 | ↓ 向下为正 | 像素 |
| **Window Space** | PreviewView 左上角 | → 向右为正 | ↓ 向下为正 | 像素 |
| **Screen Space** | 屏幕左上角 | → 向右为正 | ↓ 向下为正 | 像素 |

**关键发现**：
- ✅ **所有坐标系都使用左上角作为原点**（符合计算机图形学惯例）
- ✅ **所有坐标系的 X 轴都向右为正**
- ✅ **所有坐标系的 Y 轴都向下为正**（Android 屏幕坐标系标准）
- ✅ **所有坐标系都使用像素作为单位**

**为什么需要转换？**

虽然所有坐标系的方向一致，但由于以下原因仍然需要转换：

1. **Sensor → ML Kit**：
   - 原因：Sensor 物理安装方向与设备自然方向不一致
   - 解决：根据 `rotationDegrees` 旋转图像

2. **ML Kit → Window**：
   - 原因：前置摄像头预览需要镜像效果
   - 解决：对 X 轴进行翻转 `mirroredX = 1 - normX`

3. **Window → Screen**：
   - 原因：FIT_CENTER 可能产生 letterbox，需要映射到实际显示区域
   - 解决：乘以预览尺寸得到绝对像素坐标

**坐标系统一性验证**：

```
// 所有坐标系都遵循相同的右手定则（Y 轴向下）

Sensor Space (1280x720):
(0,0) ─────────────→ X+
  │
  │    👤 (323, 729)
  │
  ↓
  Y+

ML Kit Space (720x1280, rot=270°):
(0,0) ─────────────→ X+
  │
  │    👤 (323, 729)
  │
  ↓
  Y+

Window Space (PreviewView):
(0,0) ─────────────→ X+
  │
  │    👤 (镜像后)
  │
  ↓
  Y+

Screen Space (绝对像素):
(0,0) ─────────────→ X+
  │
  │    ⌖ (661, 1522)
  │
  ↓
  Y+
```

**重要推论**：

由于所有坐标系的方向一致，我们只需要关注：
1. **旋转补偿**（rotationDegrees）
2. **镜像处理**（前置摄像头）
3. **缩放映射**（FIT_CENTER）

而**不需要**考虑坐标轴翻转、原点偏移等复杂情况！

**坐标系转换详解**：

```
【前置摄像头 rot=270° 的完整转换流程】

1. 原始图像坐标系 (Sensor Space)
   ┌────────────┐
   │ 1280 x 720 │ ← 传感器物理尺寸
   │   (宽x高)   │
   └────────────┘
        ↓ rotation=270°
2. ML Kit 坐标系 (Image Space)
   ┌────────────┐
   │            │
   │   720 x    │ ← 旋转后尺寸
   │   1280     │   人脸在 (323, 729)
   │      👤    │
   └────────────┘
        ↓ 归一化 + 镜像
3. 窗口坐标系 (Window Space)
   ┌────────────┐
   │  Preview   │
   │   View     │ ← FIT_CENTER 显示
   │   1200 x   │   实际显示区域有黑边
   │   2670     │
   │      👤    │ ← 镜像后的预览
   └────────────┘
        ↓ 转换为像素坐标
4. 屏幕坐标系 (Screen Space)
   ┌────────────┐
   │  Screen    │
   │   1200 x   │ ← 整个屏幕
   │   2670     │   十字星画在 (661, 1522)
   │      ⌖    │
   └────────────┘
```

**为什么需要四次转换？**

| 转换步骤 | 目的 | 解决的问题 |
|---------|------|-----------|
| Sensor → ML Kit | 旋转补偿 | 让图像方向匹配设备方向 |
| ML Kit → Window | 镜像 + 归一化 | 让坐标匹配预览画面（前置摄像头需要镜像） |
| Window → Screen | 像素化 | 让归一化坐标转换为绝对像素位置 |

**常见错误与陷阱**：

❌ **错误 1**：混淆 ML Kit 坐标系和原始图像坐标系
```kotlin
// ❌ 错误：认为 ML Kit 返回的是原始传感器坐标
val wrongNormX = faceX / imageProxy.width  // faceX 已经是旋转后的坐标！

// ✅ 正确：理解 faceX 是相对于旋转后图像的坐标
val correctNormX = faceX / rotatedWidth  // rotatedWidth = 720 (rot=270°)
```

❌ **错误 2**：忽略前置摄像头的镜像特性
```kotlin
// ❌ 错误：前置摄像头不镜像
Pair(normX, normY)  // 十字星会左右相反！

// ✅ 正确：前置摄像头需要镜像 X 轴
Pair(1f - normX, normY)  // 翻转 X 轴匹配预览
```

❌ **错误 3**：错误交换 XY 轴
```kotlin
// ❌ 错误：认为 rot=270° 需要交换 XY
Pair(normY, 1f - normX)  // 导致上下移动变左右移动！

// ✅ 正确：只翻转 X 轴，不交换 XY
Pair(1f - normX, normY)  // 保持 XY 轴独立
```

**Step 1: 归一化坐标转换**
```
输入：人脸中心坐标 (faceX, faceY)，来自 Face.boundingBox
      图像尺寸 (imageWidth, imageHeight)，来自 ImageProxy
输出：归一化坐标 (normX, normY) ∈ [0, 1]

normX = faceX / imageWidth
normY = faceY / imageHeight
```

**为什么归一化不需要处理旋转？**
- `imageProxy.width/height` 是传感器物理尺寸（如 1280x720）
- `faceX/faceY` 是 ML Kit 在旋转后图像上检测到的坐标
- ML Kit 已经根据 `rotationDegrees` 调整了检测坐标系
- 因此直接相除即可得到正确的归一化坐标

**Step 2: 旋转变换补偿（坐标系映射）**
根据设备旋转角度，将**图像坐标系**映射到**屏幕坐标系**：

| rotationDegrees | 后置摄像头 (Back) | 前置摄像头 (Front) | 说明 |
|----------------|------------------|-------------------|------|
| 0° (竖屏) | (normX, normY) | (1 - normX, normY) *镜像* | 传感器竖直放置 |
| 90° (横屏右) | (normY, 1 - normX) | (1 - normY, 1 - normX) *镜像* | 传感器顺时针旋转 90° |
| 180° (倒立) | (1 - normX, 1 - normY) | (normX, 1 - normY) *镜像* | 传感器倒置 |
| 270° (横屏左) | (1 - normY, normX) | (normY, normX) *镜像* | 传感器逆时针旋转 90° |

**前置摄像头镜像原理**：
- 前置摄像头预览时，用户看到"镜中自己"，需要水平翻转
- 公式：`mirroredX = 1 - normX`
- **注意**：镜像操作在旋转变换之后进行

**Step 3: FIT_CENTER 映射**
PreviewView 使用 `ScaleType.FIT_CENTER` 保持预览不失真：

```
输入：归一化坐标 (adjustedX, adjustedY)
      PreviewView 尺寸 (previewWidth, previewHeight)
      实际渲染区域 (displayRect)

输出：最终屏幕坐标 (screenX, screenY)

// PreviewView 内部计算（伪代码）
displayRect = calculateDisplayRect(previewSize, imageSize, previewWidth, previewHeight)
screenX = displayRect.left + adjustedX * displayRect.width()
screenY = displayRect.top + adjustedY * displayRect.height()
```

**关键实现**：使用 `MeteringPointFactory` 自动处理 FIT_CENTER 映射
```kotlin
val factory = previewView.meteringPointFactory
val point = factory.createPoint(adjustedX, adjustedY)
val screenOffset = Offset(point.x, point.y)
```

#### 1.6.3 完整算法实现

```kotlin
/**
 * 人脸坐标转换函数
 * 
 * 【数据流】
 * 1. Camera Sensor -> ImageProxy (YUV_420_888, rotationDegrees)
 * 2. ML Kit 检测 -> Face.boundingBox (已处理旋转)
 * 3. 归一化 -> 旋转映射 -> 镜像 -> FIT_CENTER 映射
 * 
 * @param faceX 人脸中心 X 坐标（图像坐标系，来自 ML Kit，已考虑旋转）
 * @param faceY 人脸中心 Y 坐标（图像坐标系，来自 ML Kit，已考虑旋转）
 * @param imageWidth 图像宽度（ImageProxy 提供，传感器物理宽度）
 * @param imageHeight 图像高度（ImageProxy 提供，传感器物理高度）
 * @param previewView PreviewView 实例（用于坐标转换）
 * @param rotationDegrees 图像旋转角度（0/90/180/270，由 ImageProxy 提供）
 * @param lensFacing 摄像头方向（FRONT/BACK）
 * @return 屏幕坐标 Offset（用于绘制十字星）
 */
private fun transformFaceCoordinate(
    faceX: Float,
    faceY: Float,
    imageWidth: Int,
    imageHeight: Int,
    previewView: PreviewView,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    // Step 1: 归一化坐标
    // 注意：ML Kit 已处理旋转，faceX/Y 是旋转后图像上的坐标
    // imageWidth/Height 是传感器物理尺寸，直接相除即可
    val normX = faceX / imageWidth
    val normY = faceY / imageHeight
    
    // Step 2: 旋转变换 + 镜像补偿
    // 目的：将图像坐标系映射到屏幕坐标系
    val (adjustedX, adjustedY) = when (rotationDegrees) {
        90 -> {
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                Pair(normY, 1f - normX)  // 后置 90 度
            } else {
                Pair(1f - normY, 1f - normX)  // 前置 90 度（镜像）
            }
        }
        270 -> {
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                Pair(normY, normX)  // 前置 270 度（镜像）
            } else {
                Pair(1f - normY, normX)  // 后置 270 度
            }
        }
        180 -> Pair(1f - normX, 1f - normY)  // 180 度倒立
        else -> Pair(normX, normY)  // 0 度竖屏
    }
    
    // Step 3: FIT_CENTER 映射（使用 PreviewView 内置功能）
    // 自动处理 letterbox 效应和宽高比适配
    val factory = previewView.meteringPointFactory
    val point = factory.createPoint(adjustedX, adjustedY)
    
    return Offset(point.x, point.y)
}
```

**调用示例**（CameraScreen.kt）：
```kotlin
// ImageAnalysis 分析器中
imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        // 1. 创建 InputImage（ML Kit 自动处理旋转）
        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees  // 关键：传入旋转角度
        )
        
        // 2. ML Kit 检测人脸
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val bounds = face.boundingBox
                    
                    // 3. 坐标转换（ML Kit 返回的坐标已包含旋转信息）
                    val screenPoint = transformFaceCoordinate(
                        faceX = bounds.centerX().toFloat(),  // 已旋转的坐标
                        faceY = bounds.centerY().toFloat(),
                        imageWidth = imageProxy.width,       // 传感器物理尺寸
                        imageHeight = imageProxy.height,
                        previewView = previewView,
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        lensFacing = actualLensFacing
                    )
                    
                    facePoint = screenPoint
                }
            }
    }
    imageProxy.close()
}
```

#### 1.6.4 调试日志与验证

**关键日志输出**：
```kotlin
PicMeLogger.d(
    "PicMe:Camera",
    "Transform: face=($faceX, $faceY), norm=($normX, $normY), " +
        "adj=($adjustedX, $adjustedY), rot=$rotationDegrees, lens=$lensFacing"
)
```

**验证场景**：
1. ✅ **竖屏自拍**：前置摄像头，0°旋转，十字星应镜像对齐
2. ✅ **横屏拍摄**：后置摄像头，90°旋转，十字星应对齐
3. ✅ **视频通话**：前置摄像头，270°旋转，十字星应对齐
4. ✅ **缩放测试**：2x 变焦时，十字星仍精确跟踪
5. ✅ **移动测试**：左右移动手机，十字星应跟随人脸

#### 1.6.5 性能要求
- **实时性**：坐标转换延迟 < 16ms（60fps）
- **精度**：十字星中心与人脸中心偏差 < 5px
- **稳定性**：无明显抖动或跳跃
- **内存**：不产生额外 GC 压力（避免在 onDraw 中创建对象）

## 2. 动态相册 (Gallery)

### 2.1 智能聚类逻辑
- **人物分组 (Face Cluster)**：使用 ML Kit 的 Face Detection 提取特征点。当两张脸的特征距离 < 0.4 时判定为同一人。
- **重复照片判定**：
    - **精确重复**：MD5 哈希值完全一致。
    - **相似照片**：感知哈希 (pHash) 汉明距离 < 5。

### 2.2 媒体查看器交互
- **流体动效**：图片展开与收起必须跟随手指坐标（Bezier 曲线：0.4, 0.0, 0.2, 1）。
- **交互细节**：
    - **下滑关闭**：垂直位移 > 150dp 时触发关闭，否则回弹至原位。
    - **缩放切换**：双指捏合缩放 > 2.0x 时，平滑过渡至编辑模式。
    - **边界反馈**：滚动到首尾时，显示 20dp 的弹性拉伸效果。
- **工具栏布局**：
    - **位置**：顶部安全区域下方，距离屏幕顶部 16dp。
    - **按钮排列**：左侧关闭按钮，右侧 OCR、信息、删除按钮（间距 8dp）。
    - **视觉样式**：半透明黑色背景 (alpha 0.5)，白色图标，选中状态使用 Primary 色高亮。

## 3. 设计系统与规范

### 3.1 HyperOS 视觉风格
- **大圆角设计**：所有卡片、按钮统一使用 28dp+ 圆角
- **毛玻璃效果**：实时高斯模糊 (blur 20dp) + 半透明背景 (alpha 0.5-0.8)
- **流体动效**：Bezier 曲线 (0.4, 0.0, 0.2, 1)，模拟物理世界惯性
- **微交互反馈**：长按触发触感反馈，点击触发缩放反馈 (0.95x)

### 3.2 色彩系统
- **Primary 色**：科技蓝 (#00E5FF) - 用于强调操作和选中状态
- **Secondary 色**：强调粉 (#FFFF4081) - 用于特殊提示和警告
- **深色模式**：背景 #121212，表面 #1E1E1E，文字白色
- **浅色模式**：背景白色，表面浅灰，文字黑色

**实现规范**（2026-03-31 更新）：
- **主题色适配**：所有 UI 组件必须使用 `MaterialTheme.colorScheme` 获取颜色
- **禁止硬编码**：严禁使用 `Color.White` 或 `Color.Black` 作为文字/图标颜色
- **自动适配**：使用主题色可确保浅色/深色模式下都有良好的可读性
- **组件实现**：参考 `CameraComponents.kt` 中的 `BeautySelector`、`FilterSelector` 等组件

### 3.3 摄影美学原则
- **真实自然**：避免过度美颜和失真，保持人物肤色自然
- **高级质感**：参考徕卡色彩科学，强调暗部细节和层次感
- **情感表达**：通过滤镜传达不同情绪（温暖、冷静、怀旧）
- **专业调色**：提供精细的参数控制（饱和度、对比度、色温）

## 4. 通用规范

### 4.1 国际化

#### 4.1.1 多语言词汇表

**通用功能**
| 中文 (zh-CN) | 繁体 (zh-TW) | 英文 (EN) | 备注 |
| :--- | :--- | :--- | :--- |
| 相册 | 相簿 | Gallery | 符合港台习惯 |
| 设置 | 設定 | Settings | |
| 扫描文本 | 掃描文字 | Scan Text | |
| 提取成功 | 提取成功 | Extracted | |
| 复制 | 複製 | Copy | OCR 功能按钮 |
| 分享 | 分享 | Share | OCR 功能按钮 |
| 提取文字 | 提取文字 | Extract Text | OCR 功能标题 |
| 字 | 字 | Characters | 字数统计单位 |
| 识别中... | 識別中... | Recognizing... | OCR 加载状态 |
| 关闭 | 關閉 | Close | 通用关闭按钮 |
| 删除 | 刪除 | Delete | 通用删除操作 |
| 媒体来源 | 媒體來源 | Media Source | 图片来源标签 |

**美颜功能** (2026-03-31 更新)
| 中文 (zh-CN) | 繁体 (zh-TW) | 英文 (EN) | 备注 |
| :--- | :--- | :--- | :--- |
| 面部精修 | 面部精修 | Facial Refinement | 美颜一级入口 |
| 妆容调节 | 妝容調節 | Makeup Adjustment | 美颜一级入口 |
| 身材管理 | 身材管理 | Body Management | 美颜一级入口 |
| 磨皮 | 磨皮 | Smoothing | 面部精修功能 |
| 美白 | 美白 | Whitening | 面部精修功能 |
| 瘦脸 | 瘦臉 | Slim Face | 面部精修功能 |
| 大眼 | 大眼 | Big Eyes | 面部精修功能 |
| 唇色 | 唇色 | Lip Color | 妆容调节功能 |
| 腮红 | 腮紅 | Blush | 妆容调节功能 |
| 眉毛 | 眉毛 | Eyebrow | 妆容调节功能 |
| 丰胸 | 豐胸 | Body Enhancement | 身材管理功能 |
| 长腿 | 長腿 | Leg Extension | 身材管理功能 |
| 人脸关键点模式（更快） | 人臉關鍵點模式（更快） | Face landmark mode (faster) | 调试工具开关 |
| 动态人脸检测间隔 | 動態人臉檢測間隔 | Adaptive face detect interval | 调试工具开关 |
| 动态间隔强度档位 | 動態間隔強度檔位 | Adaptive interval profile | 调试工具分组标题 |
| 保守 | 保守 | Conservative | 动态间隔档位 |
| 平衡 | 平衡 | Balanced | 动态间隔档位（默认） |
| 激进 | 激進 | Aggressive | 动态间隔档位 |
| 已切换稳定模式，可在设置中重试 R Plan | 已切換穩定模式，可在設定中重試 R Plan | Switched to stable mode. Retry R Plan in Settings. | R Plan 自动回退 Toast |
| 已启用 R Plan 实时美颜 | 已啟用 R Plan 即時美顏 | R Plan real-time beauty enabled. | 手动切换成功提示 |
| 已启用稳定美颜模式 | 已啟用穩定美顏模式 | Stable beauty mode enabled. | 切换到 PixelFree 提示 |
| R Plan 启动失败，已为你保持稳定模式 | R Plan 啟動失敗，已為你保持穩定模式 | R Plan failed to start. Stable mode is kept. | R Plan 重试失败提示 |

#### 4.1.2 I18N 实现规范
- **资源文件位置**：各语言文案定义在 `res/values-*/strings.xml` 中
- **命名规则**：字符串资源 ID 采用小驼峰命名，格式为 `[feature]_[description]`
- **占位符处理**：使用标准占位符，确保各语言版本语义通顺
- **复数形式**：英文需支持单复数变化，中文使用量词区分
- **文案更新流程**：新增功能时，必须同步更新英文、简体中文、繁体中文三个版本

### 4.2 启动与性能优化

#### 4.2.1 冷启动流程 (< 500ms)
- **时间分配**：
    - 0-50ms：Application 初始化（仅注册必要组件）
    - 50-150ms：权限检查与请求（相机、存储）
    - 150-300ms：CameraX 初始化并绑定生命周期
    - 300-500ms：首次预览帧渲染完成
- **禁止事项**：
    - ❌ 显示启动页 (Splash Screen)
    - ❌ 执行数据库迁移或大量数据加载
    - ❌ 等待 AI 模型加载完成（应后台异步加载）

#### 4.2.2 相册滚动优化 (120fps)
- **用户体验目标**：
    - 在包含 1000+ 张照片的相册中滑动时，保持视觉流畅无卡顿
    - 缩略图加载应跟手，无明显白屏或延迟
    - 快速滑动停止后，首屏图片应立即清晰显示
- **加载策略**：
    - 优先保证当前可见区域的图片清晰度
    - 预加载用户即将看到的图片（前后相邻项）
    - 内存紧张时自动降低非可见图片的优先级

#### 4.2.3 拍摄延迟优化 (< 50ms)
- **用户体验目标**：
    - 按下快门后应立即听到拍照音效和触感反馈（感知延迟 < 50ms）
    - 照片保存过程不应阻塞界面，用户可立即查看下一张照片
    - 整个拍摄流程（按下到可继续拍摄）应在 200ms 内完成
- **反馈机制**：
    - 必须同时提供三种反馈：触感震动、快门音效、屏幕黑场闪烁
    - 任一反馈的缺失都会导致用户感知"卡顿"

### 4.3 隐私与安全

#### 4.3.1 本地 AI 约束
- **隐私承诺**：
    - 所有 AI 功能（人脸检测、OCR 识别、智能分类）必须在设备上完成
    - 严禁将用户照片上传至云端进行分析
    - 应用不应具备联网能力（不申请网络权限）
- **用户体验影响**：
    - 离线环境下所有功能必须正常使用
    - AI 处理速度应达到秒级响应（人脸检测 < 1s，OCR < 3s）
    - 模型精度可能略低于云端方案，但换取了极致的隐私保护

#### 4.3.2 数据存储安全
- **用户数据保护**：
    - 用户的照片、相册分组等核心数据必须存储在设备本地
    - 支持用户设置应用锁（密码或生物识别）保护隐私
    - 提供"隐藏相册"功能，允许用户对敏感内容二次加密
- **权限透明**：
    - 仅在必要时向用户申请权限（如首次访问相册时）
    - 明确告知用户每个权限的用途（相机、存储、人脸）
    - 用户拒绝权限后，应降级功能而非崩溃（如无存储权限时仅能拍照不能保存）
