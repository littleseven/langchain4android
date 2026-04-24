# GPUPixel → 大美丽 滤镜移植方案

**版本**: 1.0  
**日期**: 2026-04-23  
**状态**: 调研完成，Phase 1 开发中  
**目标**: 将 GPUPixel 的专业调色与风格特效能力整合到大美丽（BIG_BEAUTY）主引擎

---

## 1. 现状分析

### 1.1 大美丽（BIG_BEAUTY）当前能力

| 能力 | 实现 | 状态 |
|------|------|------|
| 磨皮 | 双边滤波快速近似（9pt Shader） | ✅ |
| 美白 | 亮度提升 + 皮肤蒙版 | ✅ |
| 大眼 | 瞳孔 UV 径向变形 | ✅ |
| 瘦脸 | 沿眼轴 UV 横向压缩 | ✅ |
| 唇色 | 唇部多边形蒙版 + 12 色号 | ✅ |
| 腮红 | 两颊椭圆蒙版 + 3 色系 | ✅ |
| 锐化 | 拉普拉斯 9pt 采样 | ✅ |
| 色调滤镜 | ColorMatrix 4×5（LEICA/FILM/COOL/WARM） | ✅ |
| 人脸检测 | MediaPipe 468 → 106 点映射 | ✅ 刚完成 |

**架构**: 单 Pass GLSL Fragment Shader，所有效果在一个 Shader 中顺序执行。

### 1.2 GPUPixel 引擎能力（实验性）

| 能力 | Shader/Filter | 复杂度 |
|------|-------------|--------|
| 磨皮 | BeautyFaceFilter | 中等 |
| 瘦脸/大眼 | FaceReshapeFilter | 高 |
| 唇色 | LipstickFilter | 高 |
| 腮红 | BlusherFilter | 中等 |
| **曝光** | **ExposureFilter** | **极简** `color * pow(2.0, exposure)` |
| **对比度** | **ContrastFilter** | **极简** `(color - 0.5) * contrast + 0.5` |
| **饱和度** | **SaturationFilter** | **低** `mix(luma, color, saturation)` |
| **色温** | **WhiteBalanceFilter** | **低** 蓝-黄/绿-品红偏移 |
| **亮度** | **BrightnessFilter** | **极简** `color + brightness` |
| **RGB 调整** | **RGBFilter** | **极简** 通道独立缩放 |
| **卡通** | **ToonFilter** | **高** Sobel 边缘 + 颜色量化 |
| **素描** | **SketchFilter** | **高** 灰度 + Sobel + 反相 |
| **色块** | **PosterizeFilter** | **低** `floor(color * levels + 0.5) / levels` |
| **浮雕** | **EmbossFilter** | **中等** 3×3 卷积核 |
| **交叉线** | **CrosshatchFilter** | **中等** 基于亮度交叉线图案 |

**架构**: Filter Chain（多 Pass，每个 Filter 是独立 C++ 对象，有自己的 ShaderProgram 和 FBO）。

### 1.3 关键差距

大美丽缺少 GPUPixel 的以下能力：

| 类别 | 具体能力 | 用户需求优先级 |
|------|---------|-------------|
| 专业调色 | 曝光、对比度、饱和度、色温、色调、亮度、RGB | **P0** |
| 风格特效 | 卡通、素描、色块、浮雕、交叉线 | **P1** |
| HSB 调整 | 色相/饱和度/亮度分离调整 | P2 |

---

## 2. 移植方案设计

### 2.1 核心决策：混合架构（推荐）

**不采用** GPUPixel 的纯多 Pass Filter Chain（改造量过大，且大美丽单 Pass 性能已验证）。  
**也不采用** 无限扩展单 Pass Shader（Shader 编译时间和寄存器压力会超限）。

**采用混合方案**：

