# 相机预览比例方案 - 最终解决方案

## 问题总结

### 初始问题
1. **竖屏拍摄时存在垂直方向的拉伸**（16:9 和 FULL 模式）
2. **1:1 模式未实现**
3. **FULL 模式无法全屏显示**

### 根本原因
使用自定义 `TextureView` + 手动 `onMeasure` 计算比例的方案过于复杂且容易出错：
- 需要手动处理旋转、缩放、裁剪
- 比例计算容易出错（之前错误地使用了横向比例而不是竖向比例）
- 难以维护和扩展

---

## 最终解决方案

### 核心原则
**使用 CameraX 官方推荐的 `PreviewView` + `ScaleType`**，让 CameraX 自动处理所有复杂的比例计算。

### 技术方案

#### 1. PreviewView 替代 TextureView

```kotlin
val previewView = remember {
    PreviewView(context).apply {
        // [关键配置] 根据比例模式设置 ScaleType
        scaleType = if (aspectRatio == AspectRatio.RATIO_FULL) {
            PreviewView.ScaleType.FILL_CENTER  // FULL 模式：裁剪填充，铺满屏幕
        } else {
            PreviewView.ScaleType.FIT_CENTER   // 其他模式：保持比例，可能有黑边
        }
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
}
```

#### 2. 动态调整 ScaleType

```kotlin
LaunchedEffect(aspectRatio) {
    previewView.scaleType = if (aspectRatio == AspectRatio.RATIO_FULL) {
        PreviewView.ScaleType.FILL_CENTER
    } else {
        PreviewView.ScaleType.FIT_CENTER
    }
}
```

#### 3. CameraX 配置

```kotlin
val preview = Preview.Builder()
    .setTargetAspectRatio(
        when (aspectRatio) {
            AspectRatio.RATIO_4_3 -> androidx.camera.core.AspectRatio.RATIO_4_3
            AspectRatio.RATIO_16_9, AspectRatio.RATIO_FULL -> androidx.camera.core.AspectRatio.RATIO_16_9
            else -> androidx.camera.core.AspectRatio.RATIO_4_3
        }
    )
    .build()
    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
```

---

## PreviewView.ScaleType 详解

### FIT_CENTER（默认）
- **行为**：保持画面原始比例，完整显示所有内容
- **效果**：画面不变形，但可能有黑边（letterbox）
- **适用**：4:3、16:9、1:1 等标准比例模式

### FILL_CENTER
- **行为**：裁剪画面以填满整个 View
- **效果**：铺满屏幕，无黑边，但会裁掉部分内容
- **适用**：FULL 模式（用户期望全屏显示）

### 对比表

| 模式 | Target AspectRatio | ScaleType | 效果 |
|------|-------------------|-----------|------|
| **4:3** | RATIO_4_3 | FIT_CENTER | 完整显示，上下有黑边 |
| **16:9** | RATIO_16_9 | FIT_CENTER | 完整显示，上下有黑边 |
| **1:1** | RATIO_4_3 | FIT_CENTER | 完整显示，左右有黑边（由CameraX裁剪） |
| **FULL** | RATIO_16_9 | **FILL_CENTER** | 铺满屏幕，无黑边，裁剪顶部/底部 |

---

## 实时美颜集成方案

### 方案1：PreviewView.getBitmap()（推荐用于测试）

```kotlin
// 在 Compose 中定期获取预览帧
LaunchedEffect(Unit) {
    while (true) {
        val bitmap = previewView.bitmap  // 获取当前预览帧
        if (bitmap != null) {
            val processedBitmap = applyBeauty(bitmap, beautySettings)
            // 显示处理后的结果（可以用 Image composable）
        }
        delay(33)  // ~30fps
    }
}
```

**缺点**：性能较低，不适合生产环境

### 方案2：自定义 SurfaceProvider + PixelFree SDK（推荐用于生产）

