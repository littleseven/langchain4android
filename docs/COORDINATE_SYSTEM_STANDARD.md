# 人脸坐标系与左右命名规范

**版本**: 1.0  
**创建日期**: 2026-05-03  
**状态**: ✅ 强制执行标准

---

## 📋 问题背景

在 PicMe 项目中，"左/右"的描述存在歧义，主要体现在：

1. **基于图像的左右**（Image Space）：从观察者视角看屏幕，左侧 = x 坐标小的位置
2. **基于人脸的左右**（Face Space）：从被拍摄者视角，其左手边 = 图像右侧（前置摄像头镜像后）

这种歧义导致：
- 代码注释混乱（如 `// 右眉，画面左侧`）
- 文档描述不一致
- 新成员理解困难
- Bug 修复时容易搞反方向

---

## ✅ 统一标准

### 核心原则：**永远使用「图像坐标系」作为唯一标准**

#### 定义

| 术语 | 定义 | 示例 |
|------|------|------|
| **图像左侧** | 图像坐标系中 x 值较小的一侧（观察者视角的左边） | `x < width/2` |
| **图像右侧** | 图像坐标系中 x 值较大的一侧（观察者视角的右边） | `x > width/2` |
| **图像上方** | 图像坐标系中 y 值较小的一侧（顶部） | `y < height/2` |
| **图像下方** | 图像坐标系中 y 值较大的一侧（底部） | `y > height/2` |

#### 禁止使用的术语

❌ **禁止**：
- "左眼"、"右眼"（未指明是图像还是人脸视角）
- "左侧脸"、"右侧脸"
- "左眉毛"、"右眉毛"

✅ **必须使用**：
- "图像左侧的眼睛"（明确说明是图像坐标系）
- "图像右侧的眼睛"
- "图像左侧的脸部轮廓"

---

## 🎯 具体规则

### 规则 1: 关键点命名

**标准格式**：`{位置}_{部位}`

| 正确命名 | 错误命名 | 说明 |
|---------|---------|------|
| `image_left_eye` | `left_eye` / `right_eye` | 明确指出是图像左侧 |
| `image_right_eye` | - | 明确指出是图像右侧 |
| `image_left_eyebrow` | `left_eyebrow` | 图像左侧的眉毛 |
| `image_right_eyebrow` | `right_eyebrow` | 图像右侧的眉毛 |
| `face_center` | - | 脸部中心（无歧义） |
| `nose_tip` | - | 鼻尖（对称器官，无歧义） |

### 规则 2: 文档描述

**标准格式**：`图像{左/右}侧的{部位}`

```markdown
❌ 错误：
- "左眼外眼角"
- "右眉眉头"
- "下巴左侧"

✅ 正确：
- "图像左侧眼睛的外眼角"
- "图像右侧眉毛的眉头"
- "图像左侧的下巴轮廓"
```

### 规则 3: 代码注释

**标准格式**：`// 图像{左/右}侧: {语义描述}`

```kotlin
// ❌ 错误注释
// 右眼外轮廓（画面左侧）
val rightEyeContour = landmarks.slice(52..57)

// ✅ 正确注释
// 图像左侧的眼睛外轮廓（对应被拍摄者的右眼）
val imageLeftEyeContour = landmarks.slice(52..57)
```

### 规则 4: 变量命名

**Kotlin 命名规范**：

```kotlin
// ❌ 错误：歧义命名
val leftEye = face.leftEye
val rightEye = face.rightEye

// ✅ 正确：明确命名
val imageLeftEye = face.getImageLeftEye()  // 图像左侧的眼睛
val imageRightEye = face.getImageRightEye()  // 图像右侧的眼睛
```

---

## 📊 坐标系对照表

### 前置摄像头（镜像模式）

```
┌─────────────────────────────────┐
│         屏幕显示区域             │
│                                 │
│   图像左侧          图像右侧     │
│   (x 小)            (x 大)      │
│                                 │
│   👁️              👁️           │
│   被拍摄者右眼      被拍摄者左眼  │
│   (image_left)      (image_right)│
│                                 │
└─────────────────────────────────┘

关键理解：
- 图像左侧 = 观察者看到的左边 = 被拍摄者的右边（镜像后）
- 图像右侧 = 观察者看到的右边 = 被拍摄者的左边（镜像后）
```

### 后置摄像头（非镜像模式）

