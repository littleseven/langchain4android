# 相机预览完整指南

**最后更新**：2026-03-29
**状态**：生产稳定版

---

## 1. 核心解决方案

### 1.1 核心原则
**使用 CameraX 官方推荐的 `PreviewView` + `ScaleType`**，让 CameraX 自动处理所有复杂的比例计算。

### 1.2 技术方案

#### PreviewView 配置
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

#### 动态调整 ScaleType
```kotlin
LaunchedEffect(aspectRatio) {
    previewView.scaleType = if (aspectRatio == AspectRatio.RATIO_FULL) {
        PreviewView.ScaleType.FILL_CENTER
    } else {
        PreviewView.ScaleType.FIT_CENTER
    }
}
```

#### 拍照与预览比例同步（ViewPort + UseCaseGroup）
```kotlin
val screenWidth = context.resources.displayMetrics.widthPixels
val screenHeight = context.resources.displayMetrics.heightPixels

// 为 FULL 模式配置 ViewPort
val viewPort = ViewPort.Builder(
    Rational(screenWidth, screenHeight),
    preview.targetRotation
).build()

val useCaseGroup = UseCaseGroup.Builder()
    .addUseCase(preview)
    .addUseCase(imageCapture)
    .setViewPort(viewPort)
    .build()

cameraProvider.bindToLifecycle(
    lifecycleOwner,
    cameraSelector,
    useCaseGroup
)
```

#### 手动裁剪 Bitmap（必须）
```kotlin
// 在 ImageProcessor.onCaptureSuccess 中
val cropRect = image.cropRect
val originalBitmap = image.toBitmap()
val croppedBitmap = if (cropRect.width() != originalBitmap.width ||
                         cropRect.height() != originalBitmap.height) {
    Bitmap.createBitmap(
        originalBitmap,
        cropRect.left,
        cropRect.top,
        cropRect.width(),
        cropRect.height()
    )
} else {
    originalBitmap
}
```

---

## 2. 技术原理

### 2.1 相机传感器物理特性

#### 传感器方向
```
┌─────────────────────────────┐
│   手机竖屏握持              │
│                             │
│    ┌───────────┐            │
│    │ 传感器    │ ← 横向放置 │
│    │ (864x480) │            │
│    └───────────┘            │
│                             │
└─────────────────────────────┘
```

**关键事实**：
- 相机传感器**永远横向放置**（width > height）
- 输出的原始帧永远是**横向分辨率**
- FULL 模式典型输出：**864 x 480**（宽高比 1.8:1）

#### 不同模式的传感器输出

| 模式 | 传感器输出 | 宽高比 | 说明 |
|------|-----------|--------|------|
| **4:3** | 640 x 480 | 1.33 (4:3) | 标准照片模式 |
| **16:9** | 864 x 480 | 1.8 (≈16:9) | 宽屏模式 |
| **FULL** | 864 x 480 | 1.8 | 传感器最大输出，需裁剪到屏幕比例 |

### 2.2 CameraX 的旋转机制

#### 自动旋转流程

```
传感器输出 (864x480, 横向)
         ↓
   CameraX 自动旋转 270°
         ↓
  PreviewView 显示 (480x864, 竖向)
```

**旋转规则**：
- 后置摄像头：顺时针旋转 **90°**
- 前置摄像头：顺时针旋转 **270°**
- 竖屏显示时：宽高交换（480 x 864）

### 2.3 PreviewView ScaleType 工作原理

#### FIT_CENTER（保持比例）
```
┌──────────────────┐
│   黑边 (上)      │
├──────────────────┤
│                  │
│   预览画面       │
│  (480 x 864)     │
│                  │
├──────────────────┤
│   黑边 (下)      │
└──────────────────┘
```

**适用场景**：4:3、16:9 模式
**特点**：
- 预览画面完整显示
- 可能有黑边（letterbox）
- 所见即所得

#### FILL_CENTER（裁剪填充）
```
┌──────────────────┐
│                  │← 裁剪掉顶部
│ ╔══════════════╗ │
│ ║  预览画面    ║ │
│ ║ (铺满全屏)   ║ │
│ ║              ║ │
│ ╚══════════════╝ │
│                  │← 裁剪掉底部
└──────────────────┘
```

