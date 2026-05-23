---
name: image-quality-checker
description: 通过 adb 截屏并分析图片质量，检测黑屏、人脸位置、调试信息是否正常。Use when verifying camera preview quality, checking for black screen issues, validating face detection overlay, or debugging rendering output in the PicMe app.
---

# 图片质量验证 Skill

通过 adb 截屏并结合 Python 脚本自动分析画面质量，诊断渲染问题。

## 快速开始

### 1. 自动化拍照质量检查（推荐）
```bash
# 自动触发拍照、拉取照片、分析质量
cd /Users/guoshuai/AndroidStudioProjects/PicMe
.lingma/skills/image-quality-checker/scripts/auto_check_photo.sh
```

### 2. 截屏并分析预览画面
```bash
cd /Users/guoshuai/AndroidStudioProjects/PicMe/.lingma/skills/image-quality-checker/scripts
./check_quality.sh
```

### 3. 分析指定图片
```bash
python3 scripts/analyze_image.py /path/to/screenshot.png
```

## 功能说明

### 自动检测项
| 检测项 | 说明 | 判定标准 |
|--------|------|----------|
| **黑屏检测** | 检查画面是否全黑或接近全黑 | 平均亮度 < 5/255 |
| **基本信息** | 分辨率、色彩模式、文件大小 | - |
| **人脸检测** | 使用 ML Kit/InsightFace 检测人脸 | 至少检测到 1 张人脸 |
| **人脸位置** | 输出人脸关键点坐标及边界框 | 关键点在合理范围内 |
| **调试信息** | 检测画面中的文字覆盖（FPS、日志） | 文字清晰可读 |

## 使用场景

### 场曯1: 代码修改后自动验证拍照质量（推荐）
```bash
# 每次修改代码后执行，自动完成：
# 1. 触发拍照
# 2. 拉取最新照片
# 3. 分析黑屏、人脸、亮度等
# 4. 检查 GPU 日志
# 5. 生成报告

.lingma/skills/image-quality-checker/scripts/auto_check_photo.sh
```

**输出示例**：
```
==================================================
=== 图片质量分析报告 ===
==================================================

文件: photo_20260503_132620.jpg
分辨率: 2000x3000
色彩模式: RGB
文件大小: 2.44 MB

[✓ 正常] 黑屏检测: 正常 (平均亮度: 75.8/255)
[✓ 检测到] 人脸检测: 检测到画面内容
[○ 未检测到] 调试信息: 无调试信息

==================================================
结论: 画面质量合格
==================================================

GPU 拍照日志摘要:
==================================================
D PicMe:PhotoProcessor: process DONE: elapsed=181ms
D PicMe:ImageProcessor: GPU photo processing succeeded

✅ 检查完成！
```

### 场曯2: 验证 GPU 拍照是否黑屏
```bash
# 1. 触发拍照
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "capture"
sleep 2

# 2. 拉取相册最新照片
adb shell ls /sdcard/DCIM/PicMe/ | tail -1
adb pull /sdcard/DCIM/PicMe/latest_photo.jpg ./output.jpg

# 3. 分析
python3 scripts/analyze_image.py output.jpg
```

### 场景2: 检查预览画面人脸检测
```bash
# 1. 截屏
adb shell screencap -p /sdcard/preview.png
adb pull /sdcard/preview.png ./preview.png

# 2. 分析
python3 scripts/analyze_image.py preview.png --detect-face
```

### 场景3: 批量验证多张照片
```bash
for img in photos/*.jpg; do
    python3 scripts/analyze_image.py "$img" >> report.txt
done
```

## 输出示例

```
=== 图片质量分析报告 ===
文件: preview.png
分辨率: 1080x2400
色彩模式: RGBA
文件大小: 2.3 MB

[✓] 黑屏检测: 正常 (平均亮度: 128/255)
[✓] 人脸检测: 检测到 1 张人脸
    - 边界框: (320, 450) - (760, 890)
    - 置信度: 0.98
    - 关键点数量: 106
[✓] 调试信息: 检测到 FPS 覆盖 (位置: 左上角)

结论: 画面质量合格
```

## 故障排除

### 检测到黑屏
- 检查 `adb logcat | grep PicMe:PhotoProcessor` 是否有 GL 错误
- 验证 FBO 状态：`glCheckFramebufferStatus`
- 确认 EGL 上下文是否正确绑定

### 未检测到人脸
- 确认光线充足，人脸正对摄像头
- 检查人脸检测引擎设置（InsightFace vs MediaPipe）
- 查看日志：`adb logcat | grep FaceDetector`

### 调试信息模糊
- 检查 Canvas 绘制时的抗锯齿设置
- 确认文字颜色与背景对比度足够

## 技术实现

- **截屏**: `adb shell screencap -p`
- **分析**: Python + Pillow (图像处理) + OpenCV (人脸检测可选)
- **人脸检测**: 可集成项目现有的 InsightFace 或 MediaPipe 模型

## 相关文件

- [scripts/auto_check_photo.sh](scripts/auto_check_photo.sh) - 自动化拍照质量检查脚本（推荐）
- [scripts/analyze_image.py](scripts/analyze_image.py) - Python 分析脚本
- [scripts/check_quality.sh](scripts/check_quality.sh) - 截屏验证流程
