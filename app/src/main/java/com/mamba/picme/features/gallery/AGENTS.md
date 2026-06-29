# 相册模块技术实现规范 (Gallery)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**: 应用默认首页，提供智能聚类相册浏览、媒体查看器、批量操作功能；右下角 plus 菜单聚合 Chat / Camera / Settings / Model Center 四个二级页入口，语音 Agent 面板提供自然语言交互入口。重复照片管理入口已迁移至设置页「相册功能」卡片

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
- **Scoped Storage 权限处理**:
  - **Android 6~9 (API 23-28)**: 运行时申请 `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE`，通过 `ContentResolver.delete()` 直接删除
  - **Android 10 (API 29)**: 捕获 `RecoverableSecurityException`，保存 `userAction.actionIntent.intentSender`，通过 `StartIntentSenderForResult` 请求单条授权，授权后重试删除
  - **Android 11+ (API 30+)**: 收集失败的 URI 列表，使用 `MediaStore.createDeleteRequest()` 发起批量系统授权对话框，用户允许后系统自动完成物理删除
- **数据一致性**: 必须遵循"先物理文件、后数据库记录"的顺序。若删除需要用户授权，必须推迟 Room 数据库清理，直到授权成功后再执行，避免用户拒绝后出现"文件还在、记录已消失"的不一致状态

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
- **触发时机**: 用户在设置页「相册功能」卡片点击「管理重复照片」后进入独立 `DuplicateManagerRoute` 时触发扫描
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

### 2.5 首页导航与底部悬浮 Tab（2026-06 新增）

**首页定位**:
- `GalleryScreen` 是 `MainActivity` NavHost 的 `startDestination`
- 系统返回键在相册无内部状态（无选择/Pager）时退出应用

**底部悬浮 Tab**:
- 使用共享组件 `FloatingBottomTab`（`features/common/components/FloatingBottomTab.kt`）
- 位置：底部居中，底部 padding 16.dp，悬浮于媒体网格之上
- 入口项（从左到右）：Camera、Chat、Model Center
- 每项仅显示图标，无文字标签
- 点击直接导航到对应二级页

**设置入口**:
- 统一放在 `GalleryTopBar` 动作区最右侧
- 点击跳转 `SettingsScreen`

**模型中心入口**:
- 底部 Tab 第三个图标（SmartToy）可直接进入
- 同时从 Gallery 顶部栏进入 Settings 后，在 AI 助手卡片第一项「Model Center」也可进入

**语音 Agent 面板**:
- 位置：右下角，底部 padding 84.dp，位于底部 Tab 上方
- 使用 `GalleryAgentPanel` + `AgentChatPanel` 公共组件
- 负责自然语言相册浏览/编辑/管理指令

### 2.6 静态图美颜编辑（2026-05 新增）

**技术规范**:
- **入口**: 
  - MediaPager 顶部工具栏 ✨ 编辑按钮（`AutoFixHigh` icon），仅 `MediaType.PHOTO` 显示
  - **长按图片区域**：直接进入编辑模式（带 `HapticFeedbackType.LongPress` 触感反馈）
- **状态管理**: 使用 `MutableStateFlow<PhotoEditState>` 密封类管理编辑状态
  - `Idle`: 未进入编辑模式
  - `Analyzing`: 正在执行人脸检测
  - `Ready(bitmap, faceData)`: 准备就绪，可显示 BeautySelector 面板
  - `Processing`: GPU 离屏渲染处理中
  - `Error(message)`: 处理失败
- **人脸检测缓存**: 
  - `preparePhotoEdit(bitmap, lensFacing=1)` 执行一次检测，缓存 `FaceData` 到 `cachedEditFaceData`
  - `processPhoto(bitmap, settings)` 复用缓存，跳过重复检测
  - `clearPhotoEditState()` 同步清除缓存
- **实时预览触发**: Compose 层使用 `snapshotFlow { editSettings }.drop(1).debounce(200).filter { enabled && hasAnyEffect() }` 自动触发处理
- **GPU 处理**: 调用 `PhotoProcessor.process(bitmap, params, faceData)`，在 `Dispatchers.Default` 执行
- **保存策略**: `saveProcessedPhoto(context, bitmap)` 在 `Dispatchers.IO` 写入 MediaStore，文件名 `EDITED_${timestamp}.jpg`
- **坐标系注意**: 相册编辑路径传入 `lensFacing=1`（后置），避免 `MediaPipe468Adapter` 对 X 坐标做镜像；`PhotoProcessorImpl` 中照片路径**不做 Y 翻转**（`GLUtils.texImage2D` 纹理坐标与图像坐标天然对齐）