```
┌─────────────────────────────────┐
│         屏幕显示区域             │
│                                 │
│   图像左侧          图像右侧     │
│   (x 小)            (x 大)      │
│                                 │
│   👁️              👁️           │
│   被拍摄者左眼      被拍摄者右眼  │
│   (image_left)      (image_right)│
│                                 │
└─────────────────────────────────┘

关键理解：
- 图像左侧 = 观察者看到的左边 = 被拍摄者的左边（无镜像）
- 图像右侧 = 观察者看到的右边 = 被拍摄者的右边（无镜像）
```

---

## 🔧 迁移指南

### Step 1: 识别需要修改的位置

搜索以下模式：

```bash
# 搜索模糊的左右描述
grep -r "左眼\|右眼\|左眉\|右眉" docs/ --include="*.md"
grep -r "leftEye\|rightEye" app/src/ --include="*.kt" | grep -v "imageLeft\|imageRight"
```

### Step 2: 按优先级修改

#### 优先级 1: 技术文档（最高）

**文件列表**：
- `docs/face-detection/INSIGHTFACE_106_MAPPING.md`
- `docs/BIG_BEAUTY_TECH_SPEC.md`
- `docs/CAMERA_PREVIEW_TECH_SPEC.md`
- `.lingma/skills/av-gl-expert/SKILL.md`

**修改示例**：

```markdown
# 修改前
| 33 | 右眉眉头（画面左侧） | 43 |
| 38 | 左眉眉头（画面右侧） | 101 |

# 修改后
| 33 | 图像右侧眉毛的眉头（被拍摄者右眉） | 43 |
| 38 | 图像左侧眉毛的眉头（被拍摄者左眉） | 101 |
```

#### 优先级 2: 代码注释

**文件列表**：
- `app/src/main/java/com/picme/features/camera/facedetect/adapter/InsightFaceAdapter.kt`
- `beauty-engine/src/main/java/com/picme/beauty/egl/BeautyRenderer.kt`
- `app/src/main/java/com/picme/core/image/ImageProcessor.kt`

**修改示例**：

```kotlin
// 修改前
// 右眼外轮廓：统一 52-57（画面左侧）
val rightEyeContour = unifiedLandmarks.slice(52..57)

// 修改后
// 图像左侧的眼睛外轮廓（对应被拍摄者右眼）：统一索引 52-57
val imageLeftEyeContour = unifiedLandmarks.slice(52..57)
```

#### 优先级 3: 变量命名（谨慎执行）

**注意**：变量命名修改影响范围大，建议分阶段进行：

```kotlin
// Phase 1: 添加别名（向后兼容）
@Deprecated("使用 imageLeftEye 替代", ReplaceWith("imageLeftEye"))
val leftEye get() = imageLeftEye

// Phase 2: 逐步迁移调用方
// Phase 3: 移除旧命名
```

---

## 📝 最佳实践

### 1. 始终明确坐标系

```kotlin
/**
 * 获取图像左侧眼睛的中心点
 * 
 * @return 图像坐标系中的点（原点在左上角，x 向右增加，y 向下增加）
 */
fun getImageLeftEyeCenter(): Point {
    // ...
}
```

### 2. 使用枚举避免歧义

```kotlin
enum class EyePosition {
    IMAGE_LEFT,   // 图像左侧的眼睛
    IMAGE_RIGHT   // 图像右侧的眼睛
}

fun getEyeCenter(position: EyePosition): Point {
    return when (position) {
        EyePosition.IMAGE_LEFT -> landmarks[IMAGE_LEFT_EYE_INDEX]
        EyePosition.IMAGE_RIGHT -> landmarks[IMAGE_RIGHT_EYE_INDEX]
    }
}
```

### 3. 在函数名中体现坐标系

```kotlin
// ❌ 不明确
fun getLeftEye(): Point

// ✅ 明确
fun getImageLeftEye(): Point
fun getFaceSpaceLeftEye(): Point  // 如果确实需要人脸坐标系
```

### 4. 文档中添加坐标系图示

```markdown
## 坐标系说明

本文档中所有坐标均基于**图像坐标系**：

```
(0, 0) ───────────→ x 增加
  │
  │    图像左侧       图像右侧
  │    (x 小)        (x 大)
  │
  ↓ y 增加
```
```

---

## 🚨 常见陷阱

### 陷阱 1: 混淆前后置摄像头

