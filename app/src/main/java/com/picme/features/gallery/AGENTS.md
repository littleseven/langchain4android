# Gallery 模块技术实现规范 (Gallery Technical Implementation)

**模块定位**：确保 PicMe 的相册模块在千张照片规模下仍能保持 120fps 流畅滚动和秒级响应。

**主要维护者**：[RD] 全栈工程师

**阅读对象**：RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[PERF] 120fps 流畅滚动**：使用 LruCache + 预加载 + beyondBoundsPageCount 优化
- **[ACCURACY] 人脸分组精度**：特征距离 < 0.4 判定为同一人
- **[DUPLICATE] 重复检测**：MD5（精确重复）+ pHash（相似照片，汉明距离 < 5）
- **[RESPONSE] OCR 秒级响应**：异步执行 + 状态流管理

## 2. 技术实现规范 (Technical Implementation)

### 2.1 数据加载策略
```kotlin
// Repository 层 - 仅加载元数据，严禁加载大图
@Query("SELECT * FROM media_assets ORDER BY date_taken DESC")
fun getAllMediaMetadata(): Flow<List<MediaAsset>>

// 延迟加载图片数据（仅在 UI 需要时）
suspend fun loadThumbnail(assetId: Long): Bitmap {
    return withContext(Dispatchers.IO) {
        // 使用 Coil 或 Glide 加载缩略图
        ImageLoader(context)
            .load(assetUri)
            .size(240) // 根据屏幕密度动态计算
            .decode()
    }
}
```

### 2.2 内存缓存管理
```kotlin
// LruCache 配置 - 占可用内存 1/8，上限 64MB
val thumbnailCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 8).toInt()
    .coerceAtMost(64 * 1024 * 1024)
) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int {
        return bitmap.byteCount / 1024 // 以 KB 为单位
    }
}

// 缓存查找与插入
fun getThumbnailFromCache(uri: String): Bitmap? {
    return thumbnailCache[uri]
}

fun addThumbnailToCache(uri: String, bitmap: Bitmap) {
    if (thumbnailCache[uri] == null) {
        thumbnailCache.put(uri, bitmap)
    }
}
```

### 2.3 智能聚类算法实现

### 2.1 人脸分组逻辑
```kotlin
// 使用 ML Kit Face Detection + 特征点比对
class FaceClusterAnalyzer {
    
    suspend fun clusterFaces(assets: List<MediaAsset>): Map<String, List<MediaAsset>> {
        val faceGroups = mutableMapOf<String, MutableList<MediaAsset>>()
        
        for (asset in assets) {
            val faces = detectFaces(asset.uri)
            
            for (face in faces) {
                val matchedGroupId = findMatchingGroup(face)
                
                if (matchedGroupId != null) {
                    faceGroups[matchedGroupId]?.add(asset)
                } else {
                    // 创建新分组
                    val newGroupId = generateGroupId(face)
                    faceGroups[newGroupId] = mutableListOf(asset)
                }
            }
        }
        
        return faceGroups
    }
    
    private fun findMatchingGroup(newFace: Face): String? {
        // 产品定义阈值：特征距离 < 0.4 判定为同一人
        for ((groupId, existingFace) in knownFaces) {
            val distance = calculateFeatureDistance(newFace, existingFace)
            if (distance < 0.4f) {
                return groupId
            }
        }
        return null
    }
}
```

### 2.2 重复照片检测
```kotlin
// 双重检测机制：MD5 + pHash
class DuplicateDetector {
    
    // 精确重复检测（MD5）
    suspend fun isExactDuplicate(uri: Uri): Boolean {
        val md5 = calculateMD5(uri)
        return database.queryExactMd5(md5) != null
    }
    
    // 相似照片检测（感知哈希）
    suspend fun isSimilarPhoto(uri: Uri): Boolean {
        val phash = calculatePerceptualHash(uri)
        
        // 产品定义阈值：汉明距离 < 5
        return database.querySimilarPHash(pHash, threshold = 5).isNotEmpty()
    }
    
    // 一键清理实现
    suspend fun cleanupDuplicates(): Int {
        val duplicates = database.findAllDuplicates()
        var deletedCount = 0
        
        for (group in duplicates) {
            // 保留每组第一张，删除其余
            group.drop(1).forEach { asset ->
                deleteAsset(asset)
                deletedCount++
            }
        }
        
        return deletedCount
    }
}
```

### 2.4 OCR 功能集成

### 3.1 MediaPager OCR 入口
```kotlin
// ViewModel 中的 OCR 状态管理
sealed class OcrResult {
    object Loading : OcrResult()
    data class Success(val text: String, val confidence: Float) : OcrResult()
    data class Error(val message: String) : OcrResult()
}

class MediaViewModel : ViewModel() {
    private val _ocrState = MutableStateFlow<OcrResult?>(null)
    val ocrState: StateFlow<OcrResult?> = _ocrState
    
    fun startOcr(uri: String) {
        viewModelScope.launch {
            _ocrState.value = OcrResult.Loading
            
            try {
                // 异步执行 OCR 识别
                val result = ocrUseCase.execute(uri)
                _ocrState.value = OcrResult.Success(
                    text = result.text,
                    confidence = result.confidence
                )
            } catch (e: Exception) {
                _ocrState.value = OcrResult.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    fun dismissOcr() {
        // 重置状态流
        _ocrState.value = null
    }
}
```

