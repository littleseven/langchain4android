# PicMe 相机坐标转换系统详解

本文档详细解释 PicMe 项目中涉及的四个坐标系及其转换关系，为相机功能开发提供数学基础。

**阅读对象**：RD 工程师、技术负责人

**相关文档**：
- `PRODUCT.md` - 产品需求规格说明书
- `FEATURES.md` - 功能交互规范（包含人脸跟踪十字星的产品逻辑）
- `app/src/main/java/com/picme/features/camera/AGENTS.md` - 相机技术实现细节

---

## 1. 核心概念：四个坐标系

PicMe 相机系统中存在四个关键坐标系，理解它们的关系是实现精确人脸跟踪的基础。

### 完整数据流

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

---

## 2. 四个坐标系详解

### 2.1 原始图像坐标系 (Sensor Space)

**来源**：Camera Sensor 直接输出的 YUV 图像

**特点**：
- 保持传感器的物理方向（通常 width > height）
- 没有经过任何旋转或镜像处理
- **坐标系原点**：左上角
- **坐标轴方向**：X 轴向右为正，Y 轴向下为正

**数据示例**：
```kotlin
imageProxy.width = 1280   // 传感器物理宽度
imageProxy.height = 720   // 传感器物理高度
imageProxy.rotationDegrees = 270  // 需要顺时针旋转 270°
```

**关键点**：这是**物理真实世界**的坐标，用户看到的手机方向

#### rotationDegrees 的含义详解

`rotationDegrees` 表示**Camera Sensor 的物理安装方向**相对于**设备自然坐标系**（Device Natural Orientation）的旋转角度。

**设备自然坐标系（0° 基准）**：
```
    ┌──────────┐
    │   Top    │  ← 听筒/前置摄像头
    │          │
    │          │
    │          │
    │  Bottom  │  ← Home 键/充电口
    └──────────┘
```

**当 rotationDegrees = 270° 时**：

```
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

---

### 2.2 ML Kit 坐标系 (Image Space)

**来源**：ML Kit 在旋转后的图像上检测人脸

**特点**：
- ML Kit 根据 `rotationDegrees` 在内部旋转了图像
- 返回的坐标是相对于**旋转后图像**的坐标
- **坐标系原点**：旋转后图像的左上角
- **坐标轴方向**：X 轴向右为正，Y 轴向下为正
- **没有镜像**（仍然是传感器视角）

**数据示例**：
```kotlin
val face = faces[0]
val bounds = face.boundingBox
val centerX = bounds.centerX()  // 323 (相对于 720x1280 的图像)
val centerY = bounds.centerY()  // 729 (相对于 720x1280 的图像)
```

**关键点**：这是**旋转后但未镜像**的坐标，ML Kit 的"数学世界"

---

### 2.3 窗口坐标系 (Window Space)

**来源**：PreviewView 使用 FIT_CENTER 显示的预览窗口

**特点**：
- PreviewView 会自动镜像前置摄像头（让用户看到"镜子里的自己"）
- 使用 FIT_CENTER 保持宽高比，可能产生 letterbox（黑边）
- **坐标系原点**：PreviewView 控件的左上角
- **坐标轴方向**：X 轴向右为正，Y 轴向下为正

**数据示例**：
```kotlin
previewView.width = 1200   // PreviewView 控件宽度
previewView.height = 2670  // PreviewView 控件高度
// 实际显示区域可能是：displayRect(0, 100, 1200, 2470)
```

**关键点**：这是**用户看到的预览画面**，已经过镜像和缩放

---

### 2.4 屏幕坐标系 (Screen Space)

**来源**：Compose Canvas 绘制十字星的绝对像素坐标

**特点**：
- 相对于整个屏幕的绝对像素坐标
- **坐标系原点**：屏幕左上角
- **坐标轴方向**：X 轴向右为正，Y 轴向下为正
- 用于最终绘制十字星

**数据示例**：
```kotlin
val screenX = 661f  // 距离屏幕左边 661 像素
val screenY = 1522f // 距离屏幕上边 1522 像素
```

**关键点**：这是**最终绘制的绝对位置**

---

## 3. 坐标系原点与坐标轴方向总结

### 四个坐标系的统一特性

| 坐标系 | 原点位置 | X 轴方向 | Y 轴方向 | 单位 |
|-------|---------|---------|---------|------|
| **Sensor Space** | 图像左上角 | → 向右为正 | ↓ 向下为正 | 像素 |
| **ML Kit Space** | 旋转后图像左上角 | → 向右为正 | ↓ 向下为正 | 像素 |
| **Window Space** | PreviewView 左上角 | → 向右为正 | ↓ 向下为正 | 像素 |
| **Screen Space** | 屏幕左上角 | → 向右为正 | ↓ 向下为正 | 像素 |

### 关键发现

- ✅ **所有坐标系都使用左上角作为原点**（符合计算机图形学惯例）
- ✅ **所有坐标系的 X 轴都向右为正**
- ✅ **所有坐标系的 Y 轴都向下为正**（Android 屏幕坐标系标准）
- ✅ **所有坐标系都使用像素作为单位**

### 为什么需要转换？

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

### 坐标系统一性验证

```kotlin
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

