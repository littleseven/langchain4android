# Camera 模块技术实现规范 (Camera Technical Implementation)

**模块定位**：确保 PicMe 的相机模块实现零延迟拍摄、智能场景识别和实时 HDR 处理。

**主要维护者**：[RD] 全栈工程师

**阅读对象**：RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[PERF] 零延迟拍摄**：快门延迟 < 50ms，使用 `CAPTURE_MODE_MINIMIZE_LATENCY` 模式
- **[FEEDBACK] 三位一体反馈**：触感 + 音效 + 黑场必须同步触发（50ms 内）
- **[OFFLINE] OCR 本地识别**：使用 ML Kit 离线引擎，严禁云端处理
- **[PRIVACY] 权限最小化**：仅在需要时申请相机和存储权限，提供降级方案
- **[REALTIME] 美颜实时预览**：所有美颜效果处理延迟 < 100ms，支持实时预览
- **[NATURAL] 自然美学原则**：所有美颜效果必须保持自然，避免过度失真
- **[ACCURACY] 十字星精确跟踪**：人脸跟踪十字星偏差 < 5px，支持旋转/缩放/镜像场景

## 2. 技术实现规范 (Technical Implementation)

### 2.1 CameraX 配置要求

- **ImageCapture**：必须使用 `CAPTURE_MODE_MINIMIZE_LATENCY` 模式，JPEG 质量设为 85（平衡画质与体积）
- **Preview**：锁定目标帧率 30fps，避免帧率波动导致卡顿
- **闪光灯**：默认使用自动模式（FLASH_MODE_AUTO）

### 2.2 场景识别实现逻辑

- **夜景模式触发条件**：当环境光照度 (Lux) < 10.0 时建议自动触发
  - 曝光补偿：+1.0 to +2.0
  - ISO 限制：自动提升至 1600+
- **月亮模式检测**：需同时满足三个条件：
  - 变焦倍率 > 3.0x
  - 检测到高对比度圆形物体
  - 场景为逆光环境
  - 锁定最大焦距，避免过度放大导致模糊

### 2.3 OCR 引擎集成

**ML Kit 配置要求**：
- 使用离线 Document Scanner API，禁用相册选择功能（仅允许实时拍摄）
- 设置设备源为 CAMERA，确保调用后置摄像头

**拍摄后自动 OCR 流程**：
1. **拍摄完成回调** → `onCaptureSuccess(photoUri: Uri)`
2. **立即触发后台 OCR 任务** → 调用 `viewModel.startOcrTask(photoUri)`，不阻塞 UI
3. **同步显示拍照反馈** → 播放黑场、音效、触感三位一体反馈
4. **OCR 结果展示** → 通过 TextOverlay 组件，使用 AnimatedVisibility 实现平滑出现/消失动画

### 2.4 性能优化关键点

**启动速度优化 (< 500ms)**：
- **时间分配要求**：
  - **0-50ms**：Application 初始化，仅注册必要组件（ActivityLifecycleCallbacks）
  - **50-150ms**：权限检查与请求（相机、存储）
  - **150-300ms**：CameraX 初始化并绑定生命周期
  - **300-500ms**：首次预览帧渲染完成
- **禁止事项**：
  - ❌ 预加载相机模块
  - ❌ 初始化 AI 模型（应懒加载）
  - ❌ 执行数据库迁移或大量数据加载

**美颜效果实现方案**：
- **磨皮 (Smoothing)**：
  - **算法选择**：使用 GPU 加速的双边滤波 (Bilateral Filter) 或表面模糊 (Surface Blur)
  - **实现要点**：保留边缘细节，仅平滑肤色区域
  - **参数映射**：UI 参数 0-100 → 滤波强度 σ_d (空间) 和 σ_r (灰度)
  - **性能优化**：使用 RenderScript 或 OpenGL ES 实现，避免 CPU 计算瓶颈

- **美白 (Whitening)**：
  - **色彩空间**：在 YUV 或 Lab 色彩空间调整亮度 (L) 和色度 (U/V)
  - **实现要点**：智能识别肤色区域，避免全图过曝
  - **参数映射**：UI 参数 → ΔL (亮度提升) 和 ΔU/ΔV (色度调整)

- **瘦脸 (Slim Face)**：
  - **人脸检测**：使用 ML Kit Face Detection API 获取 68/106 个 landmarks
  - **变形算法**：基于 Delaunay 三角剖分的网格变形 (Mesh Warping)
  - **实现要点**：以下颌角为中心点，向内收缩 5%-30%
  - **安全约束**：变形幅度限制在 30% 以内，保持面部比例协调

