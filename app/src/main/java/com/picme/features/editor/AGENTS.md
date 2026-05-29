# 图片编辑模块技术实现规范 (Image Editor)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**: 提供涂鸦与马赛克两种基础图片编辑能力，支持撤销与保存到相册

**主要维护者**: [RD] 全栈工程师

**阅读对象**: RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[LOCAL] 纯本地处理**: 所有编辑操作必须在设备本地完成，严禁上传云端
- **[PERF] 实时绘制响应**: 手指滑动绘制延迟 < 50ms，保持跟手流畅
- **[I18N] 多语言文案**: 保存成功/失败提示必须提取到 strings.xml
- **[FEEDBACK] 操作反馈**: 撤销、保存等关键操作需提供明确的视觉或触感反馈
- **[MEMORY] 内存优化**: 大图加载必须使用 inMutable 配置，避免重复分配 Bitmap

## 2. 技术实现规范 (Technical Implementation)

### 2.1 涂鸦功能 (Doodle Mode)

**技术规范**:
- **画笔颜色**: 默认红色 (`android.graphics.Color.RED`)，可通过调色板切换
- **画笔粗细**: 固定 60f，可根据屏幕密度动态调整
- **路径记录**: 使用 `Path` 对象记录每次绘制轨迹，支持撤销回退
- **绘制线程**: 必须在 UI 线程通过 `ComposeCanvas` 渲染，避免并发冲突

**代码示例**:
```kotlin
// DrawAction 数据结构
data class DrawAction(
    val path: Path,
    val mode: EditMode,
    val color: Int = android.graphics.Color.RED,
    val strokeWidth: Float = 60f
)

// Canvas 绘制逻辑
ComposeCanvas(modifier = Modifier.fillMaxSize()) {
    originalBitmap?.let { bitmap ->
        drawIntoCanvas { canvas ->
            // 绘制原图
            canvas.drawImage(bitmap.asImageBitmap(), srcSize, dstSize)
            
            // 叠加绘制路径
            actions.forEach { action ->
                val paint = Paint().apply {
                    if (action.mode == EditMode.MOSAIC && mosaicShader != null) {
                        shader = mosaicShader
                    } else {
                        color = Color(action.color)
                    }
                    style = PaintingStyle.Stroke
                    strokeWidth = action.strokeWidth
                    strokeCap = StrokeCap.Round
                }
                canvas.nativeCanvas.drawPath(action.path, paint.asFrameworkPaint())
            }
        }
    }
}
```

### 2.2 马赛克功能 (Mosaic Mode)

**技术规范**:
- **马赛克粒度**: 块大小 = 图片宽度 / 40，最小 10px
- **着色器生成**: 使用 `BitmapShader` + `Matrix` 缩放实现像素化效果
- **TileMode**: 使用 `CLAMP` 模式避免边缘拉伸异常
- **性能优化**: Shader 在图片加载时预计算一次，绘制时直接复用

**代码示例**:
```kotlin
// 马赛克 Shader 初始化
val blockSize = (bitmap.width / 40f).coerceAtLeast(10f)
val smallW = (bitmap.width / blockSize).toInt().coerceAtLeast(1)
val smallH = (bitmap.height / blockSize).toInt().coerceAtLeast(1)
val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, false)
val shader = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
val matrix = Matrix()
matrix.postScale(bitmap.width.toFloat() / smallW, bitmap.height.toFloat() / smallH)
shader.setLocalMatrix(matrix)
mosaicShader = shader
```

### 2.3 撤销机制 (Undo)

**技术规范**:
- **数据结构**: 使用 `mutableStateListOf<DrawAction>` 存储绘制历史
- **撤销操作**: 移除列表最后一项并触发重绘 (`drawIteration++`)
- **状态管理**: 撤销按钮在无历史记录时应禁用 (`enabled = actions.isNotEmpty()`)
- **内存控制**: 不限制撤销步数，但需监控大图的内存占用

### 2.4 图片保存 (Save to Gallery)

**技术规范**:
- **保存线程**: 必须在 `Dispatchers.IO` 后台线程执行，避免阻塞 UI
- **MediaStore API**: 使用 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 插入相册
- **文件格式**: 固定使用 PNG 格式保留透明度（如需压缩可改用 JPEG）
- **元数据写入**: 记录拍摄时间、修改时间等 EXIF 信息
- **异常处理**: 保存失败必须 Toast 提示用户，并记录日志

**代码示例**:
```kotlin
private suspend fun saveEditedImage(
    context: Context,
    bitmap: Bitmap,
    viewModel: MediaViewModel,
    successMsg: String,
    failedMsg: String
) {
    withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "EDITED_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }
            
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                
                // 通知 ViewModel 刷新相册
                viewModel.refreshMedia()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "$failedMsg: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
```

## 3. Agent 执行规约 (Execution Rules)

- **图片加载**: 必须在 `Dispatchers.IO` 线程加载大图，严禁在 UI 线程解码
- **Bitmap 回收**: 编辑完成后必须调用 `bitmap.recycle()` 释放 native 内存
- **Shader 复用**: 马赛克 Shader 应在 `LaunchedEffect` 中初始化一次，避免重复创建
- **路径优化**: 频繁绘制时可考虑使用 `PathMeasure` 简化路径点，减少渲染压力
- **I18N**: 所有用户可见文案（保存成功、失败、加载失败）必须提取到 strings.xml
- **权限检查**: 保存前无需显式检查存储权限（Android 10+ Scoped Storage）
- **日志规范**: 关键操作（加载、保存、撤销）需记录 `PicMe:Editor` 日志

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 是否在 UI 线程中加载了大图？(必须使用 Dispatchers.IO)
- [ ] Bitmap 使用后是否调用了 recycle()？(避免 OOM)
- [ ] 马赛克 Shader 是否只初始化了一次？(避免重复计算)
- [ ] 撤销操作后是否触发了重绘？(drawIteration++ 或 state 更新)
- [ ] 保存操作是否在后台线程执行？(避免 ANR)
- [ ] 保存失败是否有明确的错误提示？(Toast + 日志)
- [ ] 绘制路径是否使用了抗锯齿？(StrokeCap.Round)
- [ ] 编辑后的图片是否正确通知了相册刷新？(viewModel.refreshMedia)
- [ ] 是否处理了图片加载失败的异常？(try-catch + Toast)
- [ ] 当前绘制路径 (currentPath) 是否在抬起手指后加入 actions 列表？

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ 纯本地编辑 → 无网络请求，所有处理在设备端完成
- ✅ 实时绘制响应 → ComposeCanvas + State 驱动，延迟 < 50ms
- ✅ 撤销功能 → mutableStateListOf 记录历史，支持无限步回退
- ✅ 保存到相册 → MediaStore API 插入，自动刷新 Gallery

**技术决策记录**:
- 选择 ComposeCanvas 而非自定义 View：与 Jetpack Compose 生态无缝集成，状态管理更简洁
- 使用 BitmapShader 实现马赛克：GPU 加速，性能优于逐像素 CPU 处理
- 不限制撤销步数：简化实现，依赖系统内存管理；若出现 OOM 再引入 LRU 策略
- 保存为 PNG 格式：保留透明度信息，适合涂鸦场景；后续可扩展为用户可选格式
