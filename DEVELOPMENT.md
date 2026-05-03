# PicMe 开发指南

> **定位**：面向所有开发者（人类与 AI）的通用开发参考，包含环境配置、构建命令、调试技巧与发布流程。  
> **AI 工具配置**：各 AI 辅助工具的配置位置见 `AI_TOOLS.md`。

## 开发环境

### 必需工具
- **Android Studio**: 最新稳定版 (Ladybug 2024.3+)
- **JDK**: 17 (Android Studio 内置)
- **Android SDK**: API 24-35
- **Git**: 版本控制

### 可选工具
- **scrcpy**: 屏幕镜像 (`brew install scrcpy`)
- **ADB**: Android 调试桥 (`brew install android-platform-tools`)

## 快速开始

```bash
# 克隆项目
git clone <repo-url>
cd PicMe

# 构建调试版本
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# 查看 PicMe 日志
adb logcat -s "PicMe:*"
```

## 项目构建

### 常用 Gradle 命令

```bash
# 构建调试 APK
./gradlew :app:assembleDebug

# 构建发布 APK
./gradlew :app:assembleRelease

# 运行单元测试
./gradlew test

# 运行仪器测试
./gradlew connectedAndroidTest

# 清理构建
./gradlew clean

# 刷新依赖
./gradlew --refresh-dependencies

# Lint 检查
./gradlew lint
```

### 构建变体
- `debug`: 开发调试版本
- `release`: 发布版本（需要签名配置）

### Kotlin 代码风格
- 使用项目配置的 `.editorconfig`
- 缩进: 4 空格 (Kotlin/Java)
- 缩进: 2 空格 (XML/JSON/MD)

## 调试工具

### ADB 常用命令

```bash
# 查看设备
adb devices

# 安装 APK
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# 查看 PicMe 日志
adb logcat -s PicMe:*

# 只看错误
adb logcat *:E

# 清除日志
adb logcat -c

# 进入 shell
adb shell

# 截图
adb shell screencap /sdcard/screen.png && adb pull /sdcard/screen.png

# 启动耗时测量
adb shell am start -W com.picme/.MainActivity
```

## IDE 快捷键

### Android Studio (macOS)
- `Cmd + Shift + A`: 查找动作
- `Cmd + O`: 查找类
- `Cmd + Shift + O`: 查找文件
- `Cmd + L`: 跳转到行
- `Cmd + B`: 跳转到定义
- `Option + Cmd + L`: 格式化代码
- `Ctrl + Shift + L`: 打开灵码 (Lingma)

## 模拟器/真机调试

### 创建模拟器
1. Android Studio → Device Manager
2. Create Device
3. 选择 Pixel 系列
4. 下载系统镜像 (推荐 API 33+)

### 真机调试
1. 开启开发者选项（设置 → 关于手机 → 点击版本号 7 次）
2. 开启 USB 调试
3. 连接 USB，允许调试授权

## 性能分析

### Android Profiler
- CPU: 方法追踪
- Memory: 内存分配
- Network: 网络请求
- Energy: 电量消耗

## 版本发布

### 版本号规则
- `versionCode`: 整数，每次发布递增
- `versionName`: 语义化版本 (如 1.2.3)

### 签名配置
```kotlin
android {
    signingConfigs {
        release {
            storeFile file("picme.keystore")
            storePassword "..."
            keyAlias "..."
            keyPassword "..."
        }
    }
}
```

## kimi-cli 快捷入口

```bash
# 进入项目目录并启动 kimi-cli
cd ~/AndroidStudioProjects/PicMe
kimi-cli chat

# 或使用项目脚本
source scripts/kimi-cli.sh
kbuild    # 构建调试 APK
ktest     # 运行单元测试
klogs     # 查看 PicMe 日志
```

---

*项目路径: ~/AndroidStudioProjects/PicMe*
