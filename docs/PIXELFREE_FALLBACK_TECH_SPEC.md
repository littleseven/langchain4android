# PixelFreeEffects 技术集成文档

**版本**：1.1
**状态**：稳定兜底方案（与 R Plan 双引擎共存）
**最后更新**：2026-04（按双引擎现状更新）
**实施周期**：持续维护

## 0. 文档边界与导航

- 本文档聚焦 **PixelFree 作为稳定兜底引擎** 的集成与维护。
- R Plan 主链路（Provider 渲染、回退状态机、冷却恢复）见 `R_PLAN_TECH_SPEC.md`。
- 预览比例与坐标映射见 `CAMERA_PREVIEW_TECH_SPEC.md`。

## 1. 技术选型决策

### 1.1 背景
当前项目采用双引擎策略：`R_PLAN` 作为默认主引擎，`PIXEL_FREE` 作为稳定兜底引擎。PixelFree 文档用于说明兜底链路的集成、维护与兼容性要求；R Plan 主链路详见 `R_PLAN_TECH_SPEC.md`。

### 1.2 双轨策略

根据当前技术路线，双轨策略调整为：

- **主引擎（默认）**：R Plan（自研 OpenGL 链路）
- **兜底引擎**：PixelFreeEffects（异常回退、稳定可用）
- **恢复机制**：冷却窗口结束后自动重试 R Plan，失败则继续回退 PixelFree

### 1.3 PixelFree 兜底方案定位
作为兜底链路，**PixelFreeEffects** 方案的核心价值是稳定性与兼容性：

**技术优势**：
- ✅ 成熟的商业级美颜 SDK
- ✅ 支持 iOS/Android/Flutter 等多平台
- ✅ 完整的美颜、美型、滤镜、美妆功能
- ✅ 高性能 GPU 加速处理
- ✅ 支持 OpenGL 纹理模式（适合实时预览）

**功能支持**：
- ✅ 基础美颜：磨皮、美白、红润、锐化
- ✅ 美型：大眼、瘦脸、下巴、额头等 20+ 参数
- ✅ 滤镜：50+ 款可选滤镜
- ✅ 美妆：眉毛、腮红、眼影、唇彩等
- ✅ 贴纸：60+ 款 2D 贴纸
- ✅ 调色：亮度、对比度、曝光等专业参数

### 1.4 对比分析（2026-04）

| 方案 | 角色定位 | 优势 | 风险 |
|------|----------|------|------|
| R 计划（自研） | 默认主引擎 | 可控性高、可观测性强、授权成本低 | 设备兼容与预览链路复杂度高 |
| PixelFreeEffects | 稳定兜底 | 兼容性成熟、故障回退快 | 授权与扩展受限 |

**技术路线说明**：
- **运行策略**：默认使用 R Plan，异常自动回退 PixelFree。
- **容灾原则**：优先保证拍照/预览可用，再恢复主引擎能力。
- **长期目标**：持续降低兜底触发率，将 PixelFree 控制在应急路径。

## 2. SDK 集成架构

### 2.1 整体架构（当前实现）

```
┌──────────────────────────────────────────┐
│ CameraX Preview                          │
│   └─ PreviewView.surfaceProvider         │
└──────────────────────────────────────────┘
                     ↓
┌──────────────────────────────────────────┐
│ Preview Strategy Layer                   │
│   ├─ GlBeautyPreviewStrategy (主引擎)     │
│   └─ PixelFreePreviewStrategy (兜底引擎)  │
└──────────────────────────────────────────┘
                     ↓
┌──────────────────────────────────────────┐
│ PixelFree Engine Runtime                 │
│   ├─ 参数映射（0~100 -> 0~1）            │
│   ├─ queueEvent 同步到 GL 线程           │
│   └─ 美颜/美型能力执行                    │
└──────────────────────────────────────────┘
```

> 说明：在当前策略化绑定中，Preview Surface 由 `PreviewView` 统一承接；PixelFree 侧重点是能力执行与参数处理。