**代码示例**:
```kotlin
// ViewModel 层
fun preparePhotoEdit(bitmap: Bitmap, lensFacing: Int = 1) {
    viewModelScope.launch(Dispatchers.Default) {
        _photoEditState.value = PhotoEditState.Analyzing
        val detectionResult = faceDetector.detectPhoto(bitmap, lensFacing)
        val faceData = detectionResult?.landmarks106?.toFaceData(bitmap.width, bitmap.height)
        cachedEditFaceData = faceData
        _photoEditState.value = PhotoEditState.Ready(bitmap, faceData)
    }
}

fun processPhoto(bitmap: Bitmap, settings: BeautySettings, lensFacing: Int = 1) {
    viewModelScope.launch(Dispatchers.Default) {
        _photoEditState.value = PhotoEditState.Processing
        val faceData = cachedEditFaceData ?: run {
            faceDetector.detectPhoto(bitmap, lensFacing)
                ?.landmarks106?.toFaceData(bitmap.width, bitmap.height)
        }
        val params = settings.toBeautyParams()
        val processed = photoProcessor.process(bitmap, params, faceData)
        _photoEditState.value = PhotoEditState.Ready(processed, faceData)
    }
}
```

### 2.6 OCR 文字识别集成

**技术规范**:
- **触发入口**: 
  - 工具栏"提取文字"按钮
  - ~~长按图片文字区域（已改为进入图片编辑）~~
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

### 2.7 TAG 生成精细控制（2026-06 新增）

**入口**: `TagGenerationControlScreen`（设置 → AI Agent → TAG 生成控制）

**技术规范**:
- **3-Pass 混合管道**: Pass 1 人脸检测/Embedding → Pass 2 DBSCAN 聚类 → Pass 3 Qwen 多模态标签
- **队列编排**: `TagScanOrchestrator` 持久化任务队列，支持暂停/恢复/取消/失败重试
- **增量去重**: 默认跳过近期已覆盖所有请求 Pass 的媒体，按 `oldest-first` 排序避免老照片饿死
- **精细控制**: 支持按 `TagCategory`（人脸/场景/活动/物体/标签/摘要）和时间范围（全部/7天/30天/90天）重新生成
- **OpenCL 守护**: `OpenClGuardian` 在 Pass 3 前 warmup，超时后自动降级 CPU 并记录设备黑名单
- **模型加载**: `TagGenerationScheduler.ensureModelLoaded()` 优先 OpenCL（用户开启且未降级），失败/warmup 超时后降级 CPU
- **状态观察**: 通过 `TagGenerationService.sessionProgress` StateFlow 显示进度、预计剩余时间、暂停/恢复按钮

**代码示例**:
```kotlin
// 启动按类别重新生成
context.startForegroundService(
    TagGenerationService.intentRegenerateCategories(
        context = context,
        categories = listOf(TagCategory.SCENE.name, TagCategory.TAGS.name),
        startTimeMs = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L,
        fullMode = false
    )
)
```

### 2.8 缩略图缓存策略 (LruCache)

**技术规范**:
- **内存缓存**: 使用 Coil 的 `MemoryCache`，占可用内存 25%
- **磁盘缓存**: 占磁盘空间 2%，目录为 `cache/image_cache`
- **加载优先级**: 当前可见项优先加载，预加载相邻项
- **OOM 保护**: 系统内存紧张时自动降低非可见图片质量
- **位置记录**: 使用 `mutableStateMapOf<Long, Rect>` 记录缩略图位置，支持展开动画

## 3. adb 自动化测试命令 (Gallery Test Commands)

MediaPager 集成与 Camera 同源的广播命令体系（`com.mamba.picme.TEST_COMMAND`），支持通过 adb 精确控制相册操作，无需图像识别。

**命令列表**:

| 命令 | 参数 | 说明 |
|------|------|------|
| `enter_gallery` | — | 从相机页进入相册（CameraScreen 处理） |
| `open_photo` | `index` (int) | 跳转到指定索引的图片 |
| `long_press_photo` | — | 长按照片，触发进入编辑模式 |
| `start_edit` | — | 进入图片编辑模式（等效长按/✨按钮） |
| `save_edit` | — | 保存当前编辑结果 |
| `cancel_edit` | — | 取消编辑并退出编辑模式 |
| `set_smooth` | `value` (0-100) | 设置磨皮强度 |
| `set_whiten` | `value` (0-100) | 设置美白强度 |
| `set_edit_filter` | `filter` (string) | 设置色调滤镜 |
| `start_ocr` | — | 触发 OCR 文字识别 |
| `dismiss_ocr` | — | 关闭 OCR 结果浮层 |
| `toggle_landmark` | — | 切换人脸关键点覆盖层 |
| `toggle_info` | — | 切换信息浮层显示 |
| `delete_photo` | — | 删除当前照片 |
| `share_photo` | — | 分享当前照片 |
| `start_tag_scan_all` | — | 启动 TAG 全量扫描 |
| `start_tag_scan_incremental` | — | 启动 TAG 增量扫描 |
| `pause_tag_scan` | — | 暂停当前 TAG 扫描 |
| `resume_tag_scan` | — | 恢复当前 TAG 扫描 |
| `cancel_tag_scan` | — | 取消当前 TAG 扫描 |

