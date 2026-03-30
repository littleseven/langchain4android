# R 计划文档索引

## 📋 文档概览

R 计划（Real-time Beauty Plan）是 PicMe 项目的实时美颜预览技术方案，通过 OpenGL ES 和 CameraX 实现 60fps 的实时美颜效果。

## 📚 文档列表

### 1. [R 计划技术方案](R_PLAN_TECHNICAL_SPEC.md)
**定位**：技术架构和设计规范  
**阅读对象**：RD、CR、新加入的开发者  
**核心内容**：
- 第一性原理分析
- 技术架构设计
- 核心组件职责
- 技术难点与解决方案
- 性能指标
- 实施计划

**何时阅读**：
- 开始实施 R 计划前
- 需要理解整体架构时
- 新开发者加入项目时

### 2. [R 计划实施指南](R_PLAN_IMPLEMENTATION_GUIDE.md)
**定位**：实战指南和调试手册  
**阅读对象**：RD（实施者）  
**核心内容**：
- 当前问题诊断
- 解决方案（A/B/C 方案）
- 调试检查清单
- 常见问题与解决
- 性能调试方法
- 降级策略

**何时阅读**：
- 实施过程中遇到问题
- 需要调试黑屏等问题
- 准备降级到离线美颜时

### 3. [AGENTS.md](../AGENTS.md) - R 计划规范章节
**定位**：项目级规范和标准  
**阅读对象**：所有 AI Agent、开发者  
**核心内容**：
- 4.2 节：R 计划架构规范
  - 核心组件职责
  - EGL 上下文管理
  - SurfaceTexture 生命周期
  - 渲染线程同步
  - 性能指标
  - 调试检查清单
  - 降级策略

**何时阅读**：
- 编写 R 计划相关代码时
- Code Review 时
- 确保代码符合规范时

## 🗺️ 使用指南

### 场景 1：新开发者加入项目
**阅读顺序**：
1. `R_PLAN_TECHNICAL_SPEC.md` - 理解整体架构
2. `AGENTS.md` 4.2 节 - 熟悉规范
3. `R_PLAN_IMPLEMENTATION_GUIDE.md` - 准备实施

### 场景 2：实施 R 计划
**阅读顺序**：
1. `R_PLAN_TECHNICAL_SPEC.md` - 回顾架构
2. `AGENTS.md` 4.2 节 - 遵循规范
3. `R_PLAN_IMPLEMENTATION_GUIDE.md` - 实战指导

### 场景 3：遇到问题（黑屏、崩溃等）
**阅读顺序**：
1. `R_PLAN_IMPLEMENTATION_GUIDE.md` - 诊断问题
2. `R_PLAN_IMPLEMENTATION_GUIDE.md` 3.4 节 - 查看关键日志
3. `R_PLAN_IMPLEMENTATION_GUIDE.md` 4 节 - 查找解决方案

### 场景 4：Code Review
**阅读顺序**：
1. `AGENTS.md` 4.2 节 - 检查规范符合性
2. `R_PLAN_TECHNICAL_SPEC.md` 6 节 - 验证性能指标
3. `R_PLAN_IMPLEMENTATION_GUIDE.md` 3 节 - 确认调试完整性

## 🔑 核心概念速查

### 关键组件
- **BeautyPreviewView**：封装 TextureView 和渲染管线的自定义 View
- **CameraPreviewRenderer**：管理 EGL、SurfaceTexture、渲染线程
- **BeautyRenderer**：执行美颜渲染的 OpenGL 渲染器
- **EGLCore**：手动管理 EGL 上下文（Java 实现）

### 关键技术
- **EGL 上下文共享**：离屏渲染和显示使用共享的 EGL 上下文
- **SurfaceTexture**：CameraX 和 OpenGL 之间的桥梁
- **外部纹理（External Texture）**：`GL_TEXTURE_EXTERNAL_OES` 类型，用于接收相机帧
- **离屏渲染**：使用 1x1 Pbuffer Surface 初始化资源

### 关键流程
```
1. BeautyPreviewView 创建
2. onSurfaceTextureAvailable 回调
3. CameraPreviewRenderer.init()（不启动渲染）
4. CameraX 调用 getSurfaceForCamera()
5. 创建 WindowSurface 并启动渲染线程
6. 相机输出帧 → SurfaceTexture 更新
7. 渲染线程更新纹理 → 渲染 → 显示
```

### 关键日志
```
✅ 必须看到的日志：
D/PicMe:BeautyPreviewView: Surface texture available
D/PicMe:CameraPreview: External texture created: X (X != 0)
D/PicMe:Camera: Creating Surface for CameraX
D/PicMe:Camera: SurfaceProvider called
D/PicMe:Camera: Camera bound
D/PicMe:CameraPreview: Render thread started
D/PicMe:CameraPreview: Rendered X frames

❌ 错误日志（需要修复）：
E/PicMe:CameraPreview: Render error: Unable to update texture contents
E/PicMe:CameraPreview: java.lang.IllegalStateException
```