- **大眼 (Big Eyes)**：
  - **眼睛定位**：基于眼球中心点和眼眶轮廓
  - **放大算法**：径向变换 (Radial Transformation)，中心放大率最大
  - **实现要点**：保持眼神光不丢失，眼睑自然跟随
  - **安全约束**：放大比例不超过 1.3x

- **唇色 (Lip Color)**：
  - **唇部识别**：使用语义分割模型 (如 DeepLabV3) 提取唇部区域
  - **上色算法**：在 HSV 空间调整色相 (H) 和饱和度 (S)，保留明暗 (V)
  - **色号管理**：预设 12 种色号的 HSV 值
  - **实现要点**：保留唇部纹理，边缘自然过渡

- **身材调整 (Body Enhancement)**：
  - **人体检测**：使用 MediaPipe Pose 或 ML Kit Pose Detection 获取关键点
  - **丰胸算法**：以上半身关键点为基准，局部径向扩展
  - **长腿算法**：以下半身关键点为基准，纵向拉伸 + 透视校正
  - **安全约束**：调整幅度限制在 20% 以内，保持身体比例

**GPU 加速策略**：
- **优先使用 GPU**：所有图像处理方法必须使用 GPU 加速 (OpenGL ES / Vulkan)
- **内存管理**：避免频繁的 CPU-GPU 数据传输，使用 FBO (Framebuffer Object)
- **延迟控制**：单帧处理时间 < 16ms (60fps) 或 < 33ms (30fps)

**拍摄反馈同步机制**：
- **三位一体反馈必须同时触发**：
  1. **触感反馈**：调用 `HapticFeedback.performHapticFeedback()`，使用 LONG_PRESS 类型
  2. **音效播放**：启动 MediaPlayer 播放快门音（需低延迟播放器）
  3. **黑场动画**：通过 LaunchedEffect 实现 50ms 透明度渐变（1.0 → 0.0）
- **关键点**：三个反馈必须在同一帧内触发，任一反馈缺失都会导致用户感知“卡顿”

### 2.5 人脸跟踪十字星坐标转换

**问题定义**：ML Kit 检测到的人脸坐标需要转换为屏幕坐标，用于绘制十字星指示器。

**数据流与处理时机**：
```
Camera Sensor (原始图像)
    ↓
ImageProxy (YUV_420_888, rotationDegrees)
    ↓
ML Kit InputImage.fromMediaImage(mediaImage, rotationDegrees)
    ├── ML Kit 内部自动处理旋转补偿
    └── 输出：Face.boundingBox（已旋转的坐标）
    ↓
transformFaceCoordinate() / transformFaceCoordinateSimple()
    ├── Step 1: 按 rotationDegrees 交换宽高并归一化
    ├── Step 2: 前置镜像补偿（x = 1 - x）
    ├── Step 3: 旋转补偿
    └── Step 4: 映射为 Preview 像素坐标
```

**关键说明**：
- ✅ **ML Kit 自动处理旋转**：`InputImage.fromMediaImage()` 根据 `rotationDegrees` 调整检测坐标系
- ✅ **检测结果已是旋转后坐标**：`face.boundingBox` 返回相对于**旋转后**图像的坐标
- ✅ **归一化无需额外旋转**：直接使用 `imageProxy.width/height`（传感器物理尺寸）相除即可
- ✅ **Step 2 的目的**：将**已旋转的图像坐标系**映射到**屏幕显示坐标系**

**坐标系统差异**：
1. **图像坐标系**：ML Kit 检测坐标，原点在左上角，基于旋转后的图像
2. **屏幕坐标系**：PreviewView 渲染坐标，考虑 FIT_CENTER 缩放和 letterbox 效应
3. **变换因素**：旋转（坐标系映射）、镜像（前置摄像头）、宽高比适配

**四步转换算法**：

```kotlin
val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
    90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
    else -> Pair(imageProxyWidth, imageProxyHeight)
}

// Step1: 归一化
val normX = faceX / rotatedWidth
val normY = faceY / rotatedHeight

// Step2: 前置镜像
val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
    1f - normX
} else {
    normX
}

// Step3: 旋转补偿
val (adjustedX, adjustedY) = when (rotationDegrees) {
    180 -> Pair(1f - mirroredX, 1f - normY)
    else -> Pair(mirroredX, normY)
}

// Step4: 像素映射
val screenX = adjustedX * previewWidth
val screenY = adjustedY * previewHeight
```

