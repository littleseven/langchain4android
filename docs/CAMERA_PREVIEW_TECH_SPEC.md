# 相机预览完整指南

**最后更新**：2026-04（按预览策略重构对齐）
**状态**：生产稳定版（策略化预览链路：R Plan Provider + PreviewView）

---

## 1. 核心解决方案

### 1.1 核心原则
**采用策略化预览绑定（R Plan Provider + PreviewView 兜底）**，并在 `PreviewView` 路径下使用 `ScaleType` 处理比例。

> 说明：本指南聚焦预览层的比例与坐标问题；当前实现通过 `rememberPreviewStrategyBundle(...)` 在 `RPlanPreviewStrategy` 与 `PixelFreePreviewStrategy` 间切换。R Plan 的 `SurfaceView + Provider` 初始化、容灾回退与恢复链路详见 `R_PLAN_TECH_SPEC.md`。

### 1.2 PreviewView 路径技术方案（兜底与通用预览）

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

## 3. 坐标系统与人脸跟踪（重构对齐）

### 3.1 当前实现的转换模型

当前实现不再依赖 `PreviewView.getImageTransform()`，而是使用统一函数：

- `transformFaceCoordinateSimple(...)`（分析链路）
- `transformFaceCoordinate(...)`（屏幕绘制链路）

两者都遵循同一套四步法：

1. **归一化**：按旋转后的宽高将人脸点位映射到 `0~1`
2. **镜像补偿**：前置摄像头执行 `x = 1 - x`
3. **旋转补偿**：根据 `rotationDegrees` 做方向修正
4. **像素映射**：乘以 `previewWidth/previewHeight` 得到屏幕坐标

### 3.2 当前代码实现（简化版）

```kotlin
internal fun transformFaceCoordinateSimple(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewWidth: Float,
    previewHeight: Float,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }

    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) 1f - normX else normX

    val (adjustedX, adjustedY) = when (rotationDegrees) {
        180 -> Pair(1f - mirroredX, 1f - normY)
        else -> Pair(mirroredX, normY)
    }

    return Offset(adjustedX * previewWidth, adjustedY * previewHeight)
}
```

### 3.3 重构后注意事项

- `rotationDegrees=90/270` 时先交换 `imageProxy` 的宽高再归一化。
- 前置镜像与旋转补偿顺序不可颠倒。
- 该链路服务于十字星绘制与 `FaceWarpParams`，两者必须共用同一转换逻辑。
- 调试日志固定输出 `Step1~Step4`，用于回归比对坐标偏移。

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
**原因**：归一化宽高、前置镜像或旋转补偿顺序不一致
**解决**：统一走 `transformFaceCoordinateSimple()` / `transformFaceCoordinate()` 四步法，并核对 `Step1~Step4` 日志

### 问题 4：FULL 模式黑边
**原因**：传感器比例与屏幕比例不匹配
**解决**：使用 `FILL_CENTER` + ViewPort 裁剪

---

## 6. 相关文档

- `PRODUCT.md` - 产品需求规格说明书
- `FEATURES.md` - 功能交互规范
- `AGENTS.md` - AI Agent 操作规范
- `PIXELFREE_FALLBACK_TECH_SPEC.md` - 实时美颜集成规范
- `R_PLAN_TECH_SPEC.md` - R 计划实时美颜自主方案

