# GPU 拍照功能实现指南

**状态**: 设计中  
**版本**: v0.1 (快速验证版)  
**最后更新**: 2026-05-03

---

## 📋 概述

本文档描述如何将大美丽模式的拍照从 CPU Canvas 处理迁移到 GPU 离屏渲染，确保预览与拍照效果完全一致。

---

## 🎯 核心目标

1. **效果一致性**: 预览和拍照使用同一套 Shader 链
2. **性能优化**: 利用 PBO 异步读取，减少阻塞
3. **架构清晰**: 通过 BeautyShaderChain 接口解耦

---

## 🏗️ 架构设计

### 组件关系图

```
┌──────────────────────────────────────────────────────┐
│              ImageProcessor.takePhoto                 │
├──────────────────────────────────────────────────────┤
│                                                       │
│  1. CameraX 捕获原始照片 (Bitmap)                     │
│     ↓                                                 │
│  2. 旋转/裁剪/镜像                                    │
│     ↓                                                 │
│  3. 判断美颜策略                                      │
│     ├── GPUPixel → gpupixelProvider.processPhoto()   │
│     └── BIG_BEAUTY → [新增 GPU 路径]                  │
│          ↓                                            │
│  4. [GPU 路径] OffscreenRenderer                      │
│     ├── Bitmap → Texture                              │
│     ├── BeautyShaderChain.render() ← 关键             │
│     │   └── 复用 BeautyRenderer 的渲染管线            │
│     ├── FBO → Bitmap (PBO 异步)                      │
│     └── 返回处理后的 Bitmap                           │
│          ↓                                            │
│  5. 保存照片                                          │
│                                                       │
└──────────────────────────────────────────────────────┘
```

### 关键接口

#### BeautyShaderChain

```kotlin
interface BeautyShaderChain {
    fun render(
        inputTextureId: Int,
        outputTextureId: Int,
        width: Int,
        height: Int
    ): Boolean
    
    fun setBeautyParams(...)
    fun setFaceLandmarks(landmarks106: FloatArray?, hasFace: Boolean)
    fun setFilterType(filterType: String)
    fun release()
}
```

#### OffscreenRenderer

```kotlin
class OffscreenRenderer(eglContext: EGLContext) {
    fun processBitmap(
        inputBitmap: Bitmap,
        shaderChain: BeautyShaderChain
    ): Bitmap
}
```

---

## 🔧 实现步骤

### Step 1: 创建 BeautyShaderChain 接口 ✅

**文件**: `beauty-engine/src/main/java/com/picme/beauty/internal/BeautyShaderChain.kt`

**状态**: ✅ 已完成

### Step 2: 实现 BeautyRendererAsShaderChain ⚠️

**文件**: `beauty-engine/impl/src/main/java/com/picme/beauty/internal/BeautyRendererAsShaderChain.kt`

**状态**: ⚠️ 框架已创建，待完善

**TODO**:
- [ ] 替换 `Any` 为实际的 `BeautyRenderer` 类型
- [ ] 实现 `render()` 方法，调用 BeautyRenderer 的渲染逻辑
- [ ] 同步美颜参数到 BeautyRenderer
- [ ] 同步人脸关键点到 BeautyRenderer
- [ ] 处理多 Pass 渲染（美颜 → 风格特效）

### Step 3: 修改 ImageProcessor.takePhoto ⏸️

**文件**: `app/src/main/java/com/picme/core/image/ImageProcessor.kt`

**当前状态**: 
- ✅ GPUPixel 模式已有 GPU 拍照路径
- ❌ BIG_BEAUTY 模式仍使用 CPU Canvas

**需要添加的代码**（在 Line 630 之后）:

```kotlin
// [新增] 大美丽模式的 GPU 拍照路径
if (beautyStrategy == BeautyStrategy.BIG_BEAUTY && photoProcessor != null) {
    Logger.d("ImageProcessor", "BIG_BEAUTY GPU mode: processing photo with OffscreenRenderer")
    
    try {
        // 1. 获取 EGL 上下文（需要从 BeautyPreviewView 或 CameraPreviewRenderer 获取）
        val eglContext = getSharedEGLContext() // TODO: 实现
        
        // 2. 创建 OffscreenRenderer
        val offscreenRenderer = OffscreenRenderer(eglContext)
        
        // 3. 创建 BeautyShaderChain 适配器
        val beautyRenderer = getBeautyRenderer() // TODO: 实现
        val shaderChain = BeautyRendererAsShaderChain(beautyRenderer)
        
        // 4. 设置美颜参数
        shaderChain.setBeautyParams(
            smoothingStrength = beauty.smoothing / 100f,
            whiteningStrength = beauty.whitening / 100f,
            slimFaceStrength = beauty.slimFace / 100f,
            bigEyeStrength = beauty.bigEye / 100f,
            lipColorStrength = beauty.lipColor / 100f,
            blushStrength = beauty.blush / 100f,
            eyebrowStrength = beauty.eyebrow / 100f
        )
        
        // 5. 设置人脸关键点（如果有）
        if (cachedFaces.isNotEmpty()) {
            val landmarks106 = convertFacesToLandmarks106(cachedFaces, rotatedBitmap.width, rotatedBitmap.height)
            shaderChain.setFaceLandmarks(landmarks106, true)
        } else {
            shaderChain.setFaceLandmarks(null, false)
        }
        
        // 6. 设置滤镜类型
        shaderChain.setFilterType(filter.name)
        
        // 7. 执行 GPU 渲染
        val finalBitmap = offscreenRenderer.processBitmap(rotatedBitmap, shaderChain)
        
        // 8. 清理资源
        shaderChain.release()
        offscreenRenderer.release()
        
        // 9. 保存照片
        val faceId = if (cachedFaces.isNotEmpty()) "person_${cachedFaces.size}" else null
        saveBitmapToMediaStore(context, finalBitmap, name, viewModel, cachedFaces.isNotEmpty(), faceId, mode)
        
        return
        
    } catch (e: Exception) {
        Logger.e("ImageProcessor", "GPU photo processing failed, fallback to CPU", e)
        // 降级到 CPU 处理
    }
}
```

### Step 4: 实现 EGL 上下文共享 ⏸️

**问题**: OffscreenRenderer 需要与预览渲染共用同一 EGL 上下文。

**解决方案**:

方案 A: **共享 EGLContext**
```kotlin
// 在 BeautyPreviewView 中
val sharedContext = eglManager.createSharedContext()

// 传递给 ImageProcessor
imageProcessor.setSharedEGLContext(sharedContext)
```

方案 B: **在主线程执行**
```kotlin
// 确保在渲染线程执行
Handler(Looper.getMainLooper()).post {
    val finalBitmap = offscreenRenderer.processBitmap(rotatedBitmap, shaderChain)
    // ...
}
```

**推荐**: 方案 A（更灵活，支持后台处理）

### Step 5: 人脸关键点转换 ⏸️

需要将 ML Kit 的 `Face` 对象转换为 106 点数组：

```kotlin
private fun convertFacesToLandmarks106(
    faces: List<Face>,
    imageWidth: Int,
    imageHeight: Int
): FloatArray? {
    if (faces.isEmpty()) return null
    
    val face = faces[0] // 取第一张脸
    
    // TODO: 将 Face 的关键点映射到 106 点
    // 参考: docs/face-detection/INSIGHTFACE_106_MAPPING.md
    
    val landmarks106 = FloatArray(106 * 2) // x, y 交替
    
    // 示例：提取左眼中心
    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
    if (leftEye != null) {
        landmarks106[0] = leftEye.position.x / imageWidth
        landmarks106[1] = leftEye.position.y / imageHeight
    }
    
    // ... 填充其他关键点
    
    return landmarks106
}
```

---

## 🧪 测试计划

### 测试用例

