# 🎨 音视频与 OpenGL 专家 (AV-GL Expert)

## 📋 Skill 概述

**AV-GL Expert** 是 PicMe 项目专用的音视频处理与 OpenGL ES 渲染专家级调试工具，提供黑屏诊断、性能分析、Shader 调试、CameraX 集成等全方位支持。

---

## 🚀 快速开始

### 1. 黑屏问题诊断

```bash
# 查看实时 GL 错误日志
.lingma/skills/av-gl-expert/scripts/check-gl-errors.sh

# 或在特定设备上
.lingma/skills/av-gl-expert/scripts/check-gl-errors.sh <device_id>
```

### 2. 性能分析

```bash
# 捕获 10-20 秒的渲染性能数据
python3 .lingma/skills/av-gl-expert/scripts/profile-render-performance.py

# 生成详细报告（FPS、渲染耗时、空帧率）
```

### 3. 查阅完整文档

```bash
code .lingma/skills/av-gl-expert/SKILL.md
```

---

## 🛠️ 核心功能

### 1. OpenGL 黑屏诊断

**常见问题**：
- EGL 上下文创建失败
- Shader 编译/链接错误
- 纹理绑定不正确
- FBO 不完整
- Viewport 设置错误

**诊断步骤**（详见 SKILL.md Section 1）：
1. 检查 EGL 上下文状态
2. 验证 Shader 编译日志
3. 确认纹理绑定正确
4. 检查 FBO 完整性
5. 验证 Viewport 设置

### 2. Shader 调试工具集

**内置调试 Shader**：
- 🔴 **红色测试**: 验证渲染链路是否正常
- 🌫️ **R 通道灰度**: 检查纹理加载
- 🌈 **UV 坐标可视化**: 验证纹理映射
- 📊 **Uniform 值打印**: 确认参数传递

**使用方法**（详见 SKILL.md Section 2）：
```kotlin
// 在 BeautyRenderer.kt 中切换调试 Shader
shaderProgram.useDebugShader(DebugShader.RED)
```

### 3. 性能分析与优化

**监控指标**：
- 🎯 FPS（目标 ≥ 55）
- ⏱️ 单帧渲染耗时（目标 < 16.67ms）
- 🖼️ 空帧率（目标 < 5%）
- 💾 纹理/FBO 内存占用

**优化技巧**（详见 SKILL.md Section 3）：
- FBO 复用（避免每帧创建/销毁）
- PBO 异步读取（拍照路径提速 3x）
- 纹理内存池（减少 GC 压力）
- Shader 预编译（避免运行时编译）

### 4. CameraX 集成调试

**常见问题**：
- Preview Surface 绑定失败
- ImageAnalysis YUV 数据流中断
- 前后置摄像头切换卡顿
- 画幅切换闪烁

**解决方案**（详见 SKILL.md Section 4）：
- 正确的初始化顺序
- YUV→RGBA 高效转换
- 双 Surface 预初始化
- 旋转角度正确处理

### 5. 人脸关键点坐标映射

**转换流程**（详见 SKILL.md Section 5）：
```
MediaPipe 468 点 → 106 点语义映射 → 旋转校正 → 
归一化 → 镜像翻转 → 屏幕坐标 → UV 映射
```

**调试可视化**：
- 绘制 106 个关键点（不同颜色区分区域）
- 显示索引号（便于对照映射表）
- 实时显示检测来源（MediaPipe/InsightFace/GPUPixel）

---

## 📂 文件结构

```
av-gl-expert/
├── SKILL.md                              # 完整技术文档 (1042 行)
├── README.md                             # 本文件（快速参考）
└── scripts/
    ├── check-gl-errors.sh                # GL 错误实时监控
    └── profile-render-performance.py     # 渲染性能分析
```

---

## 🎯 使用场景示例

### 场景 1: 预览黑屏

```bash
# Step 1: 捕获 GL 错误
.lingma/skills/av-gl-expert/scripts/check-gl-errors.sh

# Step 2: 根据错误类型查阅 SKILL.md
# - EGL 错误 → Section 1, Step 1
# - Shader 编译失败 → Section 1, Step 2
# - 纹理绑定错误 → Section 1, Step 3

# Step 3: 应用修复方案
# Step 4: 重新测试
```

### 场景 2: FPS 低于预期

```bash
# Step 1: 性能分析
python3 .lingma/skills/av-gl-expert/scripts/profile-render-performance.py

# Step 2: 查看报告中的瓶颈
# - FPS 统计
# - 渲染耗时分解
# - 空帧率分析

# Step 3: 根据建议优化
# - FBO 复用
# - PBO 异步读取
# - 降低检测频率

# Step 4: 重新分析验证
```

### 场景 3: 人脸关键点偏移