```
Camera Input (GL_TEXTURE_EXTERNAL_OES)
    ↓
【Pass 0: 美颜核心】BeautyCorePass — 现有 Shader，保持不变
  - 外部纹理采样 → 磨皮 → 美白 → 锐化 → 美型 → 唇色 → 腮红
    ↓
【Pass 1: 基础调色】ColorGradePass — 新增，默认 bypass
  - 曝光 / 对比度 / 饱和度 / 色温 / 色调 / 亮度 / RGB
  - 计算极简（< 10 条 ALU 指令），零性能负担
    ↓
【Pass 2: 风格特效】StyleEffectPass — 新增，默认 bypass
  - Toon / Sketch / Posterize / Emboss / Crosshatch
  - 独立 Shader，按需编译，互斥使用（一次只激活一种）
    ↓
【Pass 3: 色调滤镜】ColorFilterPass — 现有 ColorMatrix，移到独立 Pass
  - ColorMatrix 4×5 变换
    ↓
SurfaceView
```

### 2.2 为什么这样设计

| 方案 | 优点 | 缺点 |
|------|------|------|
| A: 纯多 Pass Chain | 模块清晰，与 GPUPixel 一致 | 改造量极大，FBO 切换开销 |
| B: 扩展单 Pass | 零架构改动 | Shader 过大，低端机编译失败 |
| **C: 混合（推荐）** | **调色零负担并入主 Shader，特效按需独立 Pass** | **需增加 1~2 个 FBO** |

**关键洞察**：
- 基础调色（曝光/对比度/饱和度/色温）是**全图颜色变换**，计算量极小（~10 条 ALU），完全可以放在 BeautyCorePass 末尾，不增加 Pass。
- 风格特效需要**3×3 邻域采样**（边缘检测、卷积），与美颜的 UV 变形不兼容（变形后邻域关系被破坏），**必须作为独立 Pass** 在变形前或变形后执行。
- 风格特效通常不与美颜同时使用，因此**按需编译、按需启用**是最佳策略。

### 2.3 最终精简架构

```
Camera Input (GL_TEXTURE_EXTERNAL_OES)
    ↓
【Pass 0: 美颜+调色】BeautyPass — 扩展现有 Shader
  - 现有：磨皮/美白/锐化/美型/唇色/腮红
  - 新增：曝光/对比度/饱和度/色温/色调/亮度/RGB
  - 新增：ColorMatrix 色调滤镜（从 uniform 移到 Shader 末尾）
    ↓
【Pass 1: 风格特效】StylePass — 可选，独立 Shader
  - 输入：BeautyPass 输出纹理（FBO）
  - 输出：SurfaceView
  - 互斥：Toon / Sketch / Posterize / Emboss / Crosshatch
    ↓
SurfaceView（无 Style 时 BeautyPass 直连）
```

**即：只增加 1 个可选 Pass，改造量最小。**

---

## 3. Shader 设计

### 3.1 BeautyPass 扩展（在现有 FRAGMENT_SHADER_BEAUTY 末尾追加）

```glsl
// ===== 新增 uniform =====
uniform float uExposure;      // -10 ~ +10, 0 = neutral
uniform float uContrast;      // 0 ~ 4, 1 = neutral
uniform float uSaturation;    // 0 ~ 2, 1 = neutral
uniform float uTemperature;   // 3000 ~ 8000, 5000 = neutral → -1 ~ +1
uniform float uTint;          // -100 ~ +100, 0 = neutral → -1 ~ +1
uniform float uBrightness;  // -1 ~ +1, 0 = neutral
uniform float uRedAdj;        // 0 ~ 2, 1 = neutral
uniform float uGreenAdj;      // 0 ~ 2, 1 = neutral
uniform float uBlueAdj;       // 0 ~ 2, 1 = neutral

// ===== 在 main() 末尾、gl_FragColor 之前插入 =====
vec3 applyColorGrade(vec3 color) {
    // 曝光
    color *= pow(2.0, uExposure);
    // 对比度
    color = (color - 0.5) * uContrast + 0.5;
    // 饱和度
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luma), color, uSaturation);
    // 色温（蓝-黄）
    color.r += uTemperature * 0.01;
    color.b -= uTemperature * 0.01;
    // 色调（绿-洋红）
    color.g += uTint * 0.005;
    color.b -= uTint * 0.005;
    // 亮度
    color += uBrightness;
    // RGB 通道
    color.r *= uRedAdj;
    color.g *= uGreenAdj;
    color.b *= uBlueAdj;
    return clamp(color, 0.0, 1.0);
}

// main() 中替换最后一行：
// vec4 finalColor = vec4(clamp(makeupColor, 0.0, 1.0), lipTinted.a);
// 改为：
vec3 graded = applyColorGrade(makeupColor);
vec4 finalColor = vec4(graded, lipTinted.a);
```