```kotlin
// ❌ 错误：假设前置和后置的"左眼"相同
val leftEye = if (isFrontCamera) {
    face.leftEye  // 前置：这是图像右侧！
} else {
    face.leftEye  // 后置：这是图像左侧
}

// ✅ 正确：统一使用图像坐标系
val imageLeftEye = face.getImageLeftEye()  // 永远是图像左侧
```

### 陷阱 2: 镜像翻转后忘记更新命名

```kotlin
// ❌ 错误：镜像后仍然叫 leftEye
val mirroredLeftEye = mirror(face.leftEye)  // 实际已变成图像右侧

// ✅ 正确：镜像后重新评估位置
val imageLeftEye = if (isMirrored) {
    face.originalRightEye  // 镜像后，原右眼变成图像左侧
} else {
    face.originalLeftEye
}
```

### 陷阱 3: 文档与代码不一致

```markdown
# 文档说：
"左眼使用索引 58-63"

# 代码却是：
val imageLeftEye = landmarks[58..63]  // 实际是图像左侧

# 问题：文档的"左眼"指什么？
```

**解决方案**：文档必须与代码使用相同的术语。

---

## ✅ 验收检查清单

### 文档审查

- [ ] 所有"左/右"描述都明确了是"图像左侧"还是"图像右侧"
- [ ] 添加了坐标系图示
- [ ] 前后置摄像头的差异有明确说明
- [ ] 没有使用模糊的"左眼"、"右眼"等术语

### 代码审查

- [ ] 变量命名包含 `imageLeft` 或 `imageRight` 前缀
- [ ] 注释明确说明是图像坐标系
- [ ] 函数名体现坐标系（如 `getImageLeftEye()`）
- [ ] 没有硬编码的"left/right"字符串用于 UI 显示

### 测试验证

- [ ] 前置摄像头：图像左侧显示的是被拍摄者的右眼
- [ ] 后置摄像头：图像左侧显示的是被拍摄者的左眼
- [ ] 镜像切换后，UI 标注位置正确
- [ ] 关键点调试浮层显示的位置与预期一致

---

## 📚 参考资源

### 内部文档
- [INSIGHTFACE_106_MAPPING.md](./face-detection/INSIGHTFACE_106_MAPPING.md) - 需要按新标准修订
- [CAMERA_PREVIEW_TECH_SPEC.md](./CAMERA_PREVIEW_TECH_SPEC.md) - 坐标转换章节
- [BIG_BEAUTY_TECH_SPEC.md](./BIG_BEAUTY_TECH_SPEC.md) - 人脸关键点使用

### 外部资源
- [OpenCV Coordinate System](https://docs.opencv.org/master/d2/d44/tutorial_py_image_basic_ops.html)
- [MediaPipe Face Mesh Coordinates](https://google.github.io/mediapipe/solutions/face_mesh)

---

## 🔄 维护策略

### 定期审计

每季度执行一次全文档扫描：

```bash
# 查找潜在的歧义描述
find docs/ -name "*.md" -exec grep -l "左眼\|右眼\|左眉\|右眉" {} \;

# 检查代码注释
find app/src/ -name "*.kt" -exec grep -n "//.*左眼\|//.*右眼" {} +
```

### 新人培训

在新成员 onboarding 文档中加入本规范：

```markdown
## PicMe 开发规范

### 坐标系与命名
- 阅读 [人脸坐标系与左右命名规范](./docs/COORDINATE_SYSTEM_STANDARD.md)
- 理解图像坐标系 vs 人脸坐标系的区别
- 掌握前置/后置摄像头的镜像差异
```

### CI/CD 集成（可选）

添加 lint 规则检测模糊命名：

```kotlin
// custom-lint-rules/AmbiguousNamingDetector.kt
class AmbiguousNamingDetector : Detector(), Detector.UastScanner {
    override fun getApplicableUastTypes() = listOf<UClass>(
        UVariableDeclaration::class.java
    )
    
    override fun createUastHandler(context: JavaContext) = object : UElementHandler() {
        override fun visitVariable(node: UVariableDeclaration) {
            val name = node.name
            if (name.matches(Regex("^(left|right)(Eye|Eyebrow|Cheek)$"))) {
                context.report(
                    issue = AMBIGUOUS_NAMING,
                    location = context.getLocation(node),
                    message = "变量名 '$name' 有歧义，请使用 'imageLeft' 或 'imageRight' 前缀"
                )
            }
        }
    }
}
```

---

**批准人**: [RD] 全栈工程师 + [CR] 规范守护者  
**生效日期**: 2026-05-03  
**下次审查**: 2026-08-03