**adb 示例**:
```bash
# 进入相册
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action enter_gallery

# 打开第 3 张图片（索引从 0 开始）
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action open_photo --ei index 2

# 进入编辑模式
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action start_edit

# 设置磨皮 50、美白 30
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action set_smooth --ei value 50
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action set_whiten --ei value 30

# 保存编辑
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action save_edit

# 启动 TAG 全量扫描
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action start_tag_scan_all

# 暂停 / 恢复 / 取消 TAG 扫描
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action pause_tag_scan
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action resume_tag_scan
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action cancel_tag_scan
```

**实现细节**:
- 命令定义: `CameraTestCommand` sealed class（位于 `features/camera/test/`）
- 分发器: `CameraTestCommandDispatcher.commandFlow`（SharedFlow，Camera 与 Gallery 共用）
- 收集器: `MediaPager` 内通过 `LaunchedEffect(Unit)` 订阅，仅响应 Gallery 相关命令
- 编辑参数命令（`set_smooth` 等）仅在 `isEditing=true` 时生效，否则返回 Error
- TAG 扫描命令通过 `TagGenerationService` 对应 Intent 触发，无需 UI 处于前台

## 4. Agent 执行规约 (Execution Rules)

- **图片加载**: 必须使用 Coil 框架，配置内存与磁盘缓存策略
- **线程管理**: 分组计算、OCR 识别必须在后台线程执行
- **手势冲突**: 放大态必须禁用 HorizontalPager 翻页 (`userScrollEnabled = false`)
- **资源释放**: OCR Detector、Bitmap 使用后必须调用 `close()` / `recycle()`
- **I18N**: 所有分组标题、操作按钮文案必须提取到 strings.xml
- **日志规范**: 关键操作（分组切换、删除、OCR）需记录 `PicMe:Gallery` 日志
- **权限处理**: 读取相册需申请 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` 权限
- **状态持久化**: 分组模式切换后无需持久化，应用重启恢复默认 `NONE`

## 5. 常见陷阱检查清单 (Checklist)

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
- [ ] TAG 扫描任务是否正确持久化到 `tag_scan_tasks` 表？(异常恢复)
- [ ] Pass 3 Qwen 推理是否经过 `OpenClGuardian` 超时保护？(防止 OpenCL 挂起)
- [ ] 按类别重新生成时是否正确映射到 Pass 阶段？(人脸→Pass 1+2，其他→Pass 3)
- [ ] 沉浸式模式是否在 DisposableEffect 中正确清理？(onDispose 恢复系统栏)
- [ ] 缩略图位置记录是否在重组时丢失？(使用 remember)

## 6. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ 高刷流畅滚动 → LazyVerticalGrid + Coil 缓存 + beyondBoundsPageCount
- ✅ 智能聚类 → GetGroupedMediaUseCase 支持 6 种分组模式
- ✅ 流体动效 → HorizontalPager + ZoomableImage 手势联动
- ✅ 批量操作 → mutableStateListOf 支持连续批选与全选
- ✅ OCR 本地识别 → ML Kit 离线引擎，ViewModel 生命周期管理
- ✅ TAG 生成控制 → 3-Pass 队列 + 类别/时间范围精细控制 + OpenCL 超时降级

**技术决策记录**:
- 选择 HorizontalPager 而非 ViewPager2：与 Compose 生态无缝集成，手势控制更灵活
- 使用 StateFlow 而非 LiveData：支持冷流、操作符丰富，与 Coroutines 深度整合
- LruCache 上限设为内存 25%：平衡清晰度与内存占用，OOM 风险可控
- 分组计算放在 UseCase 层：遵循 Clean Architecture，便于单元测试与复用
- OCR 资源在 onCleared 释放：避免内存泄漏，符合 ViewModel 生命周期规范
- TAG 扫描使用持久化队列：支持暂停/恢复/取消，避免后台被系统回收后丢失进度
- OpenCL 推理由 `OpenClGuardian` 守护：warmup + 连续失败降级 CPU，降低设备兼容性风险
