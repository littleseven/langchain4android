# 相册模块技术实现规范 (Gallery)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**: 提供智能聚类相册浏览、媒体查看器、批量操作与重复照片管理功能

**主要维护者**: [RD] 全栈工程师

**阅读对象**: RD、QA、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[PERF] 高刷流畅滚动**: 1000+ 张照片滑动保持 60fps，缩略图加载跟手无白屏
- **[LOCAL] 纯本地聚类**: 人脸分组、重复检测全部在设备端完成，严禁上传云端
- **[I18N] 多语言支持**: 分组标题、操作按钮必须提取到 strings.xml
- **[FEEDBACK] 流体动效**: 图片展开/收起跟随手指轨迹，缩放平移过渡自然
- **[MEMORY] 内存优化**: LruCache 限制上限，OOM 时自动降级清晰度

## 2. 技术实现规范 (Technical Implementation)

### 2.1 智能聚类引擎 (Grouping Engine)

**技术规范**:
- **分组模式**: 
  - `NONE`: 按时间倒序平铺
  - `DATE`: 按日期分组（今天、昨天、本周、本月）
  - `PERSON`: 按人物聚类（基于 ML Kit 人脸检测）
  - `LANDSCAPE`: 风景照片单独分组
  - `SWIMWEAR` / `SEXY`: 特殊场景分类
- **UseCase 层**: 通过 `GetGroupedMediaUseCase` 封装分组逻辑，ViewModel 仅负责状态管理
- **Flow 组合**: 使用 `combine(repository.allMedia, _groupingMode)` 响应式更新分组结果
- **性能优化**: 分组计算在 `Dispatchers.Default` 线程执行，避免阻塞 UI

**代码示例**:
```kotlin
val groupedMedia: StateFlow<List<GroupedMedia>> = combine(
    repository.allMedia,
    _groupingMode
) { allMedia, mode ->
    getGroupedMediaUseCase(allMedia, mode)
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = emptyList()
)
```

### 2.2 媒体查看器 (MediaPager)

**技术规范**:
- **HorizontalPager**: 使用 Compose Foundation 的 `HorizontalPager` 实现左右翻页
- **缩放控制**: 
  - 缩放范围: 1.0x ~ 4.0x
  - 放大态禁用翻页 (`userScrollEnabled = !currentPageZoomed`)
  - 单指平移查看细节，双指捏合缩放
- **手势冲突处理**: 
  - 放大时优先图片平移，禁止触发翻页
  - 回到 1x 后恢复翻页功能
- **预加载策略**: `beyondBoundsPageCount = 3` 预加载前后 3 页
- **沉浸式模式**: 隐藏状态栏与导航栏，滑动边缘时 transient 显示

**代码示例**:
```kotlin
@OptIn(ExperimentalFoundationApi::class)
HorizontalPager(
    state = pagerState,
    beyondBoundsPageCount = 3,
    userScrollEnabled = !currentPageZoomed,
    pageSpacing = 16.dp,
    contentPadding = PaddingValues(horizontal = 16.dp)
) { page ->
    ZoomableImage(
        imageModel = mediaAssets[page].uri,
        onZoomChanged = { scale -> currentPageZoomed = scale > 1f }
    )
}
```

### 2.3 批量选择与操作 (Batch Selection)

**技术规范**:
- **进入方式**: 长按任意缩略图进入选择模式
- **连续批选**: 支持拖拽滑动批量选择/取消，减少逐张点击成本
- **数据结构**: 使用 `mutableStateListOf<Long>` 存储选中 ID
- **全选功能**: 提供"全选"按钮快速选中当前视图所有媒体
- **批量分享**: 
  - 单张: `Intent.ACTION_SEND` + `EXTRA_STREAM`
  - 多张: `Intent.ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` ArrayList
  - MIME 类型: 图片用 `image/*`，视频用 `video/*`，混合用 `*/*`
- **批量删除**: 调用 `viewModel.deleteMediaByIds(ids)` 异步删除

**代码示例**:
```kotlin
private fun shareMediaAssets(context: Context, assets: List<MediaAsset>) {
    if (assets.isEmpty()) return
    
    val uris = assets.map { it.uri.toUri() }
    val shareIntent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.first())
            type = if (assets.first().type == MediaType.VIDEO) "video/*" else "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    
    context.startActivity(Intent.createChooser(shareIntent, null))
}
```

### 2.4 重复照片管理 (Duplicate Manager)

**技术规范**:
- **触发时机**: 用户手动开启"重复管理器"时触发扫描
- **扫描逻辑**: 调用 `FindDuplicateMediaUseCase` 在后台线程执行
- **判定规则**:
  - 精确重复: 文件哈希值相同
  - 相似照片: 视觉内容高度接近（需图像指纹算法）
- **UI 展示**: 每组重复照片展示缩略图网格，用户可选择保留哪一张
- **删除策略**: 
  - 默认保留第一张，删除其余
  - 支持用户自定义选择保留项
  - 批量删除所有重复组中非首项