```bash
# Step 1: 启用调试浮层
# 在设置页开启"显示人脸调试"

# Step 2: 观察关键点位置
# - 是否正确贴合人脸
# - 是否随旋转/镜像正确变换

# Step 3: 查阅坐标映射章节
# SKILL.md Section 5: Landmark Coordinate Mapping

# Step 4: 检查各阶段转换
# - 归一化是否正确
# - 旋转角度是否应用
# - 前置摄像头是否镜像
# - Viewport 计算是否准确
```

---

## 📊 性能标准

| 指标 | 优秀 | 合格 | 不合格 |
|------|------|------|--------|
| **FPS** | ≥ 55 | 45-54 | < 45 |
| **单帧渲染耗时** | < 12ms | 12-16ms | > 16ms |
| **空帧率** | < 5% | 5-15% | > 15% |
| **拍照后处理 (1080p)** | < 200ms | 200-300ms | > 300ms |
| **摄像头切换** | < 150ms | 150-300ms | > 300ms |

---

## 🔗 相关文档

### 内部技术文档
- [CAMERA_PREVIEW_TECH_SPEC.md](../../docs/CAMERA_PREVIEW_TECH_SPEC.md) - 相机预览技术规格
- [BIG_BEAUTY_TECH_SPEC.md](../../docs/BIG_BEAUTY_TECH_SPEC.md) - 大美丽引擎技术规格
- [ADR-002-opengl-offscreen-unified-pipeline.md](../../docs/ADR-002-opengl-offscreen-unified-pipeline.md) - 离屏渲染架构决策
- [BEAUTY_ENGINE_FALLBACK.md](../../docs/BEAUTY_ENGINE_FALLBACK.md) - 引擎容灾降级策略

### 外部资源
- [OpenGL ES 2.0 Reference](https://www.khronos.org/opengles/sdk/docs/man/)
- [Android CameraX Guide](https://developer.android.com/training/camerax)
- [EGL 1.4 Specification](https://www.khronos.org/registry/EGL/specs/eglspec.1.4.pdf)

---

## 🆘 常见问题

### Q1: 如何判断是 OpenGL 问题还是 CameraX 问题？

**A**: 
- **OpenGL 问题特征**: 黑屏、花屏、Shader 编译错误、GL Error
- **CameraX 问题特征**: Surface 绑定失败、ImageProxy 关闭过早、分辨率不匹配

**诊断方法**:
```bash
# 检查 GL 错误
.lingma/skills/av-gl-expert/scripts/check-gl-errors.sh

# 检查 CameraX 日志
adb logcat | grep "CameraX\|ImageAnalysis"
```

### Q2: 如何在真机上调试 Shader？

**A**: 
1. 使用调试 Shader（红色/R通道/UV可视化）
2. 添加 Uniform 值打印日志
3. 使用 RenderDoc 或 Snapdragon Profiler（需要 root）
4. 截图分析（`adb exec-out screencap -p > screen.png`）

### Q3: 拍照后处理为什么比预览慢？

**A**: 
- 预览：GPU 实时渲染，直接输出到 Surface
- 拍照：需要 FBO → PBO → Bitmap，涉及 CPU-GPU 数据传输

**优化方案**:
- 启用 PBO 异步读取（提速 3x）
- 减小处理分辨率（先缩放再处理）
- 复用 FBO 和纹理资源

### Q4: 前后置摄像头切换为什么会卡顿？

**A**: 
- 原因：需要重新初始化 EGL 上下文和 Surface

**优化方案**:
- 预初始化双摄像头 Surface
- 共享 EGL 上下文
- 异步加载资源

---

## 🎓 学习路径

1. **入门**（1-2 天）
   - 阅读 SKILL.md Section 1-2（黑屏诊断 + Shader 调试）
   - 运行 `check-gl-errors.sh` 熟悉日志格式
   - 尝试切换调试 Shader

2. **进阶**（3-5 天）
   - 阅读 SKILL.md Section 3（性能优化）
   - 运行 `profile-render-performance.py` 分析瓶颈
   - 应用 FBO 复用和 PBO 优化

3. **专家**（1-2 周）
   - 阅读 SKILL.md Section 4-5（CameraX 集成 + 坐标映射）
   - 深入理解 EGL 上下文管理
   - 掌握多 Pass 渲染管线设计

---

## 📝 贡献指南

欢迎提交改进建议或 Bug 修复：

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/amazing-debug-tool`)
3. 提交更改 (`git commit -m 'Add amazing debug tool'`)
4. 推送到分支 (`git push origin feature/amazing-debug-tool`)
5. 开启 Pull Request

---

**Skill 版本**: 1.0  
**创建日期**: 2026-05-03  
**维护者**: [RD] 全栈工程师 + [CR] 规范守护者  
**适用范围**: PicMe 项目音视频与 OpenGL 相关开发