### 2.2 核心组件

#### 2.2.1 PixelFreeGLSurfaceView
```kotlin
class PixelFreeGLSurfaceView : GLSurfaceView, GLSurfaceView.Renderer {
    // 集成 PixelFree SDK
    // 管理 OpenGL 上下文
    // 处理实时美颜渲染
}
```

**职责**：
- 管理 PixelFree SDK 的生命周期
- 提供 OpenGL ES 2.0 渲染环境
- 承接 PixelFree 能力执行与参数生效
- 支持自定义美颜参数
- 不直接承接 CameraX 的 `SurfaceRequest`

#### 2.2.2 PixelFreeBeautyEngine
```kotlin
class PixelFreeBeautyEngine(context: Context) {
    // SDK 包装类
    // 提供高级 API
    // 简化调用
}
```

**职责**：
- 封装 PixelFree SDK 的底层 API
- 提供美颜参数设置的便捷方法
- 支持多种图像格式处理（纹理/RGBA/YUV）
- 管理 SDK 资源生命周期

### 2.3 数据流（当前实现）

**预览绑定流程**：
```
1. CameraX 创建 Preview UseCase
   ↓
2. PixelFreePreviewStrategy.bindPreview(...)
   ↓
3. PreviewUseCase.setSurfaceProvider(previewView.surfaceProvider)
   ↓
4. UI 保持 PreviewView 容器展示（bindPreview 返回 false）
```

**参数生效流程**：
```
1. 业务侧输出 BeautySettings（0~100）
   ↓
2. PixelFreePreviewStrategy.applyBeautySettings(...)
   ↓
3. queueEvent 切到 GL 线程
   ↓
4. PixelFree SDK 执行参数更新并生效
```

**拍照链路说明**：
- 当前文档重点是预览兜底链路；拍照后处理以业务实际实现为准。

## 3. 实施细节

### 3.1 依赖配置

**build.gradle.kts**：
```kotlin
dependencies {
    // PixelFreeEffects SDK（手动导入 AAR）
    implementation(files("libs/lib_pixelFree.aar"))
    
    // OpenGL ES 支持
    implementation("androidx.opengl:opengl-android:1.0.0")
}
```

### 3.2 初始化流程（SDK 能力侧）

**Step 1: 创建 PixelFreeGLSurfaceView**
```kotlin
val pixelFreeView = PixelFreeGLSurfaceView(context)
```

**Step 2: 初始化 SDK**
```kotlin
// 在 onSurfaceCreated 中自动初始化
pixelFreeView.initPixelFree()

// 加载授权文件（如果有）
val authData = pixelFreeView.readBundleFile(context, "pixelfreeAuth.lic")
if (authData != null) {
    pixelFreeView.auth(context, authData, authData.size)
}

// 加载滤镜资源
val filterData = pixelFreeView.readBundleFile(context, "filter_model.bundle")
if (filterData != null) {
    pixelFreeView.createBeautyItemFormBundle(
        filterData,
        filterData.size,
        PFSrcType.PFSrcTypeFilter
    )
}
```

**Step 3: 设置美颜参数**
```kotlin
// 磨皮
pixelFreeView.setBeautyParam(PFBeautyFilterType.PFBeautyFiterTypeFaceBlurStrength, 0.5f)

// 美白
pixelFreeView.setBeautyParam(PFBeautyFilterType.PFBeautyFiterTypeFaceWhitenStrength, 0.3f)

// 大眼
pixelFreeView.setBeautyParam(PFBeautyFilterType.PFBeautyFiterTypeFace_EyeStrength, 0.3f)

// 瘦脸
pixelFreeView.setBeautyParam(PFBeautyFilterType.PFBeautyFiterTypeFace_thinning, 0.3f)
```

### 3.3 相机集成（重构后实现）

当前代码已切到**策略化预览绑定**，PixelFree 作为兜底策略时采用以下链路：