> 约束：`transformFaceCoordinateSimple()` 与 `transformFaceCoordinate()` 必须保持同构，避免分析链路和绘制链路出现偏移差异。

**关键实现要点**：
- ✅ **统一转换函数**：分析链路使用 `transformFaceCoordinateSimple()`，屏幕绘制链路使用 `transformFaceCoordinate()`
- ✅ **前置摄像头镜像**：X 坐标翻转 `1 - normX`
- ✅ **旋转宽高先交换**：`rotationDegrees` 为 90/270 时先交换宽高再归一化
- ✅ **完整日志记录**：固定输出 `Step1~Step4` 便于调试

**验证场景**：
- ✅ 竖屏自拍（前置 0°）：十字星镜像对齐
- ✅ 横屏拍摄（后置 90°）：十字星精确跟踪
- ✅ 视频通话（前置 270°）：十字星无偏移
- ✅ 2x 变焦：十字星仍精确对准人脸

## 3. Agent 执行规约 (Execution Rules)

- **拍摄操作**：必须在后台线程保存照片，避免阻塞 UI
- **OCR 管理**：OCR 引擎在空闲 30s 后必须释放，避免内存泄漏
- **夜景模式**：必须正确应用曝光补偿（+1.0 to +2.0）
- **月亮模式**：必须锁定最大焦距，避免过度放大导致模糊
- **权限处理**：权限被拒绝时必须提供降级方案和引导设置
- **I18N**：所有用户可见字符串必须提取到 strings.xml
- **美颜效果**：
  - **实时预览**：所有美颜效果必须支持实时预览，延迟 < 100ms
  - **GPU 加速**：优先使用 GPU 实现，避免 CPU 计算瓶颈
  - **自然美学**：所有效果必须保持自然，避免过度失真
  - **安全约束**：瘦脸、身材调整等必须限制在安全范围内
  - **记忆功能**：记住用户上次使用的参数组合
- **十字星跟踪**：
  - **必须使用统一转换函数**：严禁在业务代码散落不同坐标算法
  - **必须处理旋转和镜像**：前置摄像头、横屏场景必须验证
  - **必须添加调试日志**：记录 `Step1~Step4` 便于排查问题
- **UI 主题色适配**（新增 2026-03-31）：
  - **严禁硬编码颜色**：所有文字、图标、背景必须使用 `MaterialTheme.colorScheme`
  - **文字颜色规范**：
    - 主要文字：`MaterialTheme.colorScheme.onSurface`
    - 次要文字：`MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)`
    - 强调文字：`MaterialTheme.colorScheme.primary`
  - **图标颜色规范**：
    - 常规图标：`MaterialTheme.colorScheme.onSurface`
    - 激活图标：`MaterialTheme.colorScheme.primary`
    - 次要图标：`MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)`
  - **背景颜色规范**：
    - 主背景：`MaterialTheme.colorScheme.surface`
    - 次级背景：`MaterialTheme.colorScheme.surfaceVariant`
    - 半透明背景：`MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)`
  - **禁止使用**：`Color.White`、`Color.Black`（除了在 Color Scheme 定义中）
  - **必须验证**：在浅色和深色模式下都要测试文字可读性
