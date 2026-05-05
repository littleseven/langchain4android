# 实时美颜系统 (大美丽引擎)

大美丽 (Big Beauty) 是 PicMe 自研的 OpenGL ES + EGL 实时美颜引擎,支持磨皮、美白、瘦脸、大眼、唇色、腮红等完整美颜链路。

## 🎨 核心能力

| 能力 | 实现方式 | 强度范围 |
|------|----------|----------|
| **磨皮** | 双边滤波 (9pt 快速近似) | 0-100 |
| **美白** | YUV 亮度调整 + Log 曲线 | 0-100 |
| **瘦脸** | FaceWarp 网格变形 | -50~+50 |
| **大眼** | 径向放大变换 | 0-100 |
| **唇色** | HSV 色相调整 + 纹理妆容 | 0-100, 12 种色号 |
| **腮红** | 双颊椭圆染色 | 0-100 |
| **专业调色** | 曝光/对比度/饱和度/色温/色调 | -100~+100 |
| **风格特效** | 卡通/素描/浮雕/色块化/交叉线 | 开关 |

## 🔧 渲染管线

### 多 Pass 架构

```
Input Texture
    ↓
Pass 1: 基础美颜 Shader
    ├─ 磨皮 (双边滤波)
    ├─ 美白 (YUV 亮度)
    ├─ 瘦脸 (FaceWarp 网格)
    └─ 大眼 (径向放大)
    ↓
Pass 2: 唇色 (FaceMakeupPass)
    ├─ BLEND_MODE_MULTIPLY
    ├─ HSV 色相调整
    └─ 纹理妆容叠加
    ↓
Pass 3: 腮红 (FaceMakeupPass)
    ├─ BLEND_MODE_OVERLAY
    ├─ 双颊椭圆区域
    └─ 自然红润染色
    ↓
Pass 4: 风格特效 (StyleEffectShader)
    ├─ ToonFilter (卡通)
    ├─ SketchFilter (素描)
    ├─ EmbossFilter (浮雕)
    ├─ PosterizeFilter (色块化)
    └─ CrosshatchFilter (交叉线)
    ↓
Output Texture → SurfaceView
```

### Ping-Pong FBO

使用双 FBO (Framebuffer Object) 实现多 Pass 渲染:

```kotlin
var currentTextureId = inputTextureId
var nextOutputFbo = if (fboPing.getTextureId() == currentTextureId) fboPong else fboPing

// Pass 1: 唇色渲染到 FBO
if (lipColorStrength > 0.001f) {
    faceMakeupPass.setIntensity(lipColorStrength)
    faceMakeupPass.setBlendMode(FaceMakeupPass.BLEND_MODE_MULTIPLY)
    val rendered = faceMakeupPass.render(
        inputTextureId = currentTextureId,
        outputFbo = nextOutputFbo,
        makeupType = FaceMakeupPass.MakeupType.LIP
    )
    currentTextureId = nextOutputFbo.getTextureId()
    nextOutputFbo = if (nextOutputFbo === fboPing) fboPong else fboPing
}

// Pass 2: 腮红渲染到另一个 FBO
if (blushStrength > 0.001f) {
    faceMakeupPass.setIntensity(blushStrength)
    faceMakeupPass.setBlendMode(FaceMakeupPass.BLEND_MODE_OVERLAY)
    val rendered = faceMakeupPass.render(
        inputTextureId = currentTextureId,
        outputFbo = nextOutputFbo,
        makeupType = FaceMakeupPass.MakeupType.BLUSH
    )
    currentTextureId = nextOutputFbo.getTextureId()
}
```

## 📸 拍照 GPU 化

### 离屏渲染流程

```
ImageProxy (原始帧)
    ↓
OffscreenRenderer.render()
    ├─ 创建 EGL Pbuffer Surface
    ├─ 绑定纹理: ImageProxy → GL_TEXTURE_2D
    ├─ 执行完整美颜管线 (同预览)
    └─ 读取像素: glReadPixels() → Bitmap
    ↓
GpuBeautyProcessor.applyPostProcess()
    → 色调滤镜 (ColorMatrix)
    ↓
PhotoRepository.save() → MediaStore
```

### 性能指标

| 分辨率 | CPU 路径 (旧) | GPU 路径 (新) | 提升 |
|--------|---------------|---------------|------|
| **1080p** | 800-1200ms | ~280ms | 3-4x |
| **4K** | 3-5s | ~750ms | 4-6x |

### 效果一致性

- **之前**: 预览/拍照效果一致性 70-85% (不同 Shader 实现)
- **现在**: 预览/拍照效果一致性 99%+ (复用同一套 Shader)

## 🎭 风格特效

### 支持的特效

| 特效 | Shader 名称 | 视觉效果 |
|------|-------------|----------|
| **卡通** | ToonFilter | 强化边缘线条,降低色彩层级 |
| **素描** | SketchFilter | 黑白铅笔素描风格 |
| **浮雕** | EmbossFilter | 凸起质感雕刻风 |
| **色块化** | PosterizeFilter | 限制颜色层级 (默认 4 层) |
| **交叉线** | CrosshatchFilter | 手绘版画风格 |

### 叠加逻辑

- 色调滤镜与风格特效可同时生效
- 处理顺序: 先 ColorMatrix 色调,再 GPU 风格渲染
- 风格特效内部互斥 (同时只能选一种)

## 🚀 未来演进

### Phase 2 (4-8 周)
- [ ] 引导滤波磨皮: O(N) 复杂度,更优边缘保持
- [ ] 眉毛美化: 独立 Shader mask
- [ ] 多色号唇色: 动态纹理替换

### Phase 3 (8-16 周)
- [ ] 3D LUT 滤镜: 64×64×64 颜色查找表
- [ ] 多尺度细节分层: 工业级磨皮算法
- [ ] ML Kit Selfie Segmentation: 背景虚化

## 📚 相关文档

- [BIG_BEAUTY_TECH_SPEC](../docs/BIG_BEAUTY_TECH_SPEC.md)
- [GPU_PHOTO_IMPLEMENTATION_GUIDE](../docs/GPU_PHOTO_IMPLEMENTATION_GUIDE.md)
- [BEAUTY_ENGINE_FALLBACK](../docs/BEAUTY_ENGINE_FALLBACK.md)

---

**最后更新**: 2026-05-05  
**维护者**: PicMe RD Team
