# Coordinate System Standard - 快速参考

**快速索引坐标系规范的核心要点**

---

## 🚀 快速开始

### 1. 检查代码规范

```bash
# 检测代码
docs/scripts/check-coordinate-annotation.sh

# 检测文档
docs/scripts/check-doc-coordinate-annotation.sh
```

### 2. 核心原则

```
允许两种坐标系，但严禁混用
```

---

## 📋 速查表

### 坐标系对比

| 特性 | 图像坐标系 | 人脸坐标系 |
|------|-----------|-----------|
| **前缀** | `image_` | `user_` 或 `face_` |
| **原点** | 左上角 (0, 0) | 被拍摄者中心 |
| **x 轴** | 向右增加 | 被拍摄者左侧为左 |
| **适用层** | 渲染层、算法层 | UI 层、业务层 |
| **示例** | `imageLeftEye` | `userLeftEye` |

### 前置 vs 后置

| 摄像头 | 图像左侧 | 图像右侧 |
|--------|---------|---------|
| **前置**（镜像） | 被拍摄者右脸 | 被拍摄者左脸 |
| **后置**（非镜像） | 被拍摄者左脸 | 被拍摄者右脸 |

---

## ✅ 正确示例

### 代码注释

```kotlin
// ✅ 正确
val imageLeftEye = landmarks[IMAGE_LEFT_EYE]  // [图像坐标系]
val userLeftEye = getUserLeftEye(isFront)     // [人脸坐标系]

// ❌ 错误
val leftEye = getLeftEye()  // 未标注坐标系
```

### 函数 KDoc

```kotlin
/**
 * [图像坐标系] 获取图像左侧眼睛中心点
 */
fun getImageLeftEyeCenter(): Point

/**
 * [人脸坐标系] 获取被拍摄者左眼中心点
 */
fun getUserLeftEyeCenter(isFront: Boolean): Point
```

### 文档描述

```markdown
> **坐标系说明**：本节基于**图像坐标系**。

| 索引 | 部位 |
|------|------|
| 58-63 | [图像坐标系] 图像左侧眼睛外轮廓 |
```

---

## ❌ 常见错误

### 错误 1: 混用坐标系

```kotlin
// ❌ 错误
val distance = calculateDistance(imageLeftEye, userRightEye)

// ✅ 正确
val distance = calculateDistance(imageLeftEye, imageRightEye)
```

### 错误 2: 未标注坐标系

```kotlin
// ❌ 错误
val leftEye = getLeftEye()

// ✅ 正确
val imageLeftEye = getImageLeftEye()
```

### 错误 3: 忘记镜像转换

```kotlin
// ❌ 错误：前置摄像头未镜像
fun convert(userLandmarks: List<Point>): List<Point> {
    return userLandmarks  // 缺少镜像逻辑
}

// ✅ 正确
fun convert(userLandmarks: List<Point>, isFront: Boolean): List<Point> {
    return userLandmarks.map { point ->
        if (isFront) Point(1f - point.x, point.y) else point
    }
}
```

---

## 🔧 转换函数模板

```kotlin
/**
 * [坐标转换] 人脸坐标系 → 图像坐标系
 */
fun convertUserToImageCoordinates(
    userLandmarks: List<Point>,
    isFrontCamera: Boolean
): List<Point> {
    return userLandmarks.map { point ->
        if (isFrontCamera) {
            Point(1.0f - point.x, point.y)  // 前置镜像
        } else {
            point
        }
    }
}
```

---

## 🎯 Code Review 检查点

- [ ] 变量名包含坐标系前缀？
- [ ] 注释标注坐标系类型？
- [ ] 同一函数内未混用坐标系？
- [ ] 跨坐标系有转换函数？
- [ ] 前置摄像头镜像处理正确？

---

## 📖 完整文档

- [SKILL.md](./SKILL.md) - 完整技能文档
- [COORDINATE_SYSTEM_STANDARD.md](../../docs/COORDINATE_SYSTEM_STANDARD.md) - 规范详细说明
- [ADR-003](../../docs/ADR-003-coordinate-system-management.md) - 技术决策文档

---

**最后更新**: 2026-05-03
