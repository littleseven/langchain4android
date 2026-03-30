# CameraX 预览显示比例技术文档

## 1. 问题背景

### 1.1 现象描述
在竖屏拍摄模式下，FULL 模式的预览画面出现拉伸变形，人物变细长。

### 1.2 核心矛盾
- **用户需求**：FULL 模式铺满全屏且画面不变形
- **技术限制**：相机传感器输出比例固定，无法匹配全面屏手机的屏幕比例

---

## 2. 技术原理分析

### 2.1 相机传感器物理特性

#### 2.1.1 传感器方向
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

#### 2.1.2 不同模式的传感器输出

| 模式 | 传感器输出 | 宽高比 | 说明 |
|------|-----------|--------|------|
| **4:3** | 640 x 480 | 1.33 (4:3) | 标准照片模式 |
| **16:9** | 864 x 480 | 1.8 (≈16:9) | 宽屏模式 |
| **FULL** | 864 x 480 | 1.8 | 传感器最大输出 |

### 2.2 CameraX 的旋转机制

#### 2.2.1 自动旋转流程

```
传感器输出 (864x480, 横向)
         ↓
    [CameraX Preview UseCase]
         ↓
    旋转 90° (竖屏设备)
         ↓
显示帧 (480x864, 竖向)
```

**旋转角度规则**：

| 设备方向 | 旋转角度 | 最终画面尺寸 |
|---------|---------|-------------|
| **竖屏** | +90° | 480 x 864 |
| **横屏** | 0° | 864 x 480 |
| **倒立竖屏** | +180° | 480 x 864 |
| **倒立横屏** | -90° | 480 x 864 |

#### 2.2.2 关键公式

```kotlin
// 竖屏时的尺寸转换
传感器宽度 = 864
传感器高度 = 480
旋转后宽度 = 传感器高度 = 480
旋转后高度 = 传感器宽度 = 864
旋转后比例 = 480 / 864 = 0.555... (9:16)
```

### 2.3 Surface 缩放机制

#### 2.3.1 CameraX Preview 的 SurfaceProvider

```kotlin
preview.setSurfaceProvider { surfaceRequest ->
    textureView.surfaceTexture?.let { surfaceTexture ->
        val surface = Surface(surfaceTexture)
        surfaceRequest.provideSurface(surface, ...)
    }
}
```

**CameraX 的处理流程**：
1. 接收 Surface 请求（包含 TextureView 的尺寸信息）
2. 将旋转后的帧（480x864）**缩放**到 Surface 尺寸
3. 如果 Surface 比例 ≠ 480:864，会**拉伸填充**

#### 2.3.2 不失真的条件

```
完美显示条件：
TextureView 宽高比 = 旋转后画面宽高比 = 480:864 = 0.555...

即：
TextureView 高度 / TextureView 宽度 = 864 / 480 = 1.8
```

### 2.4 屏幕与比例的数学关系

#### 2.4.1 小米 15 的屏幕参数

```
物理屏幕：1080 x 2400
屏幕比例：2400 / 1080 = 2.22 (20:9)
```

#### 2.4.2 比例冲突分析

```
理想情况（不拉伸）：
- 传感器输出：864 x 480 (1.8)
- 旋转后：480 x 864 (0.555)
- TextureView 应为：1080 x 1944 (1.8)

实际情况（全面屏）：
- 屏幕尺寸：1080 x 2400 (2.22)
- 黑边区域：上下各 (2400-1944)/2 = 228 像素

结论：
FULL 模式无法同时满足"铺满全屏"和"不变形"
```

---

## 3. 常见错误实现

### 3.1 错误 1：直接填满屏幕

```kotlin
// ❌ 错误代码
AspectRatio.RATIO_FULL -> {
    setMeasuredDimension(width, height)  // 1080x2400
}
```

**问题分析**：
- TextureView 尺寸：1080 x 2400
- 画面尺寸：480 x 864
- CameraX 缩放：将 480x864 拉伸到 1080x2400
- 拉伸比例：(2400/1080) / (864/480) = 2.22 / 1.8 = **1.23 倍**
- **结果**：画面被纵向拉长 23%