```kotlin
internal class PixelFreePreviewStrategy(
    private val previewView: PreviewView,
    private val pixelFreeView: PixelFreeGLSurfaceView
) : BeautyPreviewEngineStrategy {

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        return false
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        pixelFreeView.queueEvent {
            pixelFreeView.setSmoothingStrength(settings.smoothing / 100f)
            pixelFreeView.setWhiteningStrength(settings.whitening / 100f)
            pixelFreeView.setBigEyesStrength((settings.bigEyes / 100f * 1.35f).coerceIn(0f, 1f))
            pixelFreeView.setSlimFaceStrength(((settings.slimFace + 50f) / 100f).coerceIn(0f, 1f))
        }
    }
}
```

实现要点：

- CameraX 预览 Surface 统一走 `PreviewView.surfaceProvider`。
- PixelFree 仍负责参数处理与渲染能力，不直接承接 CameraX 的 SurfaceRequest。
- `bindPreview(...)` 返回 `false`，表示 UI 继续显示 `PreviewView` 容器。

### 3.4 美颜参数详解

**PFBeautyFilterType 枚举**：
```kotlin
enum class PFBeautyFilterType(val intType: Int) {
    PFBeautyFiterTypeFace_EyeStrength(0),      // 大眼 (0.0-1.0)
    PFBeautyFiterTypeFace_thinning(1),         // 瘦脸 (0.0-1.0)
    PFBeautyFiterTypeFace_narrow(2),           // 窄脸 (0.0-1.0)
    PFBeautyFiterTypeFace_chin(3),             // 下巴 (0.5 双向)
    PFBeautyFiterTypeFace_V(4),                // V 脸 (0.0-1.0)
    PFBeautyFiterTypeFace_small(5),            // small (0.0-1.0)
    PFBeautyFiterTypeFace_nose(6),             // 鼻子 (0.0-1.0)
    PFBeautyFiterTypeFace_forehead(7),         // 额头 (0.5 双向)
    PFBeautyFiterTypeFace_mouth(8),            // 嘴巴 (0.5 双向)
    PFBeautyFiterTypeFace_philtrum(9),         // 人中 (0.5 双向)
    PFBeautyFiterTypeFace_long_nose(10),       // 长鼻 (0.5 双向)
    PFBeautyFiterTypeFace_eye_space(11),       // 眼距 (0.5 双向)
    PFBeautyFiterTypeFace_smile(12),           // 微笑嘴角 (0.0-1.0)
    PFBeautyFiterTypeFace_eye_rotate(13),      // 旋转眼睛 (0.5 双向)
    PFBeautyFiterTypeFace_canthus(14),         // 开眼角 (0.0-1.0)
    PFBeautyFiterTypeFaceBlurStrength(15),     // 磨皮 (0.0-1.0)
    PFBeautyFiterTypeFaceWhitenStrength(16),   // 美白 (0.0-1.0)
    PFBeautyFiterTypeFaceRuddyStrength(17),    // 红润 (0.0-1.0)
    PFBeautyFiterTypeFaceSharpenStrength(18),  // 锐化 (0.0-1.0)
    PFBeautyFiterTypeFaceM_newWhitenStrength(19), // 新美白
    PFBeautyFiterTypeFaceH_qualityStrength(20),   // 画质增强
    PFBeautyFiterTypeFaceEyeBrighten(21),      // 亮眼 (0.0-1.0)
    PFBeautyFiterName(22),                     // 滤镜名称
    PFBeautyFiterStrength(23),                 // 滤镜强度
    PFBeautyFiterSticker2DFilter(24),          // 2D 贴纸
    PFBeautyFiterTypeOneKey(25),               // 一键美颜
    PFBeautyFiterExtend(26),                   // 扩展字段
    PFBeautyFilterNasolabial(27),              // 祛法令纹 (0.0-1.0)
    PFBeautyFilterBlackEye(28),                // 祛黑眼圈 (0.0-1.0)
    PFBeautyFilterWhitenTeeth(29),             // 美牙 (0.0-1.0)
}
```

### 3.5 美妆功能

