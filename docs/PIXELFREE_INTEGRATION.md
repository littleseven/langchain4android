# PixelFreeEffects 技术集成文档

**版本**：1.0  
**状态**：当前实施方案（短期）  
**最后更新**：2026-03-29  
**实施周期**：1-2 周

## 1. 技术选型决策

### 1.1 背景
原 R 计划（自研 OpenGL ES + EGL 手动管理）在实施过程中遇到 CameraX SurfaceProvider 机制限制，无法实现离屏渲染到自定义 SurfaceTexture。

### 1.2 双轨策略

根据项目技术路线调整，决定采用**双轨并行策略**：

- **短期（1-2 周）**：使用 PixelFreeEffects SDK 快速实现产品功能
- **中期（1-2 月）**：并行运行，积累性能数据和用户反馈
- **中长期（2-3 月）**：基于 R 计划自主研发，借鉴 PixelFreeEffects 技术方案

### 1.3 新方案
采用 **PixelFreeEffects** 开源美颜 SDK，该方案具备以下优势：

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

### 1.4 对比分析

| 方案 | 复杂度 | 性能 | 功能完整性 | 推荐度 |
|------|--------|------|-----------|--------|
| R 计划（自研） | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ |
| PixelFreeEffects（短期） | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |

**技术路线说明**：
- **当前阶段**：优先使用 PixelFreeEffects SDK 实现产品功能
- **数据积累**：监控性能、收集用户反馈、记录美颜参数偏好
- **中长期替代**：基于 R 计划自主研发，借鉴 PixelFreeEffects 的技术方案
- **最终目标**：零授权成本、完全可控、构建核心技术能力

## 2. SDK 集成架构

### 2.1 整体架构

```
┌─────────────────────────────────────────┐
│          CameraX (相机采集)              │
│              ↓                          │
│         YUV/RGBA 数据                    │
│              ↓                          │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│    PixelFreeGLSurfaceView               │
│  ┌─────────────────────────────────┐   │
│  │  PixelFree SDK (美颜处理)        │   │
│  │  - 磨皮/美白/红润               │   │
│  │  - 大眼/瘦脸/美型               │   │
│  │  - 滤镜/美妆/贴纸               │   │
│  └─────────────────────────────────┘   │
│              ↓                          │
│         OpenGL 纹理处理                 │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│         显示到屏幕                       │
└─────────────────────────────────────────┘
```

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
- 处理相机纹理的美颜渲染
- 支持自定义美颜参数

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

### 2.3 数据流

**实时预览流程**：
```
1. CameraX 采集相机帧
   ↓
2. 生成 OpenGL 外部纹理 (GL_TEXTURE_EXTERNAL_OES)
   ↓
3. PixelFreeGLSurfaceView 接收纹理
   ↓
4. PixelFree SDK 处理纹理（美颜、美型、滤镜）
   ↓
5. 渲染到屏幕显示
```

**拍照处理流程**：
```
1. 从 CameraX 获取 YUV/RGBA 数据
   ↓
2. 调用 processYUV() 或 processRGBA()
   ↓
3. PixelFree SDK 处理图像数据
   ↓
4. 获取处理后的数据
   ↓
5. 保存为照片
```

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

### 3.2 初始化流程

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

### 3.3 相机集成

**使用 CameraX + PixelFreeGLSurfaceView**：
```kotlin
// 1. 创建 PixelFreeGLSurfaceView
val pixelFreeView = PixelFreeGLSurfaceView(context)

// 2. 设置渲染回调
pixelFreeView.setRenderCallback { textureId, width, height ->
    // 调用 PixelFree SDK 处理纹理
    pixelFreeView.processTexture(textureId, width, height)
}

// 3. CameraX 绑定到 PixelFreeView
val preview = Preview.Builder()
    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
    .build()

preview.setSurfaceProvider(cameraExecutor) { outputSurface ->
    // 将相机输出发送到 PixelFreeView
    pixelFreeView.setCameraTextureId(textureId, width, height)
}

// 4. 绑定到生命周期
cameraProvider.bindToLifecycle(
    lifecycleOwner,
    cameraSelector,
    preview
)
```

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