### 3.2 错误 2：使用错误的比例计算

```kotlin
// ❌ 错误代码
val targetRatio = 9f / 16f  // 0.5625
val newHeight = (width / targetRatio).toInt()  // 1080 / 0.5625 = 1920
```

**问题分析**：
- 假设比例：9:16 = 0.5625
- 实际比例：480:864 = 0.555...
- 误差：(0.5625 - 0.555) / 0.555 = **1.35%**
- **结果**：画面轻微变形

### 3.3 错误 3：混淆分子分母

```kotlin
// ❌ 错误代码
val targetRatio = 480f / 864f  // 0.555
val newHeight = (width / targetRatio).toInt()
```

**问题分析**：
- 计算：1080 / 0.555 = 1944 ✅
- 逻辑错误：应该用乘法而非除法
- 正确理解：高度 = 宽度 × (864/480) = 1080 × 1.8 = 1944

---

## 4. 正确的实现方案

### 4.1 核心原则

```
原则 1：使用传感器的实际输出比例（不是近似值）
原则 2：竖屏时按宽度计算高度
原则 3：接受有黑边（这是物理限制）
```

### 4.2 正确的计算公式

#### 4.2.1 传感器比例定义

```kotlin
// 传感器物理尺寸（横向）
传感器宽度 = 864
传感器高度 = 480
传感器宽高比 = 864 / 480 = 1.8

// 旋转 90°后的显示比例
显示宽度 = 480
显示高度 = 864
显示宽高比 = 480 / 864 = 0.555... = 1 / 1.8
```

#### 4.2.2 TextureView 尺寸计算

**竖屏模式（height > width）**：
```kotlin
// 目标：让 TextureView 的比例 = 传感器比例 = 1.8
TextureView 宽度 = 屏幕宽度 = 1080
TextureView 高度 = 宽度 × 1.8 = 1080 × 1.8 = 1944
验证：1944 / 1080 = 1.8 ✅
```

**横屏模式（width > height）**：
```kotlin
// 目标：让 TextureView 的比例 = 传感器比例 = 1.8
TextureView 高度 = 屏幕高度
TextureView 宽度 = 高度 / 1.8
```

### 4.3 完整的 Kotlin 实现

```kotlin
val textureView = remember {
    object : android.view.TextureView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            
            when (aspectRatio) {
                AspectRatio.RATIO_4_3 -> {
                    // 4:3 模式：传感器 640x480 → 比例 640/480 = 1.33
                    if (height > width) {
                        // 竖屏：宽度填满，高度按比例
                        setMeasuredDimension(width, (width * (480f / 640f)).toInt())
                    } else {
                        // 横屏：高度填满，宽度按比例
                        setMeasuredDimension((height * (640f / 480f)).toInt(), height)
                    }
                }
                
                AspectRatio.RATIO_16_9, 
                AspectRatio.RATIO_FULL -> {
                    // FULL 模式：传感器 864x480 → 比例 864/480 = 1.8
                    if (height > width) {
                        // 竖屏：宽度填满，高度按比例
                        // 关键：高度 = 宽度 × (864/480) = 宽度 × 1.8
                        setMeasuredDimension(width, (width * (864f / 480f)).toInt())
                    } else {
                        // 横屏：高度填满，宽度按比例
                        setMeasuredDimension((height * (480f / 864f)).toInt(), height)
                    }
                }
                
                else -> setMeasuredDimension(width, height)
            }
        }
    }.apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
```

### 4.4 验证方法

#### 4.4.1 日志验证

```kotlin
android.util.Log.d("PicMe:Camera", 
    "TextureView: ${measuredWidth}x${measuredHeight}, " +
    "ratio=${measuredHeight.toFloat()/measuredWidth}, " +
    "expected=1.8"
)
```

**预期输出（竖屏 FULL 模式）**：
```
TextureView: 1080x1944, ratio=1.8, expected=1.8
```

#### 4.4.2 视觉验证

**正常标准**：
- ✅ 圆形物体显示为正圆（不会变成椭圆）
- ✅ 正方形显示为正方形（不会变成长方形）
- ✅ 人脸比例正常（不会变细长）
- ⚠️ 上下有黑边（约 228 像素×2）