- **沉浸式模式**（新增 2026-03-31）：
  - **系统栏隐藏**：相机页面必须隐藏状态栏和导航栏，提供全屏拍摄体验
  - **实现方式**：
    - 使用 `WindowInsetsControllerCompat` 控制系统栏
    - 调用 `hide(WindowInsetsCompat.Type.systemBars())` 隐藏系统栏
    - 设置 `systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 允许滑动边缘临时显示
  - **生命周期管理**：
    - 在 `CameraScreen` 的 `DisposableEffect` 中配置
    - 页面退出时必须恢复系统栏显示（`show(WindowInsetsCompat.Type.systemBars())`）
  - **日志记录**：
    - 进入沉浸式：`PicMeLogger.d("PicMe:Camera", "Immersive mode enabled")`
    - 退出沉浸式：`PicMeLogger.d("PicMe:Camera", "Immersive mode disabled")`
  - **注意事项**：
    - 不需要手动调整UI元素的padding（EdgeToEdge已在MainActivity配置）
    - 确保获取到Activity窗口引用（需要类型转换 `context as? Activity`）

## 4. 常见陷阱检查清单 (Checklist)

### 4.1 功能与性能检查
- [ ] 是否在拍摄时阻塞了 UI 线程？（保存操作必须在后台）
- [ ] OCR 引擎是否在空闲 30s 后释放？（避免内存泄漏）
- [ ] 夜景模式的曝光补偿是否正确应用？（+1.0 to +2.0）
- [ ] 月亮模式是否锁定了最大焦距？（避免过度放大导致模糊）
- [ ] 拍照音效是否使用了低延迟播放器？（MediaPlayer 延迟过高）
- [ ] 权限被拒绝时是否有降级方案？（如无相机权限时引导设置）
- [ ] 所有用户可见字符串是否已提取到 strings.xml？（支持 I18N）
- [ ] 拍摄反馈是否三位一体同步触发？（触感 + 音效 + 黑场）
- [ ] 美颜效果是否使用 GPU 加速？（CPU 计算会导致卡顿）
- [ ] 瘦脸/大眼是否限制在安全范围内？（避免失真）
- [ ] 唇色是否保留唇部纹理？（避免塑料感）
- [ ] 身材调整是否保持身体比例？（避免变形）
- [ ] 十字星坐标转换是否统一走 `transformFaceCoordinateSimple()` / `transformFaceCoordinate()`？（严禁多套算法并存）
- [ ] 前置摄像头是否正确镜像？（十字星应跟随镜中人脸）
- [ ] 横屏拍摄是否处理了 90°旋转？（坐标应正确映射）
- [ ] 是否添加了调试日志？（便于排查错位问题）

### 4.2 UI 主题色适配检查
- [ ] 美颜面板文字颜色是否使用 `MaterialTheme.colorScheme.onSurface`？（不要硬编码 `Color.White`）
- [ ] 滤镜选择器文字颜色是否使用主题色？（避免浅色模式下不可见）
- [ ] 比例选择器文字颜色是否使用主题色？（选中/未选中状态都要检查）
- [ ] 所有图标颜色是否使用 `onSurface` 或 `primary`？（避免硬编码白色）
- [ ] 滑块组件颜色是否使用主题色？（thumbColor、trackColor 都要检查）
- [ ] 背景颜色是否使用 `surfaceVariant`？（不要使用半透明白色）
- [ ] 边框颜色是否使用主题色？（避免硬编码白色边框）
- [ ] 是否在浅色和深色模式下都验证了文字可读性？（两种模式都要测试）

### 4.3 沉浸式模式检查
- [ ] 相机页面是否隐藏了状态栏和导航栏？（提供全屏拍摄体验）
- [ ] 是否使用 `WindowInsetsControllerCompat` 控制系统栏？（不要使用已废弃的API）
- [ ] 是否在 `DisposableEffect` 中管理沉浸式生命周期？（确保正确清理）
- [ ] 页面退出时是否恢复了系统栏显示？（避免影响其他页面）
- [ ] 是否正确获取了Activity窗口引用？（需要类型转换 `context as? Activity`）
- [ ] 是否记录了沉浸式模式的启用和禁用日志？（便于调试）
- [ ] UI元素是否正常显示？（不被系统栏遮挡，EdgeToEdge已在MainActivity配置）

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ 快门延迟 < 50ms → 使用 `CAPTURE_MODE_MINIMIZE_LATENCY`
- ✅ 三位一体反馈 → 触感 + 音效 + 黑场同步触发
- ✅ OCR 无感集成 → 后台异步处理，不阻塞拍摄流
- ✅ 冷启动 < 500ms → 分阶段初始化，懒加载 AI 模型
- ✅ 美颜实时预览 → GPU 加速，延迟 < 100ms
- ✅ 自然美学 → 所有效果限制在安全范围内
- ✅ 十字星精确跟踪 → 统一四步坐标转换，偏差 < 5px

**技术决策记录**：
- 选择 CameraX 而非 Camera2：简化生命周期管理，降低代码复杂度
- 使用 ML Kit Document Scanner：离线可用，精度足够，无需云端
- 黑场时长定为 50ms：模拟单反机械快门感受，经用户测试最佳
- 美颜使用 GPU 加速：CPU 计算无法满足实时性要求
- 十字星坐标转换统一为四步法：避免分析链路与绘制链路偏移
- 坐标调试日志固定 Step1~Step4：便于快速定位旋转/镜像问题
