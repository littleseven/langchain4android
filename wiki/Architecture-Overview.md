# 架构概览

PicMe 采用 **Clean Architecture** + **单引擎设计**,确保代码可维护性、可测试性与高性能。

## 🏗️ 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    App Layer (PicMe)                     │
│  Features (Camera/Gallery/Editor/Settings/Debug)        │
│  ↓ 依赖 beauty-engine:api                                │
└────────────────────┬──────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────┐
│  Domain Layer (纯 Kotlin,无 Android 依赖)              │
│  ├─ Models: FaceWarpParams, BeautySettings             │
│  ├─ UseCases: DetectFace, ApplyBeauty, SavePhoto      │
│  └─ Repositories: PhotoRepository, PreferenceRepo      │
└────────────────────┬──────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────┐
│  Data Layer                                            │
│  ├─ DataSources: Room DB, DataStore, CameraX          │
│  ├─ Repositories Impl                                  │
│  └─ Mappers: Entity ↔ Domain Model                    │
└─────────────────────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────┐
│  Beauty Engine (beauty-engine 模块)                    │
│  ├─ api/: 对外稳定 API 契约                            │
│  │   ├─ BeautySettings / FilterType / StyleFilter     │
│  │   ├─ Face / BeautyProcessor                        │
│  │   └─ BeautyPreviewEngine (Interface)               │
│  └─ egl/: OpenGL ES + EGL 渲染管线 (internal)         │
│      ├─ BeautyRenderer (多 Pass GPU 管线)             │
│      ├─ CameraPreviewRenderer                         │
│      ├─ FaceMakeupPass (唇色/腮红)                    │
│      └─ StyleEffectShader (卡通/素描等)               │
└─────────────────────────────────────────────────────────┘
```

### 依赖规则

✅ **允许**:
- `Features → Domain UseCase → Domain Repository → Data Impl`
- `Features → beauty-engine:api` (禁止直接引用 `egl/` 内部类)
- `core/image/ → beauty-engine:api` (拍照后处理,与实时预览隔离)

❌ **禁止**:
- Domain 层依赖 `android.*` 或 `features.*`
- Features 层直接访问 `beauty-engine:egl` 内部实现
- Data 层反向依赖 Features 层

---

## 📦 模块划分

### 1. app 模块 (主应用)

```
app/src/main/java/com/picme/
├── domain/                  # 领域层 (纯 Kotlin)
│   ├── model/              # 数据模型 (FaceWarpParams, BeautySettings)
│   ├── usecase/            # 业务用例 (DetectFace, ApplyBeauty)
│   └── repository/         # 仓储接口 (PhotoRepository)
├── data/                   # 数据层
│   ├── local/              # 本地数据源 (Room DB, DataStore)
│   ├── repository/         # 仓储实现
│   └── mapper/             # 数据映射 (Entity ↔ Domain)
├── features/               # 功能模块 (Compose UI)
│   ├── camera/             # 相机预览与拍摄
│   │   ├── facedetect/     # 人脸检测调度 (InsightFace/MediaPipe)
│   │   └── preview/gl/     # 大美丽预览策略
│   ├── gallery/            # 相册浏览与管理
│   ├── editor/             # 照片编辑 (涂鸦/马赛克)
│   ├── settings/           # 应用设置
│   └── debug/              # 调试工具面板
├── core/                   # 核心工具
│   ├── image/              # 拍照后 CPU 静态 Bitmap 处理
│   ├── designsystem/       # 通用 UI 组件 (HyperOS 风格)
│   └── common/             # Logger,扩展函数等
├── di/                     # 依赖装配 (手动 DI)
└── navigation/             # 页面路由
```

### 2. beauty-engine 模块 (实时美颜引擎)

```
beauty-engine/src/main/java/com/picme/beauty/
├── api/                    # 对外稳定 API (public)
│   ├── BeautySettings.kt   # 美颜参数配置
│   ├── FilterType.kt       # 滤镜类型枚举
│   ├── StyleFilter.kt      # 风格特效枚举
│   ├── Face.kt             # 人脸数据结构
│   ├── BeautyProcessor.kt  # 美颜处理器接口
│   └── BeautyPreviewEngine.kt # 预览引擎接口
└── egl/                    # OpenGL ES 渲染实现 (internal)
    ├── BeautyRenderer.kt   # 多 Pass 渲染管线
    ├── CameraPreviewRenderer.kt # 相机预览渲染器
    ├── FaceMakeupPass.kt   # 唇色/腮红妆容 Pass
    ├── StyleEffectShader.kt # 风格特效 Shader
    ├── OffscreenRenderer.kt # 离屏渲染 (拍照 GPU 化)
    └── EGLContextManager.kt # EGL 上下文管理
