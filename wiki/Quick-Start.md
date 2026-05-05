# 快速开始

本指南帮助你快速配置开发环境并运行 PicMe 项目。

## 📋 环境要求

### 必需软件
- **Android Studio**: Hedgehog (2023.1.1) 或更高版本
- **JDK**: Java 17 (推荐 Adoptium Temurin)
- **Android SDK**: 
  - Compile SDK: 36
  - Min SDK: 24 (Android 7.0)
  - Target SDK: 36
- **Gradle**: 8.5+ (通过 Gradle Wrapper 自动管理)

### 推荐配置
- **操作系统**: macOS 14+ / Windows 11 / Linux (Ubuntu 22.04+)
- **内存**: 16GB RAM (最低 8GB)
- **存储**: 20GB 可用空间
- **设备**: Android 真机 (推荐 Pixel 系列或小米/OPPO/vivo)

---

## 🚀 快速启动

### 1. 克隆仓库

```bash
git clone https://github.com/littleseven/PicMe.git
cd PicMe
```

### 2. 同步 Gradle

在 Android Studio 中打开项目,等待 Gradle 同步完成。或使用命令行:

```bash
./gradlew build --no-daemon
```

### 3. 连接设备

确保已启用 USB 调试:
- 设置 → 开发者选项 → USB 调试 (开启)
- 连接手机到电脑,授权 USB 调试

验证设备连接:
```bash
adb devices
```

### 4. 编译并安装

```bash
# 编译 Debug 包
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/picme-debug.apk
```

或在 Android Studio 中点击 **Run** (▶️) 按钮。

---

## 🛠️ 常用命令

### 构建相关

```bash
# Clean 构建
./gradlew clean

# 编译 Debug 包
./gradlew :app:assembleDebug

# 编译 Release 包
./gradlew :app:assembleRelease

# 仅编译 Kotlin 代码 (快速验证)
./gradlew :app:compileDebugKotlin
```

### 测试相关

```bash
# JVM 单元测试 (无需设备)
./gradlew :app:testDebugUnitTest

# 仪器测试 (需连接设备)
./gradlew :app:connectedDebugAndroidTest

# 运行特定测试类
./gradlew :app:testDebugUnitTest --tests "com.picme.features.camera.FaceDetectorTest"
```

### 代码质量

```bash
# KtLint 检查
./gradlew ktlintCheck

# KtLint 自动修复
./gradlew ktlintFormat

# Detekt 静态分析
./gradlew detekt
```

### 日志查看

```bash
# 实时查看应用日志
adb logcat -s "PicMe:*"

# 清除日志缓冲区
adb logcat -c

# 保存日志到文件
adb logcat -d > picme_log.txt
```

---

## 📱 首次运行

### 权限授予

首次启动时,PicMe 会请求以下权限:
- **相机**: 拍摄照片和视频 (必需)
- **存储**: 保存照片到相册 (可选,拒绝后仅能拍照不能保存)

### 默认设置

- **美颜引擎**: InsightFace 2D106 (NNAPI GPU 加速)
- **检测模式**: Landmark (快速模式,10fps)
- **调试浮层**: 开启 (可在设置页关闭)

### 功能验证

1. **相机预览**: 确认画面流畅,无黑屏
2. **人脸检测**: 观察调试浮层关键点是否贴合人脸
3. **美颜参数**: 调整磨皮/美白/瘦脸滑杆,确认实时生效
4. **拍照**: 按下快门,验证音效/触感/黑场反馈
5. **相册**: 滑动浏览照片,确认 120fps 流畅度

---

## 🔧 故障排查

### 问题 1: Gradle 同步失败

**症状**: `Could not resolve all files for configuration ':app:debugCompileClasspath'`

**解决方案**:
```bash
# 清理 Gradle 缓存
./gradlew cleanBuildCache

# 删除 .gradle 目录
rm -rf .gradle

# 重新同步
./gradlew build --refresh-dependencies
```

### 问题 2: KSP 缓存冲突

**症状**: `Storage for [/path/to/kspCaches] is already registered`

**解决方案**:
```bash
./gradlew clean
./gradlew :app:assembleDebug
```

### 问题 3: NNAPI 不可用

**症状**: 日志显示 `NNAPI not available, falling back to CPU`

**原因**: 设备不支持 NNAPI 或 Android 版本 < 8.1

**影响**: InsightFace 检测速度降低 3-5x,但功能正常

**验证**:
```bash
adb logcat | grep "NNAPI"
```

### 问题 4: 相机黑屏

**症状**: 预览画面全黑,无图像

**排查步骤**:
1. 检查相机权限是否授予
2. 确认其他应用未占用相机
3. 查看日志: `adb logcat -s "PicMe:CameraPreview:*"`
4. 尝试切换前后置摄像头

### 问题 5: 人脸关键点不贴合

**症状**: 调试浮层关键点与人脸位置偏移

**可能原因**:
- 坐标系转换错误
- 镜像翻转未正确处理 (前置摄像头)
- ROI 区域计算偏差

**解决方案**:
1. 确认设备方向传感器正常工作
2. 检查日志中的坐标转换数据
3. 参考 [坐标系统标准](Coordinate-System) 文档

---

## 📚 下一步

- [架构概览](Architecture-Overview) - 了解项目分层设计
- [实时美颜系统](Beauty-Engine) - 深入大美丽引擎技术
- [人脸检测双引擎](Face-Detection-Engines) - InsightFace + MediaPipe 架构详解
- [代码规范](Code-Standards) - Kotlin/Java 编码最佳实践

---

## 💡 提示

### 提升开发效率

1. **使用 Android Studio Profiler**: 实时监控 CPU/GPU/内存
2. **启用 Layout Inspector**: 调试 Compose UI 层级
3. **配置 Run Configurations**: 预设常用启动参数
4. **使用 ADB 快捷方式**: 
   ```bash
   # 截图
   adb exec-out screencap -p > screen.png
   
   # 录制屏幕
   adb shell screenrecord /sdcard/demo.mp4
   adb pull /sdcard/demo.mp4
   ```

### 调试技巧

- **结构化日志**: 所有日志以 `PicMe:[Module]` 格式输出
- **调试浮层**: 设置页开启后,相机预览显示 FPS/耗时/关键点
- **性能监控**: 使用 `adb shell dumpsys gfxinfo com.picme` 查看帧率

---

**遇到问题?** 请在 [GitHub Issues](https://github.com/littleseven/PicMe/issues) 提交问题报告。
