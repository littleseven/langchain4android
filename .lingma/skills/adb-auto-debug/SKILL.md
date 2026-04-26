---
name: adb-auto-debug
description: 通过 adb 命令自动截屏、拉取日志、分析 Android 应用运行状态。Use when needing to capture screenshots, check logcat, monitor app state, or debug visual rendering issues on Android devices without manual user interaction.
---

# ADB 自动调试 Skill

## 截屏分析

### 自动截屏并查看
```bash
# 截屏并拉取到本地
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png /tmp/screen.png
```

### 连续截屏监控
```bash
# 连续截屏3次，间隔2秒
for i in 1 2 3; do
    adb shell screencap -p /sdcard/screen_$i.png
    adb pull /sdcard/screen_$i.png /tmp/screen_$i.png
    sleep 2
done
```

## 日志分析

### 实时过滤日志
```bash
# 过滤特定标签
adb logcat -s PicMe:* *:S

# 过滤多个标签
adb logcat -s PicMe:BeautyRenderer:FaceMakeupPass:* *:S

# 清除后重新捕获
adb logcat -c
adb shell am force-stop com.picme
adb shell am start -n com.picme/.MainActivity
adb logcat -s PicMe:*
```

### 导出日志到文件
```bash
adb logcat -d > /tmp/logcat.txt
grep -i "error\|exception\|failed" /tmp/logcat.txt
```

## 应用状态检查

### 检查应用是否运行
```bash
adb shell pidof com.picme
```

### 强制重启应用
```bash
adb shell am force-stop com.picme
adb shell am start -n com.picme/.MainActivity
```

### 检查 GPU/渲染状态
```bash
adb shell dumpsys gfxinfo com.picme
```

## 文件操作

### 拉取应用数据
```bash
# 拉取 SharedPreferences
adb shell run-as com.picme cat /data/data/com.picme/shared_prefs/*.xml

# 拉取数据库
adb shell run-as com.picme cat /data/data/com.picme/databases/*.db > /tmp/app.db
```

### 推送测试资源
```bash
# 推送图片到设备
adb push test_image.jpg /sdcard/Pictures/
```

## 自动化测试流程

### 完整调试流程
```bash
#!/bin/bash
# 1. 确保应用运行
if ! adb shell pidof com.picme > /dev/null; then
    adb shell am start -n com.picme/.MainActivity
    sleep 3
fi

# 2. 清除日志
adb logcat -c

# 3. 截屏（操作前）
adb shell screencap -p /sdcard/before.png
adb pull /sdcard/before.png /tmp/before.png

# 4. 执行操作（如点击、滑动）
# adb shell input tap x y
# adb shell input swipe x1 y1 x2 y2

# 5. 等待渲染完成
sleep 1

# 6. 截屏（操作后）
adb shell screencap -p /sdcard/after.png
adb pull /sdcard/after.png /tmp/after.png

# 7. 收集日志
adb logcat -d > /tmp/logcat.txt
```

## 渲染问题专项调试

### 检查 OpenGL 错误
```bash
# 过滤 GL 相关日志
adb logcat -d | grep -i "gl_error\|shader\|compile\|link"
```

### 检查 Shader 编译状态
```bash
# 在代码中添加日志后过滤
adb logcat -d | grep -i "shader.*compiled\|program.*linked"
```

### 验证纹理加载
```bash
# 检查纹理加载日志
adb logcat -d | grep -i "texture\|bitmap\|load"
```

## 性能监控

### FPS 监控
```bash
# 使用 gfxinfo 获取帧率
adb shell dumpsys gfxinfo com.picme | grep -i "jank\|frame"
```

### 内存使用
```bash
adb shell dumpsys meminfo com.picme
```

## 常用命令速查

| 命令 | 用途 |
|------|------|
| `adb devices` | 检查设备连接 |
| `adb shell screencap -p /sdcard/screen.png` | 截屏 |
| `adb pull /sdcard/screen.png /tmp/` | 拉取文件 |
| `adb logcat -c` | 清除日志 |
| `adb logcat -s TAG:*` | 过滤日志 |
| `adb shell input tap x y` | 模拟点击 |
| `adb shell input swipe x1 y1 x2 y2` | 模拟滑动 |
| `adb shell am start -n pkg/.Activity` | 启动 Activity |
| `adb shell am force-stop pkg` | 强制停止应用 |
| `adb shell pidof pkg` | 检查进程是否存在 |