**代码示例**:
```kotlin
fun deleteDuplicateGroup(group: DuplicateGroup, keepIndex: Int = 0) {
    viewModelScope.launch {
        val urisToDelete = if (keepIndex == 0) {
            group.getDeleteUris()
        } else {
            group.fileUris.filterIndexed { index, _ -> index != keepIndex }
        }
        
        val idsToDelete = allMedia.value
            .filter { asset -> asset.uri in urisToDelete }
            .map { asset -> asset.id }
        
        if (idsToDelete.isNotEmpty()) {
            deleteMediaByIds(idsToDelete)
            _duplicateGroups.value = _duplicateGroups.value.filter { it.id != group.id }
        }
    }
}
```

### 2.5 OCR 文字识别集成

**技术规范**:
- **触发入口**: 
  - 工具栏"提取文字"按钮
  - 长按图片文字区域（快捷操作）
- **状态管理**: 使用 `MutableStateFlow<OcrResult?>` 管理识别状态（Loading/Success/Error）
- **资源释放**: ViewModel `onCleared()` 时调用 `ocrUseCase.close()` 释放 ML Kit 资源
- **结果展示**: 原位浮层卡片展示识别结果，支持复制与分享
- **异常处理**: 识别失败时显示友好错误提示，记录日志

**代码示例**:
```kotlin
fun recognizeTextFromCurrentImage(context: Context, uri: Uri) {
    viewModelScope.launch {
        _ocrState.value = OcrResult.Loading
        try {
            val result = ocrUseCase.recognizeFromUri(context, uri)
            _ocrState.value = if (result != null) {
                OcrResult.Success(result)
            } else {
                OcrResult.Error("未找到文字")
            }
        } catch (e: Exception) {
            _ocrState.value = OcrResult.Error("识别失败：${e.message}")
        }
    }
}

override fun onCleared() {
    super.onCleared()
    ocrUseCase.close() // 释放 ML Kit 资源
}
```

### 2.6 缩略图缓存策略 (LruCache)

**技术规范**:
- **内存缓存**: 使用 Coil 的 `MemoryCache`，占可用内存 25%
- **磁盘缓存**: 占磁盘空间 2%，目录为 `cache/image_cache`
- **加载优先级**: 当前可见项优先加载，预加载相邻项
- **OOM 保护**: 系统内存紧张时自动降低非可见图片质量
- **位置记录**: 使用 `mutableStateMapOf<Long, Rect>` 记录缩略图位置，支持展开动画

## 3. Agent 执行规约 (Execution Rules)

- **图片加载**: 必须使用 Coil 框架，配置内存与磁盘缓存策略
- **线程管理**: 分组计算、OCR 识别必须在后台线程执行
- **手势冲突**: 放大态必须禁用 HorizontalPager 翻页 (`userScrollEnabled = false`)
- **资源释放**: OCR Detector、Bitmap 使用后必须调用 `close()` / `recycle()`
- **I18N**: 所有分组标题、操作按钮文案必须提取到 strings.xml
- **日志规范**: 关键操作（分组切换、删除、OCR）需记录 `PicMe:Gallery` 日志
- **权限处理**: 读取相册需申请 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` 权限
- **状态持久化**: 分组模式切换后无需持久化，应用重启恢复默认 `NONE`

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 是否在 UI 线程中执行了分组计算？(必须使用 Dispatchers.Default)
- [ ] LruCache 是否设置了合理的上限？(避免 OOM)
- [ ] 放大态是否正确禁用了翻页？(userScrollEnabled = false)
- [ ] OCR 引擎是否在 ViewModel 销毁时释放？(onCleared 中 close)
- [ ] 批量分享是否正确授予了 URI 权限？(FLAG_GRANT_READ_URI_PERMISSION)
- [ ] 滚动时是否频繁创建对象？(应在构造函数中初始化)
- [ ] Flow 是否正确使用了 stateIn？(避免重复订阅)
- [ ] 图片加载是否处理了异常？(Coil 的 onError 回调)
- [ ] 长按触发批选时是否有触感反馈？(HapticFeedback)
- [ ] 重复扫描是否在后台线程执行？(避免阻塞 UI)
- [ ] 沉浸式模式是否在 DisposableEffect 中正确清理？(onDispose 恢复系统栏)
- [ ] 缩略图位置记录是否在重组时丢失？(使用 remember)

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ 高刷流畅滚动 → LazyVerticalGrid + Coil 缓存 + beyondBoundsPageCount
- ✅ 智能聚类 → GetGroupedMediaUseCase 支持 6 种分组模式
- ✅ 流体动效 → HorizontalPager + ZoomableImage 手势联动
- ✅ 批量操作 → mutableStateListOf 支持连续批选与全选
- ✅ OCR 本地识别 → ML Kit 离线引擎，ViewModel 生命周期管理

**技术决策记录**:
- 选择 HorizontalPager 而非 ViewPager2：与 Compose 生态无缝集成，手势控制更灵活
- 使用 StateFlow 而非 LiveData：支持冷流、操作符丰富，与 Coroutines 深度整合
- LruCache 上限设为内存 25%：平衡清晰度与内存占用，OOM 风险可控
- 分组计算放在 UseCase 层：遵循 Clean Architecture，便于单元测试与复用
- OCR 资源在 onCleared 释放：避免内存泄漏，符合 ViewModel 生命周期规范
