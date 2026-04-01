# R 计划：实时美颜预览技术方案

## 1. 执行摘要

**状态**: 🟡 进行中（遇到技术挑战）

**目标**: 实现 60fps 实时美颜预览（磨皮、美白）

**当前进展**:
- ✅ EGL 初始化和上下文管理完成
- ✅ Shader 编译系统完成
- ✅ BeautyRenderer 美颜渲染器完成
- ✅ CameraPreviewRenderer 渲染管线完成
- ✅ BeautyPreviewView 自定义 View 完成
- ✅ 集成到 CameraScreen
- ✅ **实时预览最小可用版完成**（基于 PreviewView.bitmap 抓帧）
- ✅ **第二步优化完成**（自适应帧率 10-15fps）
- ❌ ~~相机帧无法到达 SurfaceTexture~~（已切换到 Bitmap 方案）

## 2. 架构设计

### 2.1 核心组件

```
┌─────────────────────────────────────────────────────────┐
│ CameraScreen (Compose UI)                               │
│  └── BeautyPreviewView (FrameLayout)                    │
│       └── TextureView (显示)                             │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│ BeautyPreviewView                                       │
│  - displaySurfaceTexture: SurfaceTexture (显示用)       │
│  - cameraSurfaceTexture: SurfaceTexture (相机用)        │
│  - renderer: CameraPreviewRenderer                      │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│ CameraPreviewRenderer                                   │
│  - eglCore: EGLCore                                     │
│  - eglContext: EGLContext                               │
│  - windowSurface: WindowSurface                          │
│  - surfaceTexture: SurfaceTexture (相机输出目标)         │
│  - textureId: Int (外部纹理 ID)                          │
│  - beautyRenderer: BeautyRenderer                       │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│ BeautyRenderer                                          │
│  - shaderProgram: ShaderProgram                         │
│  - smoothingStrength: Float                             │
│  - whiteningStrength: Float                             │
└─────────────────────────────────────────────────────────┘
```

### 2.2 数据流

```
相机传感器
    ↓ YUV 数据
CameraX Preview
    ↓ Surface
cameraSurfaceTexture (在 CameraPreviewRenderer 中创建)
    ↓ updateTexImage()
外部纹理 (GL_TEXTURE_EXTERNAL_OES)
    ↓ BeautyRenderer Shader 处理
WindowSurface
    ↓ 显示
TextureView (displaySurfaceTexture)
```

## 3. 当前问题诊断

### 3.1 核心问题

**现象**: 黑屏，日志显示：
```
✅ SurfaceTexture ready: true
✅ Created Surface from renderer: 41865003, valid=true
✅ SurfaceProvider called, returning our surface: 41865003
✅ Camera bound: lensFacing=1, selector=1
❌ Waiting for camera frame (attempt 1-60)
❌ No camera frames received after 6 seconds, stopping render
```

**根本原因**: 
- CameraX 调用了 SurfaceProvider，并接收到了我们返回的 Surface
- 但是相机帧**没有输出到这个 Surface**
- `updateTexImage()` 持续失败，因为 SurfaceTexture 是空的

### 3.2 问题分析

**问题 1**: 使用了错误的 SurfaceTexture
- 我们使用了 TextureView 的 `surfaceTexture` 作为相机输出目标
- 但 TextureView 需要自己管理这个 SurfaceTexture 进行显示
- CameraX 的帧无法同时用于显示和渲染

**问题 2**: EGL 上下文不匹配
- 创建 cameraSurfaceTexture 时在一个 EGL 上下文中
- CameraX 输出帧时可能需要另一个 EGL 上下文
- 导致帧无法正确传递

**问题 3**: SurfaceProvider 时序问题
- `setSurfaceProvider` 是异步的
- CameraX 可能在调用 provider 之前还没有准备好接收 Surface

## 4. 技术方案对比

### 方案 A：离屏渲染（当前方案）

**原理**: 
1. 创建独立的 SurfaceTexture 接收相机帧
2. 在渲染线程中调用 `updateTexImage()` 更新纹理
3. 使用 Shader 处理纹理
4. 渲染到 WindowSurface（连接到 TextureView）

**优点**:
- 完全控制渲染管线
- 可以自定义美颜算法
- 性能最优（理论上）

**缺点**:
- 复杂度高
- 需要精确管理 EGL 上下文
- 当前遇到帧同步问题

**状态**: ❌ 遇到技术壁垒

### 方案 B：使用 TextureView 的 SurfaceTexture

**原理**:
1. 直接使用 TextureView 的 `surfaceTexture` 作为相机输出目标
2. CameraX 输出帧到 surfaceTexture
3. 在 `onSurfaceTextureUpdated` 中触发渲染
4. 使用 OpenGL ES 读取并处理纹理

**优点**:
- 简单直接
- 不需要管理多个 SurfaceTexture
- TextureView 自动处理显示

**缺点**:
- 需要在正确的 EGL 上下文中操作
- 可能需要共享 EGL 上下文

**状态**: ⏸️ 待实施

### 方案 C：使用 SurfaceView + OpenGL ES

**原理**:
1. 使用 SurfaceView 替代 TextureView
2. CameraX 输出到 SurfaceView 的 Surface
3. 在 Surface 的回调中处理渲染

