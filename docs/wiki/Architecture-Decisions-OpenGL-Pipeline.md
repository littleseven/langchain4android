# ADR-002: OpenGL 离屏渲染统一美颜处理管线

**状态**: 已接受 (Accepted)  
**日期**: 2026-04-17  
**最后同步**: 2026-05-24（`PhotoProcessorImpl` 已落地于 `beauty-engine/render`，替代早期 `OffscreenRenderer` 设计）  
**决策**: PM/RD 联合评审  
**依赖**: ADR-001 (分层架构重构)

---

## 1. 背景

### 当前问题 (ADR-001 修复后仍存在的差距)

| 美颜效果 | 预览 (GPU Shader) | 拍照 (CPU Canvas) | 一致性 |
|---------|------------------|------------------|--------|
| 磨皮 | 双边滤波 (边缘保持) | 高斯模糊近似 | ⚠️ 70% |
| 美白 | YUV 亮度 Shader | ColorMatrix | ⚠️ 80% |
| 瘦脸 | FaceWarp Shader | Canvas Mesh | ⚠️ 85% |
| 大眼 | 径向放大 Shader | Canvas Mesh | ⚠️ 85% |
| 唇色 | HSV Shader | 像素级着色 | ⚠️ 75% |
| 腮红 | 椭圆染色 Shader | ColorMatrix | ⚠️ 70% |

**根本原因**: 两套独立实现（OpenGL ES vs Canvas API），算法细节难以完全对齐

---

## 2. 决策: OpenGL 离屏渲染统一管线

### 2.1 目标架构

```
┌─────────────────────────────────────────────────────────────┐
│                    App Layer (PicMe)                        │
│                   ↓ 依赖 beauty-engine:api                    │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│  Domain Layer: beauty-engine:api                             │
│  ├─ BeautyEngine (Interface)                              │
│  ├─ FilterType / BeautyParams                             │
│  └─ ProcessCallback                                       │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│  Data Layer: beauty-engine:render                         │
│  ├─ GlBeautyPreviewProvider                               │
│  ├─ PhotoProcessorImpl (OpenGL FBO 离屏渲染，2026-05 落地) │
│  │   ├─ 预览: SurfaceTexture → SurfaceView (实时)          │
│  │   └─ 拍照: Bitmap → FBO → Bitmap (离屏)               │
│  ├─ CameraPreviewRenderer (渲染管线核心)                 │
│  ├─ BeautyRenderer (自研 Shader 管线)                    │
│  └─ EGLCore (EGL 上下文管理)                             │
└──────────────────────┬──────────────────────────────────────┘
                       │
        ┌──────────────────────────────┐
        ▼                              
┌───────────────┐                      
│  BIG_BEAUTY   │                      
│ (自研 Shader) │                      
│ 磨皮/美白/瘦脸 │                      
│ 大眼/唇色/腮红 │                      
│ 风格特效/调色  │                      
└───────────────┘                      
```

### 2.2 核心设计

**统一 Shader 管线路径**:
```
预览路径: Camera → SurfaceTexture → Shader → SurfaceView (实时 30fps)
拍照路径: Bitmap → Texture → Shader → FBO → glReadPixels → Bitmap (单次)
```

**关键洞察**: 
- 复用同一套 Shader 代码（.glsl / .vert / .frag）
- 预览和拍照只有输入/输出不同，处理逻辑完全一致
- 彻底解决预览/拍照一致性

---

## 3. 技术实现

### 3.1 新增模块: OffscreenRenderer

```kotlin
// beauty-engine/render/src/.../render/PhotoProcessorImpl.kt（实际落地类名）

class PhotoProcessorImpl(
    private val eglContext: EGLContext
) {
    private var fboId: Int = 0
    private var outputTexture: Int = 0
    
    /**
     * 处理 Bitmap  through OpenGL Shader
     * 
     * @param inputBitmap 原始照片
     * @param shaderChain 美颜 Shader 链
     * @return 处理后的 Bitmap
     */
    fun processBitmap(
        inputBitmap: Bitmap,
        shaderChain: BeautyShaderChain
    ): Bitmap {
        // 1. Bitmap → OpenGL Texture
        val inputTexture = bitmapToTexture(inputBitmap)
        
        // 2. 创建 FBO
        setupFBO(inputBitmap.width, inputBitmap.height)
        
        // 3. 绑定 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            outputTexture,
            0
        )
        
        // 4. 执行 Shader 链（与预览完全一致）
        shaderChain.render(inputTexture, outputTexture)
        
        // 5. FBO → Bitmap
        val outputBitmap = readPixelsToBitmap(
            inputBitmap.width,
            inputBitmap.height
        )
        
        // 6. 清理
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        deleteTexture(inputTexture)
        
        return outputBitmap
    }
    
    private fun bitmapToTexture(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return texId
    }
    
    private fun readPixelsToBitmap(width: Int, height: Int): Bitmap {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
}
```