```

---

## 🎨 核心设计模式

### 1. 策略模式 (Strategy Pattern)

**应用场景**: 美颜引擎切换

```kotlin
interface BeautyPreviewEngine {
    fun initialize()
    fun render(textureId: Int): Int
    fun release()
}

class BigBeautyEngine : BeautyPreviewEngine { ... }
// GPUPixel 已移除,仅保留 BigBeautyEngine
```

### 2. 适配器模式 (Adapter Pattern)

**应用场景**: 人脸检测双引擎统一接口

```kotlin
interface FaceLandmarkAdapter {
    val source: FaceDetectionSource
    fun adapt(rawResult: Any): GpuPixelLandmarks
}

class InsightFaceAdapter : FaceLandmarkAdapter { ... }
class MediaPipe468Adapter : FaceLandmarkAdapter { ... }
```

### 3. 工厂模式 (Factory Pattern)

**应用场景**: 检测流水线创建

```kotlin
object DetectionPipelineFactory {
    fun create(config: DetectionPipelineConfig): DetectionPipeline {
        return when (config.roiDetectorType) {
            RoiDetectorType.DET10G -> Det10GRoiDetector()
            RoiDetectorType.MEDIAPIPE -> MediaPipeRoiDetector()
        }
    }
}
```

### 4. 观察者模式 (Observer Pattern)

**应用场景**: 美颜参数变化通知

```kotlin
class BeautySettings : Observable() {
    var smoothingStrength: Float by observable(0.5f) { _, old, new ->
        notifyObservers(BeautyEvent.SmoothingChanged(new))
    }
}
```

---

## 🔧 关键技术决策

### ADR-001: 大美丽单引擎架构

**决策**: 移除 GPUPixel,仅保留自研 OpenGL ES 引擎

**理由**:
- ✅ 完全自主可控,无商业 SDK 依赖
- ✅ 代码量减少 40%,维护成本降低
- ✅ 渲染效果一致性提升 (预览/拍照同源 Shader)
- ❌ 初期开发成本高 (已克服)

**影响范围**: 
- `beauty-engine/egl/` 完全重写
- App 层仅依赖 `beauty-engine:api`
- GPUPixel 相关代码全部清理

详见: [ADR-001](../docs/ADR-001-beauty-engine-architecture.md)

### ADR-002: OpenGL 离屏渲染统一管线

**决策**: 预览与拍照使用同一套 OpenGL Shader

**理由**:
- ✅ 预览/拍照效果一致性从 70-85% 提升至 99%+
- ✅ 代码复用率提升,避免重复实现
- ✅ 性能优化: 1080p 处理 < 300ms (CPU 路径 800-1200ms)

**实现**:
- 预览: `SurfaceTexture → OpenGL ES → SurfaceView`
- 拍照: `Bitmap → EGL Off-screen → OpenGL ES → Bitmap`

详见: [ADR-002](../docs/ADR-002-opengl-offscreen-unified-pipeline.md)

### ADR-003: 坐标系统标准

**决策**: 统一使用归一化坐标 [0,1],明确左右命名规范

**理由**:
- ✅ 避免坐标系混用导致的错位问题
- ✅ 前置摄像头镜像翻转逻辑清晰
- ✅ 跨平台移植友好 (iOS/Web)

**规范**:
- OpenGL NDC: [-1,1],Y 轴向上
- 图像像素坐标: [0,width]×[0,height],Y 轴向下
- 归一化坐标: [0,1],Y 轴向下
- 左右命名: 以人物视角为准 (非屏幕视角)

详见: [ADR-003](../docs/ADR-003-coordinate-system-management.md)

---

## 🔄 数据流示例

### 相机预览流程

```
1. CameraX ImageProxy
   ↓