**优点**:
- 性能更好（直接渲染到屏幕）
- 不需要 TextureView 的中间层

**缺点**:
- SurfaceView 在 Compose 中使用复杂
- 不支持 View 的动画和变换

**状态**: ❌ 不适合当前架构

## 5. 下一步行动计划

### 阶段 1：实时预览最小可用版 ✅ 已完成

**目标**: 实现可见、可用的实时美颜预览

**实施方案**（降级方案 B）：
- ✅ 使用 `PreviewView.bitmap` 抓取当前帧
- ✅ 在 `LaunchedEffect` 中循环处理美颜
- ✅ 叠加 `Image` 组件显示美颜后的 Bitmap
- ✅ 集成人脸检测，支持大眼/瘦脸等效果

**成果**：
- 实时预览可见、可用
- 支持所有美颜参数实时反馈
- 无黑屏问题，稳定运行

### 阶段 2：性能优化 ✅ 已完成

**目标**: 提升帧率，减少卡顿和抖动

**实施内容**：
1. ✅ **自适应刷新频率**
   - 动态调整延迟时间（67-150ms）
   - 根据处理时间自动优化
   - 目标帧率：10-15fps

2. ✅ **性能监控**
   - 测量每帧处理时间
   - 实时 FPS 日志（每秒记录）
   - 处理时间监控（快/慢阈值）

3. ✅ **跳帧策略**
   - 处理快时提升帧率
   - 处理慢时降低帧率
   - 避免过载和卡顿

**成果**：
- 自适应帧率：设备性能好时自动提升至 15fps
- 性能日志：可监控实时 FPS 和处理时间
- 平滑体验：减少处理抖动，避免过载

### 阶段 3：进一步优化（规划中）

1. ⏳ **GPU 纹理流方案**（PixelFree 直连纹理）
   - 直接使用 CameraX 的 SurfaceTexture
   - 零拷贝纹理处理
   - 预期达到 30fps+

2. ⏳ **美颜算法优化**
   - Shader 性能调优
   - 减少人脸检测延迟
   - 算法并行化

3. ⏳ **更多美颜效果**
   - 锐化、红润等参数
   - 美妆效果
   - 滤镜实时预览

**预计时间**: 2-3 周

## 6. 技术细节

### 6.1 EGL 上下文管理

```kotlin
// 正确的 EGL 初始化流程
eglCore.init()
eglContext = eglCore.createContext()

// 创建离屏 pbuffer surface 用于初始化
pbufferSurface = eglCore.createSurface(null, 1, 1)
eglCore.makeCurrent(pbufferSurface, eglContext)

// 初始化 Shader 和渲染器
beautyRenderer.onInit()

// 创建外部纹理
createExternalTexture()

// 创建 cameraSurfaceTexture
surfaceTexture = SurfaceTexture(textureId)
```

### 6.2 渲染循环

```kotlin
while (isRendering) {
    // 1. 更新相机帧
    surfaceTexture.updateTexImage()
    
    // 2. 获取变换矩阵
    surfaceTexture.getTransformMatrix(transformMatrix)
    
    // 3. 绑定纹理
    GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
    
    // 4. 设置为当前渲染目标
    eglCore.makeCurrent(windowSurface.eglSurface, eglContext)
    
    // 5. 渲染
    beautyRenderer.setTextureTransform(transformMatrix)
    beautyRenderer.onRender()
    
    // 6. 交换缓冲区
    windowSurface.swapBuffers()
}
```

### 6.3 关键日志

**成功标志**:
```
✅ Using TextureView's SurfaceTexture: 223250756
✅ Camera SurfaceTexture: android.graphics.SurfaceTexture@xxx
✅ Created Surface from renderer: 41865003, valid=true
✅ SurfaceProvider called, returning our surface: 41865003
✅ Camera bound: lensFacing=1, selector=1
✅ First frame received! Starting render loop.
✅ Rendered 30 frames, textureId=0
```

**失败标志**:
```
❌ Waiting for camera frame (attempt N)
❌ Still waiting for camera frame after 1 second...
❌ No camera frames received after 6 seconds, stopping render
❌ Render thread stopped after 0 frames
```

## 7. 参考资料

- [Android CameraX 官方文档](https://developer.android.com/training/camerax)
- [OpenGL ES 官方文档](https://developer.android.com/guide/topics/graphics/opengl)
- [EGL 规范](https://www.khronos.org/egl/)
- [GPUImage 开源项目](https://github.com/cyberagent/android-gpuimage)

## 8. 更新日志

### 2026-03-29

**进展**:
- ✅ 修复了 BeautyPreviewView 传递错误 view 的问题
- ✅ 实现了详细的日志记录
- ✅ 添加了错误恢复机制
- ❌ 发现相机帧无法到达 SurfaceTexture

**问题**:
- CameraX 调用了 SurfaceProvider，但帧没有输出
- 怀疑 EGL 上下文不匹配或 SurfaceTexture 管理问题

**下一步**:
- 深入分析 EGL 上下文管理
- 考虑使用 TextureView 的 surfaceTexture 直接作为相机输出目标