### 重要推论

由于所有坐标系的方向一致，我们只需要关注：
1. **旋转补偿**（rotationDegrees）
2. **镜像处理**（前置摄像头）
3. **缩放映射**（FIT_CENTER）

而**不需要**考虑坐标轴翻转、原点偏移等复杂情况！

---

## 4. 坐标系转换详解

### 前置摄像头 rot=270° 的完整转换流程

```
1. 原始图像坐标系 (Sensor Space)
   ┌────────────┐
   │ 1280 x 720 │ ← 传感器物理尺寸
   │   (宽 x 高)   │
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

---

## 5. 常见错误与陷阱

### ❌ 错误 1：混淆 ML Kit 坐标系和原始图像坐标系

```kotlin
// ❌ 错误：认为 ML Kit 返回的是原始传感器坐标
val wrongNormX = faceX / imageProxy.width  // faceX 已经是旋转后的坐标！

// ✅ 正确：理解 faceX 是相对于旋转后图像的坐标
val correctNormX = faceX / rotatedWidth  // rotatedWidth = 720 (rot=270°)
```

### ❌ 错误 2：忽略前置摄像头的镜像特性

```kotlin
// ❌ 错误：前置摄像头不镜像
Pair(normX, normY)  // 十字星会左右相反！

// ✅ 正确：前置摄像头需要镜像 X 轴
Pair(1f - normX, normY)  // 翻转 X 轴匹配预览
```

### ❌ 错误 3：错误交换 XY 轴

```kotlin
// ❌ 错误：认为 rot=270° 需要交换 XY
Pair(normY, 1f - normX)  // 导致上下移动变左右移动！

// ✅ 正确：只翻转 X 轴，不交换 XY
Pair(1f - normX, normY)  // 保持 XY 轴独立
```

---

## 6. 技术实现参考

具体的代码实现请参考：
- `app/src/main/java/com/picme/features/camera/CameraScreen.kt` - `transformFaceCoordinate()` 函数
- `app/src/main/java/com/picme/features/camera/AGENTS.md` - 技术实现细节

---

## 7. 调试建议

### 关键日志输出

```kotlin
PicMeLogger.d(
    "PicMe:Camera",
    "Step1 Size: sensor=${imageProxyWidth}x${imageProxyHeight}, " +
        "rotated=${rotatedWidth}x${rotatedHeight}, rot=$rotationDegrees"
)
PicMeLogger.d(
    "PicMe:Camera",
    "Step2 Norm: face=($faceX,$faceY), " +
        "rotatedSize=${rotatedWidth}x${rotatedHeight}, " +
        "norm=($normX,$normY)"
)
PicMeLogger.d(
    "PicMe:Camera",
    "Step3 Adjust: rot=$rotationDegrees, lens=$lensFacing, " +
        "adj=($adjustedX,$adjustedY)"
)
PicMeLogger.d(
    "PicMe:Camera",
    "Step4 Screen: adj=($adjustedX,$adjustedY), " +
        "previewSize=${previewWidth.toInt()}x${previewHeight.toInt()}"
)
PicMeLogger.d(
    "PicMe:Camera",
    "Transform: face=($faceX, $faceY), " +
        "norm=($normX, $normY), adj=($adjustedX, $adjustedY), " +
        "screen=($screenX, $screenY), " +
        "rot=$rotationDegrees, lens=$lensFacing"
)
```

### 验证场景

测试时应覆盖以下场景：
1. ✅ **竖屏自拍**：前置摄像头，0°旋转，十字星应镜像对齐
2. ✅ **横屏拍摄**：后置摄像头，90°旋转，十字星应对齐
3. ✅ **视频通话**：前置摄像头，270°旋转，十字星应对齐
4. ✅ **缩放测试**：2x 变焦时，十字星仍精确跟踪
5. ✅ **移动测试**：左右移动手机，十字星应跟随人脸

---

**最后更新**：2026-03-29  
**维护者**：RD 团队