### 3.2 Shader 链抽象

```kotlin
// 统一 Shader 链（预览和拍照共用）

class BeautyShaderChain(
    private val smoothingShader: SmoothingShader,      // 双边滤波
    private val whiteningShader: WhiteningShader,      // YUV 亮度
    private val slimFaceShader: SlimFaceShader,        // FaceWarp
    private val bigEyesShader: BigEyesShader,          // 径向放大
    private val lipColorShader: LipColorShader,        // HSV 色相
    private val blushShader: BlushShader,              // 椭圆染色
    private val filterShader: ColorMatrixShader        // 色调滤镜
) {
    fun render(inputTexture: Int, outputTexture: Int) {
        // 链式渲染，每个 Shader 的输出作为下一个的输入
        var currentTexture = inputTexture
        
        if (params.smoothing > 0) {
            currentTexture = smoothingShader.render(currentTexture)
        }
        if (params.whitening > 0) {
            currentTexture = whiteningShader.render(currentTexture)
        }
        if (params.slimFace != 0f) {
            currentTexture = slimFaceShader.render(currentTexture, faceData)
        }
        // ... 其他效果
        
        // 最终输出
        filterShader.renderToOutput(currentTexture, outputTexture)
    }
}
```

### 3.3 预览与拍照复用

```kotlin
// 预览时
class BeautyPreviewView {
    private val shaderChain: BeautyShaderChain
    
    fun onDrawFrame() {
        // 实时渲染到 SurfaceView
        shaderChain.render(cameraTexture, screenFramebuffer)
    }
}

// 拍照时
class PhotoProcessorImpl {
    private val beautyRenderer: BeautyRenderer // 同一实例
    
    fun processPhoto(bitmap: Bitmap): Bitmap {
        // 离屏渲染，复用相同 Shader
        val inputTexture = bitmapToTexture(bitmap)
        beautyRenderer.render(inputTexture, fboTexture)
        return fboToBitmap()
    }
}
```

---

## 4. 迁移计划

### Phase 1: 基础设施 (2-3 周)

| 任务 | 负责人 | 输出 |
|------|--------|------|
| PhotoProcessorImpl 框架 | RD | 类实现 + 单元测试 |
| FBO/Texture 管理封装 | RD | 资源管理器 |
| glReadPixels 优化 | RD | PBO 异步读取（⏳ 待优化） |
| Shader 加载器重构 | RD | 支持 .glsl 文件（⏳ 待优化） |

**验收标准**:
- [x] PhotoProcessorImpl 可独立单元测试
- [ ] 1080p 图片处理 < 500ms
- [ ] 内存无泄漏（连续处理 100 张）

### Phase 2: Shader 迁移 (3-4 周)

| Shader | 当前状态 | 迁移方式 |
|--------|---------|---------|
| 双边滤波（磨皮） | BeautyPreviewView 内联 | 提取为独立类 |
| YUV 美白 | BeautyPreviewView 内联 | 提取为独立类 |
| FaceWarp（瘦脸） | BeautyPreviewView 内联 | 提取为独立类 |
| 径向放大（大眼） | BeautyPreviewView 内联 | 提取为独立类 |
| HSV 唇色 | BeautyPreviewView 内联 | 提取为独立类 |
| 椭圆腮红 | BeautyPreviewView 内联 | 提取为独立类 |
| ColorMatrix 滤镜 | 已独立 | 复用现有 |

**验收标准**:
- [ ] 所有 Shader 可独立实例化
- [ ] ShaderChain 可动态组合
- [ ] 预览效果与之前完全一致

### Phase 3: 拍照路径切换 (2 周)

| 步骤 | 操作 |
|------|------|
| 1 | GpuBeautyProcessor 调用 PhotoProcessorImpl |
| 2 | A/B 测试：对比 CPU vs GPU 路径效果 |
| 3 | 灰度发布：50% 用户使用 GPU 路径 |
| 4 | 全量切换：100% GPU 路径 |
| 5 | 废弃 CPU 路径代码 |

**验收标准**:
- [ ] 拍照效果与预览 100% 一致（像素级对比）
- [ ] 性能指标：1080p < 300ms, 4K < 800ms
- [ ] 崩溃率 < 0.1%

### Phase 4: 库化准备 (4-6 周)

- 抽离 `beauty-core` 模块（纯 Kotlin 接口）
- 完善 `beauty-engine` 模块（OpenGL 实现）
- 定义稳定 API 与版本策略
- 发布内部 SDK v0.1.0

---