**适用场景**：FULL 模式
**特点**：
- 预览画面铺满全屏
- 边缘会被裁剪
- 需配合 ViewPort 确保拍照与预览一致

---

## 3. 坐标系统与人脸跟踪

### 3.1 四个坐标系

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

### 3.2 坐标转换流程

#### 步骤 1：ML Kit 坐标系 → 窗口坐标系
```kotlin
val sourceCoords = floatArrayOf(
    face.boundingBox.left.toFloat(),
    face.boundingBox.top.toFloat(),
    face.boundingBox.right.toFloat(),
    face.boundingBox.bottom.toFloat()
)

val destCoords = FloatArray(4)
coordinateTransform.mapPoints(destCoords, sourceCoords)
```

**关键**：`coordinateTransform` 由 `PreviewView.getImageTransform()` 提供，自动处理旋转和缩放。

#### 步骤 2：窗口坐标系 → 屏幕坐标系
```kotlin
val screenX = destCoords[0] + previewViewLeft
val screenY = destCoords[1] + previewViewTop
```

**关键**：加上 `PreviewView` 在屏幕中的偏移量。

### 3.3 完整实现示例
```kotlin
@Composable
fun FaceTrackingOverlay(
    faces: List<Face>,
    previewView: PreviewView
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val transform = previewView.getImageTransform()
        val viewLocation = IntArray(2)
        previewView.getLocationOnScreen(viewLocation)

        faces.forEach { face ->
            // 1. ML Kit → 窗口坐标
            val sourceCoords = floatArrayOf(
                face.boundingBox.centerX().toFloat(),
                face.boundingBox.centerY().toFloat()
            )
            val destCoords = FloatArray(2)
            transform.mapPoints(destCoords, sourceCoords)

            // 2. 窗口坐标 → 屏幕坐标
            val screenX = destCoords[0] + viewLocation[0]
            val screenY = destCoords[1] + viewLocation[1]

            // 3. 绘制十字星
            drawLine(
                color = Color.White,
                start = Offset(screenX - 20f, screenY),
                end = Offset(screenX + 20f, screenY),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = Color.White,
                start = Offset(screenX, screenY - 20f),
                end = Offset(screenX, screenY + 20f),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}
```

---

## 4. 最佳实践

### 4.1 比例选择器实现
```kotlin
@Composable
fun RatioSelector(
    currentRatio: AspectRatio,
    onRatioChange: (AspectRatio) -> Unit
) {
    val ratios = listOf(
        AspectRatio.RATIO_4_3 to "4:3",
        AspectRatio.RATIO_16_9 to "16:9",
        AspectRatio.RATIO_FULL to "FULL"
    )

    Row {
        ratios.forEach { (ratio, label) ->
            Button(onClick = { onRatioChange(ratio) }) {
                Text(label)
            }
        }
    }
}
```

### 4.2 PreviewView 生命周期管理
```kotlin
DisposableEffect(previewView) {
    onDispose {
        previewView.releasePointerCapture()
    }
}
```

### 4.3 性能优化
- 使用 `ImplementationMode.COMPATIBLE` 确保兼容性
- 避免频繁切换 `ScaleType`
- 坐标转换缓存 `Matrix` 对象

---

## 5. 常见问题

### 问题 1：预览画面拉伸变形
**原因**：使用了错误的 ScaleType
**解决**：FULL 模式使用 `FILL_CENTER`，其他模式使用 `FIT_CENTER`

### 问题 2：拍照比例与预览不一致
**原因**：未配置 ViewPort 或未手动裁剪 Bitmap
**解决**：使用 `UseCaseGroup` + `ViewPort`，并在 `ImageProcessor` 中手动裁剪

### 问题 3：人脸跟踪十字星位置偏移
**原因**：坐标转换不正确
**解决**：使用 `PreviewView.getImageTransform()` + `getLocationOnScreen()`

### 问题 4：FULL 模式黑边
**原因**：传感器比例与屏幕比例不匹配
**解决**：使用 `FILL_CENTER` + ViewPort 裁剪

---

## 6. 相关文档

- `PRODUCT.md` - 产品需求规格说明书
- `FEATURES.md` - 功能交互规范
- `AGENTS.md` - AI Agent 操作规范
- `PIXELFREE_INTEGRATION.md` - 实时美颜集成文档
- `R_PLAN_GUIDE.md` - R 计划实时美颜自主方案