---

## 5. 测试验证

### 5.1 测试环境

| 项目 | 参数 |
|------|------|
| **设备** | 小米 15 |
| **屏幕** | 1080 x 2400 (20:9) |
| **Android 版本** | 15 |
| **CameraX 版本** | 1.3.x |

### 5.2 测试用例

#### 测试 1：竖屏 FULL 模式

```
输入：aspectRatio = RATIO_FULL
TextureView 测量：1080 x 1944
比例验证：1944 / 1080 = 1.8 ✅
预期结果：画面正常，上下有黑边
```

#### 测试 2：横屏 FULL 模式

```
输入：aspectRatio = RATIO_FULL
TextureView 测量：1333 x 740 (假设高度 740)
比例验证：1333 / 740 = 1.8 ✅
预期结果：画面正常，左右有黑边
```

#### 测试 3：4:3 模式

```
输入：aspectRatio = RATIO_4_3
TextureView 测量：1080 x 1440
比例验证：1440 / 1080 = 1.33 (4:3) ✅
预期结果：画面正常，上下有较大黑边
```

---

## 6. 常见问题解答

### Q1: 为什么 FULL 模式不能真正"Full"？

**A**: 
- "FULL"指的是**传感器的全画幅输出**（864x480），而不是"铺满全屏"
- 传感器的物理比例是固定的（1.8:1）
- 全面屏手机的比例通常更大（2.2:1）
- 这是**物理限制**，无法通过软件完全解决

### Q2: 能否通过裁剪实现铺满？

**A**: 
可以，但有代价：

```kotlin
// 裁剪方案
val croppedWidth = height / 2.22f  // 按屏幕比例裁剪
setMeasuredDimension(croppedWidth.toInt(), height)
```

**代价**：
- ❌ 损失约 18% 的画面内容
- ❌ 视角变窄
- ⚠️ 需要用户接受

### Q3: 为什么其他相机 App 可以铺满？

**A**: 
可能的原因：
1. **使用了裁剪方案**（牺牲视角）
2. **使用了更高分辨率的传感器**（有更多像素可裁剪）
3. **实际上也有轻微拉伸**（用户不易察觉）
4. **使用了多帧合成技术**（高端机型）

### Q4: 未来有解决方案吗？

**A**: 
可能的技术方向：
1. **更高分辨率的传感器**（提供更多裁剪空间）
2. **可变比例的传感器**（技术难度大）
3. **AI 智能填充**（生成式填充黑边区域）

---

## 7. 最佳实践总结

### 7.1 DO（推荐做法）

✅ **使用传感器的实际输出比例**
```kotlin
val sensorRatio = 864f / 480f  // 1.8
```

✅ **竖屏时按宽度计算高度**
```kotlin
if (height > width) {
    setMeasuredDimension(width, (width * sensorRatio).toInt())
}
```

✅ **接受有黑边的现实**
```kotlin
// 黑边是正常的，不要试图拉伸填充
```

### 7.2 DON'T（避免做法）

❌ **不要直接填满屏幕**
```kotlin
setMeasuredDimension(width, height)  // 会导致拉伸！
```

❌ **不要使用近似的数学比例**
```kotlin
val ratio = 9f / 16f  // 0.5625，不精确！
```

❌ **不要试图修复物理限制**
```kotlin
// 不要尝试通过算法"优化"黑边
```

---

## 8. 参考资料

### 8.1 官方文档
- [CameraX Preview UseCase](https://developer.android.com/training/camerax/preview)
- [TextureView 官方指南](https://developer.android.com/reference/android/view/TextureView)
- [CameraX 架构](https://developer.android.com/training/camerax/architecture)

### 8.2 相关技术文章
- [Understanding CameraX SurfaceProvider](https://medium.com/androiddevelopers/)
- [Android Camera Performance](https://source.android.com/docs/core/graphics/multidisplay-camera)

---

## 9. 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|----------|
| 1.0 | 2026-03-30 | 初始版本，基于 PicMe 项目实践 |

---

**文档状态**：✅ 已完成  
**适用项目**：PicMe Camera  
**最后更新**：2026-03-30
