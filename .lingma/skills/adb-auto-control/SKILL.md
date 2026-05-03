---
name: adb-auto-control
description: 通过 adb 命令自动化控制 PicMe 相机应用，执行拍照、切换摄像头、设置美颜参数、切换滤镜等操作。Use when automating camera operations, running UI tests via adb, or controlling the PicMe app programmatically without manual interaction.
---

# PicMe ADB 自动化控制 Skill

基于 CameraTestCommand 架构，通过 adb broadcast 命令远程控制相机界面。

## 前置条件

- 设备已连接：`adb devices`
- 应用已安装：`adb install -r app/build/outputs/apk/debug/picme-debug.apk`
- 应用处于前台（相机界面可见）

## 快速开始

### 启动应用并进入相机
```bash
adb shell am start -n com.picme/.MainActivity
sleep 3
```

### 拍照
```bash
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "capture"
```

### 切换摄像头
```bash
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "flip_camera"
```

## 命令参考

### 基础操作

| 命令 | adb 命令 | 说明 |
|------|----------|------|
| 拍照 | `--es action "capture"` | 触发快门拍照 |
| 切换摄像头 | `--es action "flip_camera"` | 前/后置摄像头切换 |
| 获取状态 | `--es action "get_state"` | 获取当前相机状态 |

### 模式设置

```bash
# 切换拍照模式 (photo/video/pro)
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_mode" --es mode "video"

# 切换场景 (none/night/moon)
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_scene" --es scene "night"

# 切换画幅 (4_3/16_9/full)
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_ratio" --es ratio "16_9"
```

### 美颜设置

```bash
# 设置美颜参数 (0-100)
adb shell am broadcast -a com.picme.TEST_COMMAND \
    --es action "set_beauty" \
    --ei smooth 80 \
    --ei whiten 60 \
    --ei slim_face 50 \
    --ei big_eye 30
```

### 滤镜与风格

```bash
# 设置滤镜
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_filter" --es filter "vivid"

# 设置风格滤镜
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_style" --es style "toon"
```

### 相机参数

```bash
# 设置曝光补偿 (-2 ~ 2)
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_exposure" --ei exposure 1

# 设置缩放
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_zoom" --ef zoom 2.0
```

### 面板控制

```bash
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "toggle_beauty"
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "toggle_filter"
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "toggle_settings"
```

## 自动化脚本

### 完整拍照流程
```bash
#!/bin/bash
# 启动应用
adb shell am start -n com.picme/.MainActivity
sleep 3

# 确保后置摄像头
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "flip_camera"
sleep 1

# 设置美颜
adb shell am broadcast -a com.picme.TEST_COMMAND \
    --es action "set_beauty" --ei smooth 80 --ei whiten 60
sleep 0.5

# 拍照
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "capture"
```

### 批量测试脚本
使用 `scripts/batch_capture.sh` 执行批量拍照测试。

## 验证与调试

### 检查命令是否成功
```bash
# 清除日志后执行命令
adb logcat -c
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "capture"
sleep 1

# 查看 PicMe:CameraTest 标签日志
adb logcat -d | grep "PicMe:CameraTest"
```

### 预期日志输出
```
PicMe:CameraTest: Broadcast received: capture
PicMe:CameraTest: Dispatching command: Capture
PicMe:CameraTest: Command emitted successfully: Capture
PicMe:CameraTest: Executing command: 拍照
```

## 技术说明

- 广播 Action: `com.picme.TEST_COMMAND`
- 动态注册: CameraScreen 通过 DisposableEffect 注册 BroadcastReceiver
- 命令分发: CameraTestCommandDispatcher 使用 SharedFlow 分发命令
- 状态更新: LaunchedEffect 定期更新 CameraTestStateSnapshot

## 故障排除

### 命令无响应
1. 确认应用在前台运行
2. 检查 `adb logcat | grep PicMe:CameraTest` 是否有接收日志
3. 如果显示 `Background execution not allowed`，说明静态接收器被限制，动态接收器应该正常工作

### 状态未更新
- 状态快照在 CameraScreen 的 LaunchedEffect 中更新
- 首次进入相机界面可能需要等待状态初始化

## 相关文件

- [commands.md](commands.md) - 完整命令列表
- [scripts/batch_capture.sh](scripts/batch_capture.sh) - 批量拍照脚本
