# 架构决策记录 (ADR)

本文档记录 PicMe 项目的关键技术决策及其理由。

## 📋 ADR 列表

### ADR-001: 大美丽单引擎架构

**状态**: ✅ 已接受  
**日期**: 2026-04  
**影响范围**: `beauty-engine/egl/`, App 层依赖

**决策**: 移除 GPUPixel,仅保留自研 OpenGL ES 引擎

**理由**:
- ✅ 完全自主可控,无商业 SDK 依赖
- ✅ 代码量减少 40%,维护成本降低
- ✅ 渲染效果一致性提升 (预览/拍照同源 Shader)
- ❌ 初期开发成本高 (已克服)

**后果**:
- GPUPixel 相关代码全部清理
- App 层仅依赖 `beauty-engine:api`
- 容灾降级展示无美颜原生预览

详见: [ADR-001](../docs/ADR-001-beauty-engine-architecture.md)

---

### ADR-002: OpenGL 离屏渲染统一管线

**状态**: ✅ 已接受  
**日期**: 2026-05  
**影响范围**: `beauty-engine/egl/OffscreenRenderer.kt`

**决策**: 预览与拍照使用同一套 OpenGL Shader

**理由**:
- ✅ 预览/拍照效果一致性从 70-85% 提升至 99%+
- ✅ 代码复用率提升,避免重复实现
- ✅ 性能优化: 1080p 处理 < 300ms (CPU 路径 800-1200ms)

**实现**:
- 预览: `SurfaceTexture → OpenGL ES → SurfaceView`
- 拍照: `Bitmap → EGL Pbuffer → OpenGL ES → Bitmap`

**降级策略**:
- GPU 离屏渲染失败时 (EGL 上下文创建失败/OOM)
- 自动回退到现有 CPU Canvas 路径
- 确保拍照不失败

详见: [ADR-002](../docs/ADR-002-opengl-offscreen-unified-pipeline.md)

---

### ADR-003: 坐标系统标准

**状态**: ✅ 已接受  
**日期**: 2026-04  
**影响范围**: 全项目 (人脸检测、渲染引擎、UI 展示)

**决策**: 统一使用归一化坐标 [0,1],明确左右命名规范

**理由**:
- ✅ 避免坐标系混用导致的错位问题
- ✅ 前置摄像头镜像翻转逻辑清晰
- ✅ 跨平台移植友好 (iOS/Web)

**规范**:
- **OpenGL NDC**: [-1,1],Y 轴向上
- **图像像素坐标**: [0,width]×[0,height],Y 轴向下
- **归一化坐标**: [0,1],Y 轴向下
- **左右命名**: 以人物视角为准 (非屏幕视角)

**坐标转换公式**:
```kotlin
// 像素坐标 → 归一化坐标
val normalizedX = pixelX / imageWidth
val normalizedY = pixelY / imageHeight

// 归一化坐标 → OpenGL NDC
val ndcX = normalizedX * 2.0f - 1.0f
val ndcY = -(normalizedY * 2.0f - 1.0f)  // Y 轴翻转

// 前置摄像头镜像翻转
if (isFrontCamera) {
    normalizedX = 1.0f - normalizedX
}
```

详见: [ADR-003](../docs/ADR-003-coordinate-system-management.md)

---

## 🔄 决策流程

1. **提出问题**: 在 GitHub Issues 中描述技术难题
2. **方案对比**: 列出至少 2 个备选方案,分析优缺点
3. **团队讨论**: RD Team 内部评审,PM/QA 参与意见
4. **决策记录**: 编写 ADR 文档,明确选择理由
5. **实施验证**: 编码实现并通过 QA 验收
6. **归档维护**: 将 ADR 纳入版本管理,定期回顾

## 📊 决策状态

- ✅ **已接受**: 已实施并验证通过
- ⏸️ **进行中**: 正在实施,未完成
- ❌ **已拒绝**: 经过评估后放弃
- 🔄 **已废弃**: 曾经实施,后被新方案替代

---

**最后更新**: 2026-05-05  
**维护者**: PicMe RD Team