> **性能评估**：新增 ~15 条 ALU 指令，在现有 200+ 条指令的 Shader 中占比 < 7%，对帧率影响可忽略。

### 3.2 StylePass Shader（独立，每个风格一个 Shader）

#### ToonFilter（卡通）

```glsl
precision mediump float;
uniform sampler2D uInputTexture;  // BeautyPass 输出
uniform float uThreshold;         // 边缘阈值，默认 0.2
uniform float uQuantizationLevels; // 颜色量化级数，默认 10.0
uniform vec2 uTexelSize;
varying vec2 vTextureCoord;

// 3×3 邻域采样（Sobel 边缘检测）
vec4 sobelEdge() {
    // 8 邻域采样...
    float h = -tl - 2.0*t - tr + bl + 2.0*b + br;
    float v = -bl - 2.0*l - tl + br + 2.0*r + tr;
    float mag = length(vec2(h, v));
    return vec4(mag, mag, mag, 1.0);
}

void main() {
    vec4 color = texture2D(uInputTexture, vTextureCoord);
    float edge = sobelEdge().r;
    vec3 posterized = floor(color.rgb * uQuantizationLevels + 0.5) / uQuantizationLevels;
    float edgeMask = 1.0 - step(uThreshold, edge);
    gl_FragColor = vec4(posterized * edgeMask, color.a);
}
```

#### SketchFilter（素描）

```glsl
// Filter Group：Grayscale → Sobel Edge → Invert
// 灰度化
float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
// Sobel 边缘强度
float mag = length(vec2(h, v));
// 反相
float sketch = 1.0 - mag * uEdgeStrength;
gl_FragColor = vec4(vec3(sketch), 1.0);
```

#### PosterizeFilter（色块）

```glsl
vec4 color = texture2D(uInputTexture, vTextureCoord);
gl_FragColor = floor(color * uColorLevels + vec4(0.5)) / uColorLevels;
```

#### EmbossFilter（浮雕）

```glsl
// 3×3 卷积核：[-2,-1,0; -1,1,1; 0,1,2] * intensity
// 使用 3×3 邻域采样，与 GPUPixel Convolution3x3Filter 一致
```

#### CrosshatchFilter（交叉线）

```glsl
// 基于亮度阈值绘制交叉线图案
// 参考 GPUPixel 实现
```

---

## 4. BeautyRenderer 架构改造

### 4.1 当前状态

```kotlin
class BeautyRenderer {
    // 单 ShaderProgram，单 FBO（WindowSurface 直接显示）
    fun onRender() {
        // 绑定输入纹理 → 绘制全屏 quad → 输出到 WindowSurface
    }
}
```

### 4.2 改造后