## 📊 技术架构图

```
┌─────────────────────────────────────────────────┐
│           UI Layer (Jetpack Compose)             │
│  ┌───────────────────────────────────────────┐  │
│  │     BeautyPreviewView (自定义 View)        │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │  TextureView (显示最终渲染结果)      │  │  │
│  │  └─────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
                    ↓ getSurfaceForCamera()
┌─────────────────────────────────────────────────┐
│         Rendering Layer (OpenGL ES)              │
│  ┌───────────────────────────────────────────┐  │
│  │   CameraPreviewRenderer                   │  │
│  │   ├─ EGLCore (EGL 管理)                    │  │
│  │   ├─ BeautyRenderer (美颜渲染)            │  │
│  │   ├─ ShaderProgram (Shader 管理)          │  │
│  │   └─ WindowSurface (渲染目标)             │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
                    ↓ SurfaceTexture
┌─────────────────────────────────────────────────┐
│          Camera Layer (CameraX)                  │
│  ┌───────────────────────────────────────────┐  │
│  │   Preview UseCase                         │  │
│  │   └─ SurfaceProvider → Surface            │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

## 🎯 性能指标

| 指标 | 目标值 | 当前状态 |
|------|--------|----------|
| 启动时间 | < 500ms | 🟡 待验证 |
| 渲染延迟 | < 16ms | 🟡 待验证 |
| 帧率 | 60fps | 🟡 待验证 |
| 内存占用 | < 50MB | 🟡 待验证 |
| 纹理 ID | 非 0 值 | 🔴 当前为 0 |

## 🚧 当前状态

### 已完成
- ✅ EGLCore 实现
- ✅ ShaderProgram 管理
- ✅ BeautyRenderer 基础渲染
- ✅ BeautyPreviewView 封装
- ✅ CameraPreviewRenderer 集成
- ✅ 文档体系建立

### 进行中
- 🟡 CameraX 集成调试
- 🟡 SurfaceTexture 同步
- 🟡 黑屏问题修复

### 待完成
- ⚪ 美颜算法优化
- ⚪ 性能基准测试
- ⚪ 兼容性测试

## 💡 快速解决方案

### 问题：黑屏
**检查步骤**：
1. 查看是否有 "Camera bound" 日志
2. 检查 External texture ID 是否为 0
3. 验证 `getSurfaceForCamera()` 是否被调用
4. 确认 SurfaceProvider 的回调执行

**快速修复**：
```kotlin
// 在 BeautyPreviewView 中
fun getSurfaceForCamera(): Surface? {
    val st = renderer.getSurfaceTexture()
    if (st == null) return null
    
    if (!surfaceCreated) {
        surfaceCreated = true
        st.setDefaultBufferSize(1920, 1080)  // 关键！
        renderer.setRenderSurface(Surface(st))
    }
    return Surface(st)
}
```

### 问题：updateTexImage() 失败
**原因**：渲染线程启动太早

**解决**：
- 延迟启动渲染线程
- 在 `getSurfaceForCamera()` 中首次调用时才启动
- 添加错误恢复机制（重试）

### 问题：纹理 ID 为 0
**原因**：纹理创建失败

**解决**：
```kotlin
fun createExternalTexture() {
    val textureIds = IntArray(1)
    GLES20.glGenTextures(1, textureIds, 0)
    textureId = textureIds[0]
    
    if (textureId == 0) {
        Log.e(TAG, "Failed to create texture!")
        return
    }
    // ... 绑定纹理
}
```

## 🔗 相关资源

### 官方文档
- [OpenGL ES 编程指南](https://developer.android.com/guide/topics/graphics/opengl)
- [CameraX 架构](https://developer.android.com/training/camerax/architecture)
- [SurfaceTexture 详解](https://source.android.com/devices/graphics/arch-st)

### 开源项目
- [GPUImage](https://github.com/cyberagent/android-gpuimage)
- [CameraX 示例](https://github.com/android/camera-samples)

### 技术文章
- [Android 图形系统架构](https://source.android.com/devices/graphics)
- [OpenGL ES 最佳实践](https://developer.arm.com/solutions/graphics-and-gaming/arm-mali-gpu-training)

## 📝 更新日志

### 2026-03-29
- 创建 R 计划技术方案文档
- 创建 R 计划实施指南文档
- 更新 AGENTS.md 添加 R 计划规范
- 创建文档索引

---

**最后更新**：2026-03-29  
**维护者**：[RD] 全栈工程师  
**审核者**：[CR] 规范守护者