| 用例 | 前置条件 | 预期结果 |
|------|---------|---------|
| **TC1**: 后置摄像头拍照 | 无美颜 | 照片清晰，无变形 |
| **TC2**: 后置摄像头 + 磨皮 | 磨皮强度 50% | 皮肤平滑，细节保留 |
| **TC3**: 前置摄像头拍照 | 无美颜 | 照片镜像，与预览一致 |
| **TC4**: 前置 + 瘦脸 | 瘦脸强度 30% | 脸部变瘦，位置准确 |
| **TC5**: 前后置切换拍照 | 先拍后置再拍前置 | 两张照片效果独立正确 |
| **TC6**: 超大分辨率 (4K) | 4096x3072 | 正常处理，无 OOM |
| **TC7**: 无人脸场景 | 拍摄风景 | 不崩溃，保存原图 |
| **TC8**: 多人脸场景 | 3 个人脸 | 所有人脸都应用美颜 |

### 验证方法

1. **视觉效果对比**
   ```bash
   # 截取预览画面
   adb exec-out screencap -p > preview.png
   
   # 拍照后拉取照片
   adb pull /sdcard/Pictures/PicMe/ latest_photo.jpg
   
   # 人工对比或使用图像相似度算法
   ```

2. **性能指标**
   - 拍照耗时：< 500ms（1080p）
   - 内存占用：< 200MB
   - GPU 利用率：< 80%

3. **日志检查**
   ```
   D/PicMe:ImageProcessor: BIG_BEAUTY GPU mode: processing photo with OffscreenRenderer
   D/PicMe:OffscreenRenderer: Created FBO 'ping': 1080x1920
   D/PicMe:BeautyRendererAdapter: render: inputTex=1, outputTex=2, size=1080x1920
   I/PicMe:ImageProcessor: GPU photo processing success, saved to MediaStore
   ```

---

## ⚠️ 已知问题和限制

### 当前限制

1. **BeautyRenderer 集成未完成**
   - 需要分析 BeautyRenderer 的内部结构
   - 确定如何暴露渲染接口

2. **EGL 上下文管理复杂**
   - 需要确保线程安全
   - 避免上下文冲突

3. **人脸关键点映射未实现**
   - ML Kit Face → 106 点的转换逻辑需要开发
   - 需要考虑前后置镜像

### 风险点

- **性能风险**: 如果 PBO 不可用，回退到 `glReadPixels` 可能较慢
- **兼容性风险**: 部分低端设备可能不支持某些 OpenGL ES 特性
- **内存风险**: 大分辨率图片可能导致 OOM

---

## 📊 进度跟踪

| 任务 | 状态 | 预计完成时间 |
|------|------|------------|
| 创建 BeautyShaderChain 接口 | ✅ 完成 | 2026-05-03 |
| 实现 BeautyRendererAsShaderChain | ⚠️ 进行中 | 2026-05-04 |
| 修改 ImageProcessor.takePhoto | ⏸️ 待开始 | 2026-05-05 |
| 实现 EGL 上下文共享 | ⏸️ 待开始 | 2026-05-06 |
| 实现人脸关键点转换 | ⏸️ 待开始 | 2026-05-07 |
| 单元测试 | ⏸️ 待开始 | 2026-05-08 |
| 真机测试 | ⏸️ 待开始 | 2026-05-09 |
| 性能优化 | ⏸️ 待开始 | 2026-05-10 |
| 文档完善 | ⏸️ 待开始 | 2026-05-11 |

---

## 🔗 相关文档

- [COORDINATE_SYSTEM_STANDARD.md](./COORDINATE_SYSTEM_STANDARD.md) - 坐标系规范
- [ADR-002-opengl-offscreen-unified-pipeline.md](./ADR-002-opengl-offscreen-unified-pipeline.md) - OpenGL 离屏渲染统一管线
- [BIG_BEAUTY_TECH_SPEC.md](./BIG_BEAUTY_TECH_SPEC.md) - 大美丽引擎技术规范
- [INSIGHTFACE_106_MAPPING.md](./face-detection/INSIGHTFACE_106_MAPPING.md) - 106 点映射

---

**维护者**: PicMe AI Team  
**下次更新**: 完成 BeautyRenderer 集成后
