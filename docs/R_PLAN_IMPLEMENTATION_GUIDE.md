# R 计划实施指南

## 1. 核心问题诊断

### 1.1 当前状态（黑屏问题）

**症状**：
- ✅ EGL 初始化成功
- ✅ Shader 编译成功
- ✅ SurfaceTexture 创建成功
- ✅ 渲染线程启动
- ❌ **相机预览黑屏**
- ❌ **`updateTexImage()` 失败**
- ❌ **没有 "Camera bound" 日志**

**错误信息**：
```
java.lang.IllegalStateException: Unable to update texture contents
  at android.graphics.SurfaceTexture.nativeUpdateTexImage(Native Method)
  at android.graphics.SurfaceTexture.updateTexImage(SurfaceTexture.java:249)
  at CameraPreviewRenderer.startRendering(CameraPreviewRenderer.kt:200)
```

### 1.2 根本原因分析

**问题 1：CameraX 没有调用 SurfaceProvider**

日志中没有：
- `Camera bound`
- `SurfaceProvider called`
- `Created Surface from renderer`

这说明**CameraX 根本没有尝试绑定我们的 Surface**。

**可能原因**：
1. SurfaceTexture 还没有准备好，CameraX 拒绝绑定
2. Surface 的创建时机不对
3. CameraX 的 Preview 配置有问题

**问题 2：渲染线程启动太早**

`updateTexImage()` 失败说明：
- SurfaceTexture 还没有接收到相机帧
- 渲染线程在相机开始输出之前就尝试更新纹理

---

## 2. 解决方案

### 2.1 方案 A：修复当前架构（推荐优先尝试）

**核心思路**：确保 CameraX 能够成功绑定到我们的 Surface

#### 步骤 1：检查 SurfaceTexture 状态

```kotlin
// 在 BeautyPreviewView 中
override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    Log.d(TAG, "Surface available: ${width}x${height}, id=${surface.id}")
    
    this.surfaceTexture = surface
    renderer.init(this)
    
    // 关键：等待 SurfaceTexture 完全初始化
    surface.setDefaultBufferSize(width, height)
}
```

#### 步骤 2：延迟创建 Surface

```kotlin
// 在 BeautyPreviewView 中
private var surfaceCreated = false

fun getSurfaceForCamera(): Surface? {
    val st = renderer.getSurfaceTexture()
    if (st == null) {
        Log.w(TAG, "SurfaceTexture not ready")
        return null
    }
    
    // 检查 SurfaceTexture 是否有效
    if (!st.isValid) {
        Log.w(TAG, "SurfaceTexture is invalid")
        return null
    }
    
    if (!surfaceCreated) {
        Log.d(TAG, "Creating Surface for CameraX")
        surfaceCreated = true
        
        // 设置缓冲区大小（关键！）
        st.setDefaultBufferSize(1920, 1080)
        
        // 创建 Surface
        val surface = Surface(st)
        Log.d(TAG, "Surface created: ${surface.hashCode()}")
        
        // 启动渲染
        renderer.setRenderSurface(surface)
    }
    
    return Surface(st)
}
```

#### 步骤 3：修复 CameraX 配置

```kotlin
// 在 CameraScreen.kt 中
val preview = Preview.Builder()
    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
    .build()
    .also { previewUseCase ->
        val surface = beautyPreviewView.getSurfaceForCamera()
        Log.d("PicMe:Camera", "Surface for camera: ${surface?.hashCode()}")
        
        if (surface != null) {
            previewUseCase.setSurfaceProvider(cameraExecutor) { outputSurface ->
                Log.d("PicMe:Camera", "SurfaceProvider called")
                surface  // 返回我们的 Surface
            }
        } else {
            Log.e("PicMe:Camera", "Surface not ready, using fallback")
            // 使用默认 Surface（黑屏）
        }
    }
```

#### 步骤 4：添加错误恢复

```kotlin
// 在 CameraPreviewRenderer 中
private fun startRendering() {
    renderThread = Thread {
        var retryCount = 0
        while (isRendering) {
            try {
                surfaceTexture?.updateTexImage()
                retryCount = 0  // 重置重试计数
                
                // ... 正常渲染流程
                
            } catch (e: IllegalStateException) {
                retryCount++
                Log.e(TAG, "Render error (retry $retryCount): ${e.message}")
                
                if (retryCount > 10) {
                    Log.e(TAG, "Too many retries, stopping render")
                    break
                }
                
                Thread.sleep(100)  // 等待一会儿再试
            }
        }
    }.apply {
        name = "CameraPreviewRender"
        priority = Thread.MAX_PRIORITY
        start()
    }
}
```