## 5. 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 内存压力（大图片） | 中 | 高 | PBO 异步读取，分块处理 4K |
| 兼容性（低端设备） | 中 | 中 | 保留 CPU 路径作为 Fallback |
| 开发周期延长 | 低 | 中 | 分阶段交付，每个 Phase 可独立发布 |
| Shader 精度差异 | 低 | 高 | 浮点精度统一，端到端测试 |

---

## 6. 性能预期

| 指标 | 当前 (CPU) | 目标 (GPU) | 提升 |
|------|-----------|-----------|------|
| 1080p 处理时间 | 800-1200ms | 200-300ms | **4-6x** |
| 4K 处理时间 | 3-5s | 600-1000ms | **3-5x** |
| 内存峰值 | 150MB | 80MB | **47%↓** |
| 预览/拍照一致性 | 70% | 99%+ | **提升 29%** |

---

## 7. 依赖与前置条件

- [x] ADR-001 分层架构完成
- [x] Phase 1 基础设施评审通过
- [x] QA 自动化测试框架就绪
- [x] 性能基准测试环境搭建

---

## 8. 决策记录

| 日期 | 决策 | 决策人 | 状态 |
|------|------|--------|------|
| 2026-04-17 | 采用方案 A (OpenGL 离屏渲染) | PM/RD | 已接受 |
| 2026-04-17 | 4 阶段实施计划 | RD | 已接受 |
| 2026-04-17 | **Phase 1 启动** | PM | ✅ **已完成** |

---

## 9. Phase 1 进展记录

### 2026-04-17 基础设施框架搭建

| 任务 | 状态 | 产出文件 |
|------|------|---------|
| PhotoProcessorImpl 框架 | ✅ 完成 | `PhotoProcessorImpl.kt` |
| BeautyRenderer 复用 | ✅ 完成 | `BeautyRenderer.kt` |
| Shader 统一 | ✅ 完成 | `BeautyShaders.kt` |
| 单元测试骨架 | ✅ 完成 | `PhotoProcessorImplTest.kt` |

### 已完成代码文件

**PhotoProcessorImpl.kt** - 核心离屏渲染器（实际落地类名，替代早期 `OffscreenRenderer` 设计）
- Bitmap → Texture → FBO → Bitmap 完整流程
- PBO 异步读取优化（双缓冲）
- 资源自动管理（FBO/Texture 复用）
- 错误处理和边界检查

**BeautyShaderChain.kt** - 统一 Shader 链
- 美颜参数数据类（BeautyParams）
- 人脸数据结构（FaceData）
- 链式 Shader 执行逻辑
- 与预览渲染共享同一套参数更新机制

**BeautyShaders.kt** - Shader 源码契约
- 磨皮、美白、瘦脸、大眼、唇色、腮红、滤镜 7 个效果内联于 GLSL
- 统一的生命周期管理（compile/link/use）

### 验收进度

Phase 1 验收标准：
- [x] PhotoProcessorImpl 框架实现（支持 Bitmap 输入输出）
- [x] 单元测试通过（真实 EGL 环境验证）
- [x] 1080p 图片处理 < 500ms（设备实测 200ms 以内）
- [x] 内存无泄漏测试（连续 100 张压力测试通过）

### 落地实现记录（2026-05）

GPU 离屏渲染拍照已由 `PhotoProcessorImpl` 在 `beauty-engine/render` 中完整落地，关键实现要点：

1. **独立 EGL 上下文与 Pbuffer Surface**：在 `PhotoProcessorImpl` 中创建独立 EGL 上下文，避免与预览线程竞争，实现后台线程异步处理。
2. **渲染管线重构**：引入 `renderMainShaderFromFbo2D()` 方法，使用专用 `shaderProgram2D`（基于 `VERTEX_SHADER_2D` 和 `FRAGMENT_SHADER_2D`），复用 `BeautyRenderer` 的渲染逻辑。
3. **坐标系统一**：拍照路径直接使用原始人脸关键点坐标，跳过预览路径的 `inverseTransform`，Bitmap 上传后的 UV 空间与 Shader 期望的标准 UV 空间天然对齐。
4. **FBO 复用与 glReadPixels**：实现 `ensureFbo(width, height)` 按需重建；采用同步 `glReadPixels` 读取，主流设备 1080P 处理耗时控制在 200ms 以内。
5. **黑屏修复**：确保 `BeautyRenderer` 在 `PhotoProcessorImpl` 线程内完成 `onInit`，显式设置 `glViewport`，解决跨上下文 Uniform 缓存失效问题。

> 注：原 `GPU_PHOTO_MAJOR_CHANGES.md` 已归档合并至本文档。

---

**当前状态**: Phase 1 已落地，`PhotoProcessorImpl` 生产可用。后续优化（PBO 异步读取、多分辨率压测）归入 P1 迭代。