**加载美妆**：
```kotlin
// 方式 1：通过 bundle 文件（推荐）
val makeupData = pixelFreeView.readBundleFile(context, "makeup/makeup_name.bundle")
if (makeupData != null) {
    pixelFreeView.createBeautyItemFormBundle(
        makeupData,
        makeupData.size,
        PFSrcType.PFSrcTypeMakeup
    )
}

// 方式 2：通过 JSON 配置（已废弃）
// pixelFreeView.setMakeupPath("makeup/makeup_config.json")
```

**设置美妆程度**：
```kotlin
// 设置唇彩程度为 0.8
pixelFreeView.setMakeupPartDegree(PFMakeupPart.PFMakeupPartLip, 0.8f)

// 设置眼影程度为 0.5
pixelFreeView.setMakeupPartDegree(PFMakeupPart.PFMakeupPartEyeShadow, 0.5f)

// 批量设置所有部位
PFMakeupPart.values().forEach { part ->
    pixelFreeView.setMakeupPartDegree(part, 0.8f)
}
```

**清除美妆**：
```kotlin
pixelFreeView.clearMakeup()
```

## 4. 性能优化

### 4.1 性能指标

**处理速度**：
- 1080p @ 30fps: < 5ms/帧
- 720p @ 60fps: < 3ms/帧
- 支持实时 60fps 预览

**内存占用**：
- SDK 基础占用：~20MB
- 滤镜资源：~5-10MB
- 美妆资源：~10-20MB
- 总占用：~40-60MB

### 4.2 优化建议

**1. 资源管理**：
- 按需加载滤镜和美妆资源
- 及时释放不需要的资源
- 使用 bundle 方式加载（性能更好）

**2. 渲染优化**：
- 使用 RENDERMODE_WHEN_DIRTY 模式
- 避免频繁调用 requestRender()
- 在子线程中处理耗时操作

**3. 参数调节**：
- 美颜参数建议范围：0.3-0.7
- 避免过度美颜（不自然）
- 提供实时预览反馈

## 5. 常见问题

### 5.1 初始化失败

**问题**：`PixelFree create() failed`

**原因**：
- OpenGL 上下文未创建
- AAR 文件未正确集成
- 缺少必要的权限

**解决**：
- 确保在 GLSurfaceView 的 onSurfaceCreated 中初始化
- 检查 build.gradle 配置
- 添加必要的依赖

### 5.2 美颜效果不显示

**问题**：调用了 setBeautyParam 但没有效果

**原因**：
- 参数值过小
- 未调用 processWithBuffer
- 纹理 ID 不正确

**解决**：
- 增大参数值（建议 0.5 以上）
- 确保调用 processTexture() 或 processWithBuffer()
- 检查纹理 ID 是否有效

### 5.3 性能问题

**问题**：预览卡顿、帧率低

**原因**：
- 同时开启过多效果
- 分辨率过高
- 设备性能不足

**解决**：
- 减少同时开启的美颜项
- 降低处理分辨率（720p）
- 使用性能更好的设备测试

## 6. 参考资料

### 6.1 官方文档
- [PixelFreeEffects GitHub](https://github.com/uu-code007/PixelFreeEffects)
- [Android 集成文档](../temp/PixelFreeEffects/doc/doc_android.md)
- [API 参考文档](../temp/PixelFreeEffects/doc/api_android.md)

### 6.2 示例代码
- [官方 Demo](../temp/PixelFreeEffects/SMBeautyEngine_andriod/pixelfree_android_demo/)
- [本项目实现](../app/src/main/java/com/picme/core/image/pixelfree/)

### 6.3 相关资源
- 滤镜 bundle 文件：`assets/filter_model.bundle`
- 美妆 bundle 文件：`assets/makeup_name.bundle`
- 授权文件：`assets/pixelfreeAuth.lic`

## 7. 版本信息

**SDK 版本**：2.4.9
**集成日期**：2026-03-29
**集成方式**：手动导入 AAR
**项目版本**：PicMe 1.0