```kotlin
class BeautyRenderer {
    // Pass 0: 美颜+调色（现有 Shader，扩展 uniform）
    private val beautyShader: ShaderProgram
    // Pass 1: 风格特效（按需编译，5 个 Shader 按需 lazy init）
    private val styleShaders: Map<StyleEffect, ShaderProgram>
    // 中间 FBO（BeautyPass 输出 → StylePass 输入）
    private var intermediateFbo: Framebuffer?
    
    fun onRender() {
        // Step 1: BeautyPass 渲染到 intermediateFbo
        bindFbo(intermediateFbo)
        beautyShader.use()
        drawQuad()
        
        // Step 2: 如有 Style，渲染到 WindowSurface
        if (activeStyle != StyleEffect.NONE) {
            styleShaders[activeStyle]?.use()
            bindTexture(intermediateFbo.texture)  // 作为输入
            drawQuad()
        } else {
            // 无 Style：直接将 intermediateFbo 内容 blit 到 WindowSurface
            blit(intermediateFbo, windowSurface)
        }
    }
}
```

### 4.3 新增类

| 类名 | 职责 |
|------|------|
| `ColorGradeParams` | 封装曝光/对比度/饱和度/色温/色调/亮度/RGB 参数 |
| `StyleEffect` | 枚举：NONE / TOON / SKETCH / POSTERIZE / EMBOSS / CROSSHATCH |
| `StyleEffectShader` | 风格特效 Shader 封装（编译、uniform 绑定） |
| `Framebuffer` | 离屏 FBO 封装（纹理 + 深度/模板缓冲） |

---

## 5. 数据流改造

### 5.1 BeautyParams 扩展

```kotlin
data class BeautyParams(
    // === 现有参数 ===
    val smoothing: Float = 0f,
    val whitening: Float = 0f,
    val bigEyes: Float = 0f,
    val slimFace: Float = 0f,
    val lipColor: Float = 0f,
    val lipColorIndex: Int = 0,
    val blush: Float = 0f,
    val blushColorFamily: Int = 0,
    val sharpen: Float = 0f,
    val colorMatrix: FloatArray? = null,
    
    // === 新增：专业调色（GPUPixel 移植）===
    val exposure: Float = 0f,        // -10.0 ~ +10.0
    val contrast: Float = 1f,      // 0.0 ~ 4.0
    val saturation: Float = 1f,    // 0.0 ~ 2.0
    val temperature: Float = 5000f, // 3000 ~ 8000 → 映射到 -1 ~ +1
    val tint: Float = 0f,          // -100 ~ +100 → 映射到 -1 ~ +1
    val brightness: Float = 0f,   // -1.0 ~ +1.0
    val redAdjustment: Float = 1f,  // 0.0 ~ 2.0
    val greenAdjustment: Float = 1f,// 0.0 ~ 2.0
    val blueAdjustment: Float = 1f, // 0.0 ~ 2.0
    
    // === 新增：风格特效（GPUPixel 移植）===
    val styleEffect: StyleEffect = StyleEffect.NONE,
    val styleIntensity: Float = 1f, // 0.0 ~ 1.0（强度统一控制）
    
    // 风格特效子参数
    val toonThreshold: Float = 0.2f,
    val toonQuantizationLevels: Float = 10f,
    val sketchEdgeStrength: Float = 1f,
    val posterizeColorLevels: Float = 10f,
    val embossIntensity: Float = 1f,
    val crosshatchSpacing: Float = 0.03f,
    val crosshatchLineWidth: Float = 0.003f,
)
```

### 5.2 参数转换器扩展

在 `BeautyParamsConverter` 中增加 GPUPixel → 大美丽参数映射：

```kotlin
fun applyColorGrade(params: BeautyParams, renderer: BeautyRenderer) {
    renderer.setExposure(params.exposure)
    renderer.setContrast(params.contrast)
    renderer.setSaturation(params.saturation)
    // 色温 3000~8000 → -1~+1
    val tempNorm = (params.temperature - 5000f) / 3000f
    renderer.setTemperature(tempNorm)
    // 色调 -100~+100 → -1~+1
    val tintNorm = params.tint / 100f
    renderer.setTint(tintNorm)
    renderer.setBrightness(params.brightness)
    renderer.setRedAdjustment(params.redAdjustment)
    renderer.setGreenAdjustment(params.greenAdjustment)
    renderer.setBlueAdjustment(params.blueAdjustment)
}
```