### 2.2 方案 B：使用 TextureView 的 SurfaceTexture（备选）

如果方案 A 失败，尝试直接使用 TextureView 的 SurfaceTexture：

```kotlin
// 在 CameraPreviewRenderer 中
fun init(view: View) {
    // ... EGL 初始化
    
    // 使用 TextureView 的 SurfaceTexture
    if (view is TextureView) {
        val textureViewSurface = view.surfaceTexture
        if (textureViewSurface != null) {
            Log.d(TAG, "Using TextureView's SurfaceTexture: ${textureViewSurface.id}")
            surfaceTexture = textureViewSurface
        }
    }
    
    if (surfaceTexture == null) {
        // 创建我们自己的 SurfaceTexture
        createExternalTexture()
        surfaceTexture = SurfaceTexture(textureId)
    }
}
```

### 2.3 方案 C：降级到离线美颜（保底方案）

如果实时预览无法实现，实现离线美颜：

```kotlin
// 拍照时应用美颜
fun applyBeautyToImage(imageProxy: ImageProxy, beautySettings: BeautySettings) {
    // 1. 获取 ImageProxy 的 Bitmap
    val bitmap = imageProxy.toBitmap()
    
    // 2. 应用美颜算法
    val processedBitmap = applyBeautyOnGPU(bitmap, beautySettings)
    
    // 3. 保存处理后的图片
    saveImage(processedBitmap)
    
    imageProxy.close()
}
```

---

## 3. 调试检查清单

### 3.1 启动阶段检查

- [ ] BeautyPreviewView 是否被添加到布局？
- [ ] `onSurfaceTextureAvailable` 是否被调用？
- [ ] `renderer.init()` 是否成功？
- [ ] 外部纹理 ID 是否创建？（应该是非 0 值）
- [ ] SurfaceTexture 是否创建成功？

### 3.2 CameraX 绑定检查

- [ ] `getSurfaceForCamera()` 是否被调用？
- [ ] 返回的 Surface 是否非 null？
- [ ] `setSurfaceProvider` 是否被调用？
- [ ] SurfaceProvider 的回调是否执行？
- [ ] 是否有 "Camera bound" 日志？

### 3.3 渲染阶段检查

- [ ] 渲染线程是否启动？
- [ ] `updateTexImage()` 是否成功？
- [ ] 是否有 "New frame available" 回调？
- [ ] 渲染是否每 16ms 执行一次？
- [ ] TextureView 是否显示内容？

### 3.4 关键日志标签

```kotlin
// 必须看到的日志：
D/PicMe:BeautyPreviewView: Surface texture available
D/PicMe:CameraPreview: External texture created: X (X != 0)
D/PicMe:CameraPreview: SurfaceTexture created with texture ID: X
D/PicMe:Camera: Creating Surface for CameraX
D/PicMe:Camera: SurfaceProvider called
D/PicMe:Camera: Camera bound
D/PicMe:CameraPreview: Render thread started
D/PicMe:CameraPreview: Rendered X frames
```

---

## 4. 常见问题与解决方案

### 4.1 问题：External texture created: 0

**原因**：纹理 ID 为 0，说明纹理创建失败

**解决**：
```kotlin
fun createExternalTexture() {
    val textureIds = IntArray(1)
    GLES20.glGenTextures(1, textureIds, 0)
    textureId = textureIds[0]
    
    Log.d(TAG, "Texture ID: $textureId")  // 必须是非 0 值
    
    if (textureId == 0) {
        Log.e(TAG, "Failed to create texture!")
        return
    }
    
    // ... 绑定纹理
}
```

### 4.2 问题：SurfaceTexture 为 null

**原因**：初始化顺序错误

**解决**：
```kotlin
// 确保顺序正确
override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    // 1. 先保存 SurfaceTexture
    this.surfaceTexture = surface
    
    // 2. 再初始化 Renderer
    renderer.init(this)
    
    // 3. 最后设置参数
    updateBeautyParams()
}
```

### 4.3 问题：没有 "Camera bound" 日志

**原因**：CameraX 绑定失败

**解决**：
```kotlin
// 检查 CameraX 绑定代码
try {
    cameraProvider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        preview,  // 确保 preview 不为 null
        imageCapture
    )
    Log.d("PicMe:Camera", "Camera bound successfully")
} catch (e: Exception) {
    Log.e("PicMe:Camera", "Binding failed: ${e.message}", e)
}
```