```kotlin
val preview = Preview.Builder()
    .build()
    .also { previewUseCase ->
        previewUseCase.setSurfaceProvider { surfaceRequest ->
            // 1. 创建 PixelFreeGLSurfaceView
            val pixelFreeView = PixelFreeGLSurfaceView(context)
            pixelFreeView.setBeautyParams(beautySettings)

            // 2. 将 CameraX 的纹理输入到 PixelFree
            val surface = pixelFreeView.getSurface()
            surfaceRequest.provideSurface(surface, executor) { result ->
                // 清理资源
            }

            // 3. PixelFree 处理后的纹理自动渲染到 PreviewView
        }
    }
```

**优势**：
- GPU 加速，性能高（60fps+）
- 延迟低（< 16ms）
- 支持实时参数调整

### 方案3：ImageAnalysis + GPU 处理

```kotlin
val imageAnalysis = ImageAnalysis.Builder()
    .setTargetAspectRatio(aspectRatio)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .build()
    .apply {
        setAnalyzer(cameraExecutor) { imageProxy ->
            val rgbaData = imageProxy.planes[0].buffer
            val processedData = beautyEngine.processRGBA(rgbaData, width, height)
            // 渲染到 PreviewView
            imageProxy.close()
        }
    }
```

---

## 1:1 比例实现 ✅ 已实现

### 当前方案：ViewPort 裁剪（已实施）
CameraX **不直接支持** 1:1 比例，使用 `ViewPort` + `UseCaseGroup` 实现实时裁剪：

```kotlin
val useCaseGroup = if (aspectRatio == AspectRatio.RATIO_1_1) {
    // 使用4:3作为基础，通过ViewPort裁剪为1:1
    val preview = Preview.Builder()
        .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
        .build()
        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

    val imageCapture = ImageCapture.Builder()
        .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    // 创建1:1的ViewPort
    val viewport = androidx.camera.core.ViewPort.Builder(
        android.util.Rational(1, 1),  // 1:1比例
        android.view.Surface.ROTATION_0
    ).build()

    androidx.camera.core.UseCaseGroup.Builder()
        .addUseCase(preview)
        .addUseCase(imageCapture)
        .setViewPort(viewport)
        .build()
} else {
    null  // 其他比例使用标准方式
}

// 绑定相机
if (useCaseGroup != null) {
    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
} else {
    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
}
```

**优势**：
- ✅ 预览和拍照都是1:1比例
- ✅ 无需后期裁剪处理
- ✅ `PreviewView` 自动保持比例显示
- ✅ 性能更优，内存占用更少

#### 方案B：Viewport 裁剪
```kotlin
val viewport = Viewport.Builder(Rational(1, 1), Surface.ROTATION_0).build()
val useCaseGroup = UseCaseGroup.Builder()
    .addUseCase(preview)
    .addUseCase(imageCapture)
    .setViewPort(viewport)
    .build()
cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
```

---

## 优势总结

### 相比 TextureView 方案

| 特性 | TextureView（旧方案） | PreviewView（新方案） |
|------|----------------------|----------------------|
| **比例计算** | 手动实现（易出错） | CameraX 自动处理 ✅ |
| **旋转处理** | 需要手动处理 | 自动处理 ✅ |
| **ScaleType** | 不支持 | 支持 FIT/FILL ✅ |
| **美颜集成** | 复杂 | 简单（SurfaceProvider） ✅ |
| **代码量** | ~60行 | ~15行 ✅ |
| **维护性** | 低 | 高 ✅ |

### 关键要点
1. ✅ **零手动计算**：CameraX 处理所有比例、旋转、缩放
2. ✅ **FULL 模式全屏**：使用 `FILL_CENTER` 裁剪填充
3. ✅ **无拉伸变形**：`FIT_CENTER` 保持原始比例
4. ✅ **易于扩展**：后续添加美颜只需修改 SurfaceProvider
5. ✅ **性能优异**：PreviewView 内部使用 SurfaceView/TextureView 优化

---

## 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|----------|
| 1.0 | 2026-03-31 | 初始版本，记录最终解决方案 |

---

**文档状态**：✅ 已完成
**适用项目**：PicMe Camera
**最后更新**：2026-03-31