---

## 6. UI 层改造

### 6.1 美颜面板新增标签页

```
┌─────────────────────────────────────────┐
│  [美颜]  [调色]  [风格]  [滤镜]          │
├─────────────────────────────────────────┤
│                                         │
│  调色页内容：                            │
│  ├─ 曝光      [-10  ==========  +10]   │
│  ├─ 对比度    [0   ==========  4  ]   │
│  ├─ 饱和度    [0   ==========  2  ]   │
│  ├─ 色温      [3000==========8000]   │
│  ├─ 色调      [-100==========+100]   │
│  ├─ 亮度      [-1  ==========  +1 ]   │
│  ├─ 红色      [0   ==========  2  ]   │
│  ├─ 绿色      [0   ==========  2  ]   │
│  └─ 蓝色      [0   ==========  2  ]   │
│                                         │
│  风格页内容：                            │
│  ├─ [无] [卡通] [素描] [色块] [浮雕]    │
│  └─ 强度      [0   ==========  1  ]   │
│                                         │
└─────────────────────────────────────────┘
```

### 6.2 新增 Composable

- `ColorGradePanel` — 调色面板
- `StyleEffectPanel` — 风格特效面板
- `ColorGradeSlider` — 带中心标记的滑块（如色温 5000K 为中点）

---

## 7. 实施路线图

### Phase 1: ColorGrade 整合到 BeautyPass（3 天）

**目标**: 在现有单 Pass Shader 中新增调色 uniform 和计算逻辑。

**任务清单**:
- [ ] `BeautyShaders.kt`: 在 FRAGMENT_SHADER_BEAUTY 末尾追加 `applyColorGrade()` 函数
- [ ] `BeautyShaders.kt`: 新增 9 个 uniform 声明
- [ ] `BeautyRenderer.kt`: 新增 uniform location 缓存和 setter
- [ ] `BeautyRenderer.kt`: 在 `onRender()` 中绑定新增 uniform
- [ ] `BeautyParams.kt`: 新增调色参数字段
- [ ] `BeautyParamsConverter.kt`: 新增参数映射逻辑
- [ ] 编译验证 + 真机测试（曝光/对比度/饱和度 基础功能）

### Phase 2: StyleEffectPass 独立 Pass（4 天）

**目标**: 实现 5 种风格特效的独立 Shader + FBO 链。

**任务清单**:
- [ ] 新增 `Framebuffer.kt` 离屏 FBO 封装
- [ ] 新增 `StyleEffect.kt` 枚举
- [ ] 新增 `StyleEffectShader.kt`（5 个 Shader 字符串 + 编译管理）
- [ ] `BeautyRenderer.kt`: 改造为多 Pass（增加 intermediate FBO）
- [ ] `BeautyRenderer.kt`: StylePass 按需绑定/绘制
- [ ] `BeautyParams.kt`: 新增风格参数字段
- [ ] 编译验证 + 真机测试（5 种风格特效）

### Phase 3: UI 面板（2 天）

**任务清单**:
- [ ] `ColorGradePanel.kt` — 调色滑块面板
- [ ] `StyleEffectPanel.kt` — 风格选择 + 强度滑块
- [ ] `BeautyBottomSheet.kt` — 新增 [调色] [风格] 标签页
- [ ] 主题色适配（MaterialTheme.colorScheme）
- [ ] strings.xml 三语言文案

### Phase 4: 参数持久化 + 预览/拍照一致性（1 天）

**任务清单**:
- [ ] `UserPreferences`: 新增调色/风格参数存储
- [ ] `GpuBeautyProcessor`: 拍照后处理复用调色参数
- [ ] 拍照与预览效果一致性验证

### Phase 5: 性能测试 + 低端机降级（2 天）