### 4.4 问题：渲染线程启动但立即停止

**原因**：`updateTexImage()` 持续失败

**解决**：
```kotlin
// 添加重试机制
var consecutiveErrors = 0
while (isRendering) {
    try {
        surfaceTexture?.updateTexImage()
        consecutiveErrors = 0
        // ... 渲染
    } catch (e: Exception) {
        consecutiveErrors++
        Log.e(TAG, "Error $consecutiveErrors: ${e.message}")
        
        if (consecutiveErrors > 30) {
            Log.e(TAG, "Too many errors, stopping")
            break
        }
        
        Thread.sleep(100)
    }
}
```

---

## 5. 性能调试

### 5.1 帧率监控

```kotlin
private fun startRendering() {
    var frameCount = 0
    var lastFpsTime = System.currentTimeMillis()
    
    renderThread = Thread {
        while (isRendering) {
            // ... 渲染逻辑
            
            frameCount++
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFpsTime >= 1000) {
                Log.d(TAG, "FPS: $frameCount")
                frameCount = 0
                lastFpsTime = currentTime
            }
            
            Thread.sleep(16)
        }
    }
}
```

### 5.2 内存监控

```kotlin
// 在 BeautyPreviewView 中
override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    
    val runtime = Runtime.getRuntime()
    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
    Log.d(TAG, "Memory used: ${usedMemory / 1024 / 1024} MB")
}
```

---

## 6. 降级策略

### 6.1 检测失败并降级

```kotlin
// 在 CameraScreen 中
var useFallbackPreview by remember { mutableStateOf(false) }

LaunchedEffect(Unit) {
    // 等待 5 秒，如果还没有看到渲染日志，切换到降级方案
    delay(5000)
    
    if (!isRenderingSuccessfully) {
        Log.e("PicMe:Camera", "R plan failed, switching to fallback")
        useFallbackPreview = true
    }
}

// 降级方案：使用普通的 PreviewView
val previewView = if (useFallbackPreview) {
    // 创建普通 PreviewView（无美颜预览）
    PreviewView(context)
} else {
    // 使用 BeautyPreviewView
    beautyPreviewView
}
```

### 6.2 离线美颜实现

```kotlin
// 拍照时应用美颜
fun capturePhoto(beautySettings: BeautySettings) {
    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
        // 1. 转换为 Bitmap
        val bitmap = imageProxy.toBitmap()
        
        // 2. 应用美颜（在后台线程）
        val processedBitmap = withContext(Dispatchers.Default) {
            applyBeautyEffect(bitmap, beautySettings)
        }
        
        // 3. 保存图片
        saveProcessedImage(processedBitmap)
        
        imageProxy.close()
    }
}
```

---

## 7. 下一步行动

### 立即执行（今天）

1. **添加详细日志**
   - 在关键位置添加日志
   - 确认每个步骤是否执行
   - 定位第一个失败的点

2. **修复 Surface 创建时机**
   - 确保在 CameraX 请求时才创建
   - 设置正确的缓冲区大小
   - 验证 Surface 有效性

3. **测试 CameraX 绑定**
   - 确认 SurfaceProvider 被调用
   - 检查返回的 Surface 是否正确
   - 验证相机是否开始输出帧

### 明天执行

4. **优化渲染性能**
   - 测试不同分辨率下的帧率
   - 优化 Shader 算法
   - 减少内存占用

5. **实现美颜算法**
   - 磨皮算法优化
   - 美白算法调整
   - 参数实时调节

### 本周执行

6. **兼容性测试**
   - 测试不同 Android 版本
   - 测试不同厂商设备
   - 收集性能数据

7. **编写文档**
   - 更新 AGENTS.md
   - 记录最佳实践
   - 创建故障排查指南

---

## 8. 总结

R 计划的核心挑战是**协调 CameraX、SurfaceTexture 和 OpenGL ES 的生命周期**。

**关键成功因素**：
1. ✅ 正确的初始化顺序
2. ✅ 合适的 Surface 创建时机
3. ✅ EGL 上下文的正确管理
4. ✅ 渲染线程与相机帧的同步

**失败应对**：
- 准备降级方案（离线美颜）
- 充分的日志记录
- 快速失败和恢复机制

**预期结果**：
- 成功：实现 60fps 实时美颜预览
- 失败：降级到离线美颜，不影响拍照功能

无论如何，我们都能为用户提供美颜功能，只是实现方式不同。
