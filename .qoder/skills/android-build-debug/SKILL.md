---
name: android-build-debug
description: Android 项目编译、安装、日志调试的标准化流程。
version: 1.1.0
created: 2026-05-03
updated: 2026-05-25
maintainer: [RD] 全栈工程师
tags: [android, gradle, build, debug, apk]
---

# Android 编译调试 Skill

## 标准编译流程

### 1. 编译 Debug APK
```bash
# 在项目根目录执行
./gradlew :app:assembleDebug
```

### 2. 安装到设备
```bash
adb install -r app/build/outputs/apk/debug/picme-debug.apk
```

### 3. 查看日志（过滤 PicMe 标签）
```bash
adb logcat -s PicMe:* *:S
```

### 4. 清除日志后重新启动
```bash
adb logcat -c
adb shell am force-stop com.picme
adb shell am start -n com.picme/.MainActivity
```

## 编译错误排查

### 常见错误处理
1. **Kotlin 编译错误**：检查语法、导入、类型匹配
2. **Shader 编译错误**：检查 GLSL 语法、varying/uniform 声明一致性
3. **资源缺失错误**：检查 assets/、res/ 目录文件是否存在
4. **依赖冲突**：检查 gradle/libs.versions.toml

### 快速修复流程
1. 读取错误日志定位文件和行号
2. 读取相关文件内容
3. 修复错误
4. 重新编译验证

## 运行时调试

### Shader 调试
- 使用纯色输出验证 Shader 执行：`gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0)`
- 检查 uniform 绑定：`Log.d(TAG, "uniform location: $loc")`
- 检查纹理加载：`Log.d(TAG, "texture id: $id, size: ${width}x${height}")`

### OpenGL 调试
- 检查 GL 错误：`GLES20.glGetError()`
- 检查 FBO 状态：`GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)`
- 检查 Shader 编译日志：`GLES20.glGetShaderInfoLog(shaderId)`

## 项目特定路径

- APK 输出：`app/build/outputs/apk/debug/picme-debug.apk`
- Shader 目录：`beauty-engine/src/main/assets/shaders/`
- Java/Kotlin 源码：`app/src/main/java/com/picme/`