2. FaceDetectorManager.detect()
   ├─ InsightFace: Det10G ROI → 2D106 关键点
   └─ MediaPipe: 468 点 → 映射到 106 点
   ↓
3. Face106ToWarpParams.convert()
   → FaceWarpParams (归一化坐标)
   ↓
4. BeautyRenderer.setFaceWarpParams()
   → OpenGL Shader Uniforms
   ↓
5. CameraPreviewRenderer.onRender()
   ├─ Pass 1: 基础美颜 (磨皮/美白/瘦脸/大眼)
   ├─ Pass 2: 唇色 (FaceMakeupPass, BLEND_MODE_MULTIPLY)
   ├─ Pass 3: 腮红 (FaceMakeupPass, BLEND_MODE_OVERLAY)
   └─ Pass 4: 风格特效 (StyleEffectShader)
   ↓
6. swapBuffers() → SurfaceView 显示
```

### 拍照 GPU 化流程

```
1. ImageCapture.capture()
   → ImageProxy (原始帧)
   ↓
2. OffscreenRenderer.render()
   ├─ 创建 EGL Pbuffer Surface
   ├─ 绑定纹理: ImageProxy → GL_TEXTURE_2D
   ├─ 执行完整美颜管线 (同预览)
   └─ 读取像素: glReadPixels() → Bitmap
   ↓
3. GpuBeautyProcessor.applyPostProcess()
   → 色调滤镜 (ColorMatrix)
   ↓
4. PhotoRepository.save()
   → MediaStore (相册)
```

---

## 📊 性能指标

| 指标 | 目标值 | 当前值 | 测量方法 |
|------|--------|--------|----------|
| **冷启动时间** | < 500ms | ~450ms | `adb shell am start -W` |
| **首帧渲染** | < 500ms | ~480ms | Logger 时间戳 |
| **预览 FPS** | ≥ 30fps | 30-60fps | 调试浮层 |
| **拍摄延迟** | < 50ms | ~45ms | HapticFeedback 触发时间 |
| **拍照处理 (1080p)** | < 300ms | ~280ms | Logger 耗时统计 |
| **拍照处理 (4K)** | < 800ms | ~750ms | Logger 耗时统计 |
| **相册滚动 FPS** | 120fps | 115-120fps | Systrace |
| **内存占用** | < 200MB | ~180MB | Android Profiler |

---

## 🚀 未来演进

### Phase 2 (4-8 周)
- [ ] 卡尔曼滤波跟踪: 预测下一帧关键点位置
- [ ] ML Kit Selfie Segmentation: 人像分割 Mask
- [ ] MediaPipe Pose: 人体姿态估计 (身材管理)

### Phase 3 (8-16 周)
- [ ] 3D LUT 滤镜: 64×64×64 颜色查找表
- [ ] 引导滤波磨皮: O(N) 复杂度,更优边缘保持
- [ ] beauty-core 抽离: 纯 Kotlin 能力库化

---

## 📚 相关文档

- [架构决策记录](Architecture-Decisions) - ADR-001/002/003 详解
- [人脸检测双引擎](Face-Detection-Engines) - InsightFace + MediaPipe 架构
- [实时美颜系统](Beauty-Engine) - 大美丽渲染管线技术细节
- [坐标系统标准](Coordinate-System) - 坐标系转换与映射规范

---

**最后更新**: 2026-05-05  
**维护者**: PicMe RD Team