### 3.2 OCR 结果展示组件
```kotlin
@Composable
fun OcrResultOverlay(
    ocrState: StateFlow<OcrResult?>,
    onDismiss: () -> Unit
) {
    val result by ocrState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    
    AnimatedVisibility(
        visible = result != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(onClick = onDismiss) // 点击背景关闭
        ) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .heightIn(max = 500.dp)
                    .padding(horizontal = 24.dp)
            ) {
                when (val ocrResult = result) {
                    is OcrResult.Success -> {
                        OcrResultContent(
                            text = ocrResult.text,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(ocrResult.text))
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.ocr_copy_success),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onShare = { shareText(context, ocrResult.text) },
                            onClose = onDismiss
                        )
                    }
                    else -> { /* Loading/Error states */ }
                }
            }
        }
    }
}
```

### 2.5 性能优化关键点

### 2.6 预览缩放与分享实现规范（2026-04）

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZoomableImage(
    uri: String,
    onZoomStateChanged: (Float) -> Unit
) {
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .pointerInput(uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 4f)
                    scale = nextScale
                    offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                }
            }
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        )
    }

    SideEffect {
        onZoomStateChanged(scale)
    }
}

HorizontalPager(
    state = pagerState,
    userScrollEnabled = !currentPageZoomed
) { page ->
    // scale > 1f 时禁用翻页，避免手势冲突
}
```

**实现约束**：
- **缩放范围**：必须限制在 `1.0x~4.0x`，防止过度放大导致性能劣化。
- **手势冲突**：放大态优先平移图片，禁止触发 `HorizontalPager` 翻页。
- **单图分享**：预览页使用 `Intent.ACTION_SEND` + `EXTRA_STREAM`，并授予 `FLAG_GRANT_READ_URI_PERMISSION`。
- **批量分享**：相册选择模式下，单张使用 `ACTION_SEND`，多张使用 `ACTION_SEND_MULTIPLE`。
- **媒体类型**：图片用 `image/*`，视频用 `video/*`；多媒体混合批量可回退为 `*/*`。
- **日志规范**：分享触发、批选状态切换需记录 `PicMe:UX` 日志。

### 4.1 RecyclerView 优化
```kotlin
// Compose Pager 的性能配置
@OptIn(ExperimentalFoundationApi::class)
HorizontalPager(
    state = pagerState,
    beyondBoundsPageCount = 3, // 预加载前后 3 页
    pageSpacing = 16.dp,
    contentPadding = PaddingValues(horizontal = 16.dp)
) { page ->
    // 懒加载图片
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(assets[page].uri)
            .crossfade(true)
            .build(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize()
    )
}
```

### 4.2 后台线程管理
```kotlin
// 数据库操作必须在 IO 线程
viewModelScope.launch(Dispatchers.IO) {
    val allMedia = repository.getAllMedia()
    
    withContext(Dispatchers.Main) {
        // UI 更新在主线程
        updateUi(allMedia)
    }
}

// 使用 Flow 实现自动刷新
val uiState: StateFlow<UiState> = repository.allMedia
    .map { media -> UiState.Success(media) }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UiState.Loading
    )
```

## 3. Agent 执行规约 (Execution Rules)

- **图片加载**：必须在后台线程（Dispatchers.IO）加载大图，严禁在 UI 线程
- **LruCache 管理**：必须设置合理上限（占可用内存 1/8，上限 64MB），避免 OOM
- **Face Detection**：ML Kit 很耗时，必须在后台线程执行
- **OCR 引擎**：使用后必须释放，避免内存泄漏
- **对象创建**：滚动时避免频繁创建对象，应在构造函数中初始化
- **Flow 订阅**：正确使用 `stateIn` 操作符，避免重复订阅
- **异常处理**：图片加载必须处理异常情况，避免崩溃
- **防抖优化**：长按触发 OCR 时必须加防抖，避免重复触发

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 是否在 UI 线程中加载了大图？（必须使用 Dispatchers.IO）
- [ ] LruCache 是否设置了合理的上限？（避免 OOM）
- [ ] Face Detection 是否在后台线程执行？（ML Kit 很耗时）
- [ ] OCR 引擎是否在使用后释放？（避免内存泄漏）
- [ ] 滚动时是否频繁创建对象？（应在构造函数中初始化）
- [ ] Flow 是否正确使用了 `stateIn`？（避免重复订阅）
- [ ] 图片加载是否处理了异常？（避免崩溃）
- [ ] 长按触发 OCR 时是否有防抖？（避免重复触发）

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ 120fps 滚动 → LruCache + 预加载 + beyondBoundsPageCount
- ✅ 人脸分组特征距离 < 0.4 → ML Kit + 自定义比对算法
- ✅ 重复检测汉明距离 < 5 → pHash + 数据库查询优化
- ✅ OCR 秒级响应 → 异步执行 + 状态流管理

**技术决策记录**：
- 选择 Room 而非 Realm：更好的 Kotlin 协程支持，更小的包体积
- 使用 Coil 而非 Glide：原生支持 Compose，API 更简洁
- Flow 替代 LiveData：冷流特性更适合数据同步场景