**任务清单**:
- [ ] 中/高端机：确认 ≥ 30fps，单帧 ≤ 16ms
- [ ] 低端机：若 StylePass 导致掉帧，自动降级为无风格
- [ ] 内存占用：确认 FBO 纹理内存 < 5MB（1280×720 × 4byte ≈ 3.5MB）
- [ ] Shader 编译时间：低端机首次编译 < 500ms

---

## 8. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Shader 编译失败（寄存器超限） | 高 | 调色计算极简，已评估 < 7% 增量；若超限则将调色移出为独立 Pass |
| 低端机 FBO 性能下降 | 中 | StylePass 默认关闭；开启时若 fps < 25 自动降级 |
| 风格特效与美颜 UV 变形冲突 | 中 | StylePass 在 BeautyPass **之后**执行（变形已完成，风格作用于最终图像） |
| 拍照与预览效果不一致 | 中 | Phase 4 专门验证；调色参数通过 `BeautyParamsConverter` 统一转换 |
| 3D LUT 后续替换 ColorMatrix | 低 | 当前方案兼容 3D LUT：ColorGradePass 末尾可直接替换为 `texture3D(uLUT, color)` |

---

## 9. 关键代码参考

### 9.1 GPUPixel Shader 源码位置

| 滤镜 | 文件路径 |
|------|---------|
| Exposure | `temp/gpupixel/src/filter/exposure_filter.cc` |
| Contrast | `temp/gpupixel/src/filter/contrast_filter.cc` |
| Saturation | `temp/gpupixel/src/filter/saturation_filter.cc`（需查找） |
| WhiteBalance | `temp/gpupixel/src/filter/white_balance_filter.cc`（需查找） |
| Toon | `temp/gpupixel/src/filter/toon_filter.cc` |
| Sketch | `temp/gpupixel/src/filter/sketch_filter.cc` |
| Posterize | `temp/gpupixel/src/filter/posterize_filter.cc` |
| Emboss | `temp/gpupixel/src/filter/emboss_filter.cc` |
| Crosshatch | `temp/gpupixel/src/filter/crosshatch_filter.cc`（需查找） |

### 9.2 大美丽关键文件

| 文件 | 路径 |
|------|------|
| 美颜 Shader | `beauty-engine/.../egl/BeautyShaders.kt` |
| 美颜渲染器 | `beauty-engine/.../egl/BeautyRenderer.kt` |
| 预览提供者 | `beauty-engine/.../egl/GlBeautyPreviewProvider.kt` |
| 参数定义 | `beauty-engine/.../api/BeautyParams.kt` |
| 参数转换器 | `app/.../camera/BeautyParamsConverter.kt` |
| 人脸检测 | `app/.../facedetect/MediaPipeFaceDetector.kt` |

---

## 10. 验收标准

### 10.1 功能验收

- [ ] 大美丽模式支持曝光/对比度/饱和度/色温/色调/亮度/RGB 调整
- [ ] 大美丽模式支持 Toon/Sketch/Posterize/Emboss/Crosshatch 风格特效
- [ ] 所有新增参数可通过 UI 实时调节，延迟 < 100ms
- [ ] 参数持久化到 `UserPreferences`，重启后恢复
- [ ] 拍照效果与预览效果一致（调色/风格均生效）

### 10.2 性能验收

- [ ] 中/高端机：预览 ≥ 30fps，单帧处理 ≤ 16ms
- [ ] 低端机：基础调色不导致掉帧；风格特效可关闭
- [ ] Shader 编译：首次启动 < 500ms（低端机）

### 10.3 质量验收

- [ ] 调色效果与 GPUPixel 引擎等价（同参数输出视觉一致）
- [ ] 风格特效与 GPUPixel 引擎等价
- [ ] 不破坏现有美颜功能（回归测试通过）

---

*文档版本: 1.0 | 作者: 小七 (AI Agent) | 日期: 2026-04-23*
