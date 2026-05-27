# PicMe Agent 架构 V2 设计方案

> 目标：打造全功能 Agent 控制系统，Agent 成为应用的核心交互入口。

---

## 功能覆盖矩阵

### 当前已接入功能（✅）

| 功能域 | 具体功能 | 命令类型 | Capability | 状态 |
|--------|----------|----------|------------|------|
| **相机控制** | 拍照 | `CapturePhoto` | CameraCapability | ✅ |
| | 开始/停止录像 | `ToggleRecording` | CameraCapability | ✅ |
| | 翻转摄像头 | `FlipCamera` | CameraCapability | ✅ |
| | 变焦调节 | `AdjustZoom` | CameraCapability | ✅ |
| | 曝光调节 | `AdjustExposure` | CameraCapability | ✅ |
| | 切换拍摄模式 | `SwitchMode` | CameraCapability | ✅ |
| **美颜** | 磨皮/美白调节 | `AdjustBeauty` | CameraCapability | ✅ |
| | 瘦脸/大眼调节 | `AdjustBeauty` | CameraCapability | ✅ |
| | 唇色/腮红调节 | `AdjustBeauty` | CameraCapability | ✅ |
| **滤镜/风格** | 切换滤镜 | `SwitchFilter` | CameraCapability | ✅ |
| | 切换风格特效 | `SwitchStyle` | CameraCapability | ✅ |
| | 切换场景模式 | `SwitchScene` | CameraCapability | ✅ |
| | 切换画幅比例 | `SwitchRatio` | CameraCapability | ✅ |
| **对话** | 文本回复/聊天 | `TextReply` | CameraCapability | ✅ |

### V2 新增功能（🆕）

| 功能域 | 具体功能 | 命令类型 | Capability | 优先级 |
|--------|----------|----------|------------|--------|
| **Gallery** | 查看照片 | `ViewMedia` | GalleryCapability | P0 |
| | 删除照片 | `DeleteMedia` | GalleryCapability | P0 |
| | 分享照片 | `ShareMedia` | GalleryCapability | P1 |
| | 收藏照片 | `FavoriteMedia` | GalleryCapability | P2 |
| | 照片搜索 | `SearchMedia` | GalleryCapability | P2 |
| | 批量选择 | `SelectMedia` | GalleryCapability | P2 |
| | 切换视图模式 | `SwitchViewMode` | GalleryCapability | P2 |
| **设置** | 切换主题 | `ChangeTheme` | SettingsCapability | P1 |
| | 切换语言 | `ChangeLanguage` | SettingsCapability | P1 |
| | 下载模型 | `DownloadModel` | SettingsCapability | P1 |
| | 切换人脸引擎 | `SwitchFaceEngine` | SettingsCapability | P2 |
| | 开关调试模式 | `ToggleSetting` | SettingsCapability | P2 |
| **导航** | 切换页面 | `NavigateTo` | NavigationCapability | P0 |
| | 返回上一页 | `GoBack` | NavigationCapability | P0 |
| **编辑** | 进入编辑 | `ApplyEdit` | EditCapability | P1 |
| | 保存编辑 | `SaveEdit` | EditCapability | P1 |
| | 撤销/重做 | `UndoEdit`/`RedoEdit` | EditCapability | P2 |

---

## 架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              UI Layer                                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ CameraScreen │  │GalleryScreen │  │SettingsScreen│  │ImageEditScreen│    │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│         │                 │                 │                 │              │
│         └─────────────────┴────────┬────────┴─────────────────┘              │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     GlobalAgentPanel (全局Agent面板)                  │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │  ChatView   │  │VoiceButton  │  │ QuickActions│  │StatusIndicator│  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Agent Runtime Layer                                │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    AgentOrchestratorV2 (编排器)                       │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │SceneManager │  │PromptBuilder│  │MemoryManager│  │PrivacyGuard │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                       │                                     │
│                                       ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    CapabilityRegistry (能力注册表)                     │   │
│  │                                                                      │   │
│  │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │   │  CameraCapability   │  │ GalleryCapability  │  │SettingsCapability  │  │   │
│  │   │  • 拍照/录像        │  │ • 查看/删除        │  │ • 主题切换         │  │   │
│  │   │  • 美颜/滤镜        │  │ • 分享/搜索        │  │ • 语言切换         │  │   │
│  │   │  • 变焦/曝光        │  │ • 批量操作         │  │ • 模型管理         │  │   │
│  │   └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  │                                                                      │   │
│  │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │   │NavigationCapability │  │  EditCapability   │  │  (可扩展)        │  │   │
│  │   │ • 页面切换         │  │ • 编辑操作        │  │                  │  │   │
│  │   │ • 返回/退出        │  │ • 保存/撤销       │  │                  │  │   │
│  │   └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                       │                                     │
│                                       ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    LocalLlmEngine (本地推理引擎)                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Domain/Data Layer                                  │
│                                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │MediaRepository │ │SettingsRepository│ │ BeautyEngine   │ │  MNN-LLM    │    │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 核心组件设计

### 1. SceneManager 场景管理器

```kotlin
/**
 * 场景管理器
 * 
 * 负责：
 * 1. 跟踪当前活跃场景
 * 2. 根据场景获取对应的 Capability 集合
 * 3. 动态构建场景相关的 system prompt
 */
class SceneManager {
    
    enum class Scene {
        CAMERA,      // 相机页
        GALLERY,     // 相册页
        SETTINGS,    // 设置页
        EDITOR,      // 编辑页
        DEBUG        // 调试页
    }
    
    private val _currentScene = MutableStateFlow(Scene.CAMERA)
    val currentScene: StateFlow<Scene> = _currentScene.asStateFlow()
    
    fun transitionTo(scene: Scene) {
        _currentScene.value = scene
    }
    
    /**
     * 获取场景对应的 Capability 列表
     */
    fun getCapabilitiesForScene(scene: Scene): List<String> = when (scene) {
        Scene.CAMERA -> listOf("camera", "navigation")
        Scene.GALLERY -> listOf("gallery", "navigation")
        Scene.SETTINGS -> listOf("settings", "navigation")
        Scene.EDITOR -> listOf("edit", "navigation")
        Scene.DEBUG -> listOf("navigation")
    }
}
```

### 2. 分层 Prompt 构建器

```kotlin
/**
 * Prompt 构建器
 * 
 * 分层构建 system prompt：
 * - Base: 通用规则（JSON 格式、回复风格等）
 * - Scene: 场景特定能力
 * - Capability: 各 Capability 的自描述
 */
class PromptBuilder(private val sceneManager: SceneManager) {
    
    private val basePrompt = """
        你是 PicMe 的 AI 助手，帮助用户控制相机和照片管理。
        
        输出规则：
        1. 控制设备时只输出 JSON，不要任何解释
        2. 格式：{"action": "action_name", "param": "value"}
        3. 闲聊时用 text_reply action
        4. 不要输出 <think> 标签
    """.trimIndent()
    
    fun buildSystemPrompt(
        capabilities: List<Capability>,
        context: AgentContext
    ): String {
        val scene = sceneManager.currentScene.value
        
        return buildString {
            appendLine(basePrompt)
            appendLine()
            appendLine("当前页面: ${scene.name}")
            appendLine()
            appendLine("可用功能:")
            capabilities.forEach { cap ->
                appendLine("- ${cap.name}: ${cap.description}")
                cap.supportedCommands().forEach { cmd ->
                    appendLine("  • $cmd")
                }
            }
        }
    }
}
```

### 3. Capability 接口扩展

```kotlin
/**
 * Capability 接口 V2
 * 
 * 新增：
 * - 场景绑定：声明该 Capability 活跃的场景
 * - 上下文感知：接收页面特定的上下文数据
 */
interface Capability {
    val name: String
    val description: String
    
    /**
     * 该 Capability 在哪些场景下可用
     */
    fun activeScenes(): List<SceneManager.Scene>
    
    fun supportedCommands(): List<String>
    
    /**
     * 执行命令
     * @param command 解析后的命令
     * @param context 当前上下文
     * @param pageContext 页面特定上下文（如 Gallery 当前选中的照片）
     */
    suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext? = null
    ): Result<AgentAction>
}

/**
 * 页面上下文（页面特定状态）
 */
sealed class PageContext {
    data class GalleryContext(
        val currentMedia: MediaAsset?,
        val selectedItems: List<MediaAsset>,
        val isSelectionMode: Boolean
    ) : PageContext()
    
    data class SettingsContext(
        val currentCategory: String?
    ) : PageContext()
    
    data class EditorContext(
        val editingMedia: MediaAsset,
        val hasUnsavedChanges: Boolean
    ) : PageContext()
    
    object None : PageContext()
}
```

### 4. GalleryCapability

```kotlin
/**
 * 相册控制 Capability
 */
class GalleryCapability(
    private val onViewMedia: ((MediaAsset) -> Unit)? = null,
    private val onDeleteMedia: ((List<MediaAsset>) -> Unit)? = null,
    private val onShareMedia: ((List<MediaAsset>) -> Unit)? = null,
    private val onSelectMedia: ((MediaAsset, Boolean) -> Unit)? = null,
    private val onSearch: ((String) -> Unit)? = null,
    private val onSwitchViewMode: ((ViewMode) -> Unit)? = null
) : Capability {
    
    override val name = "gallery"
    override val description = "查看、删除、分享、搜索照片和视频"
    
    override fun activeScenes() = listOf(SceneManager.Scene.GALLERY)
    
    override fun supportedCommands() = listOf(
        "view_media",
        "delete_media",
        "share_media",
        "select_media",
        "search_media",
        "switch_view",
        "text_reply"
    )
    
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val galleryContext = pageContext as? PageContext.GalleryContext
        
        return when (command) {
            is AgentCommand.ViewMedia -> {
                val media = command.mediaId?.let { findMediaById(it) }
                    ?: galleryContext?.currentMedia
                media?.let { onViewMedia?.invoke(it) }
                Result.success(AgentAction.Success(command))
            }
            
            is AgentCommand.DeleteMedia -> {
                val items = command.mediaIds.mapNotNull { findMediaById(it) }
                    .ifEmpty { galleryContext?.selectedItems ?: emptyList() }
                onDeleteMedia?.invoke(items)
                Result.success(AgentAction.Success(command))
            }
            
            // ... 其他命令处理
            
            else -> Result.success(AgentAction.Error("不支持的命令"))
        }
    }
}
```

### 5. NavigationCapability

```kotlin
/**
 * 导航 Capability
 * 
 * 在所有场景都可用，负责页面切换
 */
class NavigationCapability(
    private val onNavigate: (Screen) -> Unit,
    private val onBack: () -> Unit
) : Capability {
    
    override val name = "navigation"
    override val description = "页面导航：切换页面、返回上一页"
    
    override fun activeScenes() = SceneManager.Scene.entries.toList()
    
    override fun supportedCommands() = listOf(
        "navigate_to",
        "go_back",
        "text_reply"
    )
    
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.NavigateTo -> {
                val screen = when (command.destination.lowercase()) {
                    "camera", "相机" -> Screen.Camera
                    "gallery", "相册" -> Screen.Gallery
                    "settings", "设置" -> Screen.Settings
                    "editor", "编辑" -> Screen.Editor
                    else -> return Result.success(AgentAction.Error("未知页面: ${command.destination}"))
                }
                onNavigate(screen)
                Result.success(AgentAction.Success(command))
            }
            
            is AgentCommand.GoBack -> {
                onBack()
                Result.success(AgentAction.Success(command))
            }
            
            else -> Result.success(AgentAction.Error("不支持的导航命令"))
        }
    }
}
```

---

## 命令扩展

```kotlin
/**
 * Agent 命令 V2
 * 
 * 新增 Gallery、Settings、Navigation、Edit 相关命令
 */
sealed class AgentCommand {
    // ===== 相机命令（已有）=====
    data class AdjustBeauty(val settings: BeautySettings) : AgentCommand()
    data class SwitchFilter(val filterType: FilterType) : AgentCommand()
    // ... 其他相机命令
    
    // ===== Gallery 命令（新增）=====
    data class ViewMedia(val mediaId: String? = null) : AgentCommand()
    data class DeleteMedia(val mediaIds: List<String> = emptyList()) : AgentCommand()
    data class ShareMedia(val mediaIds: List<String> = emptyList()) : AgentCommand()
    data class SelectMedia(val mediaId: String, val selected: Boolean) : AgentCommand()
    data class SearchMedia(val query: String) : AgentCommand()
    data class SwitchViewMode(val mode: ViewMode) : AgentCommand()
    
    // ===== 设置命令（新增）=====
    data class ChangeTheme(val theme: ThemeMode) : AgentCommand()
    data class ChangeLanguage(val language: AppLanguage) : AgentCommand()
    data class DownloadModel(val modelId: String) : AgentCommand()
    data class SwitchFaceEngine(val engine: FaceDetectionEngineMode) : AgentCommand()
    data class ToggleSetting(val settingKey: String, val enabled: Boolean) : AgentCommand()
    
    // ===== 导航命令（新增）=====
    data class NavigateTo(val destination: String) : AgentCommand()
    object GoBack : AgentCommand()
    
    // ===== 编辑命令（新增）=====
    data class ApplyEdit(val editType: String, val params: Map<String, Any>) : AgentCommand()
    object SaveEdit : AgentCommand()
    object UndoEdit : AgentCommand()
    
    // ===== 通用命令 =====
    data class TextReply(val message: String) : AgentCommand()
    data class Unknown(val raw: String) : AgentCommand()
    data class Error(val reason: String) : AgentCommand()
}
```

---

## 架构问题诊断与改进方向

### 当前架构问题

1. **单一场景限制**
   - AgentContext 仅支持 CAMERA/GALLERY/PHOTO_EDIT 三个场景
   - 没有 SETTINGS/EDITOR 等场景支持
   - 场景切换需要手动设置，Agent 无法自动感知

2. **Capability 单一**
   - 目前只有 CameraCapability
   - Gallery、Settings、Navigation 等功能域无对应 Capability
   - 所有回调都注册在 CameraCapability，耦合严重

3. **System Prompt 硬编码**
   - AgentOrchestrator 中硬编码 system prompt
   - 不同场景需要不同的 prompt，目前无法动态切换
   - 新增命令需要修改核心类

4. **命令解析局限**
   - 仅支持相机相关命令解析
   - Gallery/Settings 等域的命令无解析逻辑

5. **UI 与 Agent 绑定**
   - AiAgentPanel 仅在 CameraScreen 中
   - Gallery/Settings 等页面无 Agent 入口

### V2 改进方向

1. **多 Capability 架构**
   - 新增 GalleryCapability、SettingsCapability、NavigationCapability
   - 每个 Capability 自包含命令处理和执行逻辑

2. **动态场景感知**
   - Agent 自动感知当前页面（通过 Navigation 监听）
   - 根据场景动态加载对应的 system prompt

3. **全局 Agent 入口**
   - Agent Panel 作为全局组件，可在任意页面唤起
   - 支持悬浮球或手势触发

4. **分层 System Prompt**
   - 基础 prompt（通用能力）
   - 场景 prompt（特定页面能力）
   - 动态组合生成完整 prompt

---

## UI 改造

### 全局 Agent 面板

```kotlin
/**
 * 全局 Agent Panel
 * 
 * 以悬浮窗形式存在，可在任意页面唤起
 */
@Composable
fun GlobalAgentPanel(
    orchestrator: AgentOrchestratorV2,
    pageContextProvider: () -> PageContext,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    
    // 浮动按钮（收缩状态）
    if (!isExpanded) {
        FloatingActionButton(
            onClick = { isExpanded = true },
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(Icons.Default.Assistant, "AI Agent")
        }
    }
    
    // 展开面板
    AnimatedVisibility(visible = isExpanded) {
        AgentChatPanel(
            messages = messages,
            onSendMessage = { text ->
                coroutineScope.launch {
                    val context = buildAgentContext()
                    val pageContext = pageContextProvider()
                    val result = orchestrator.processUserInput(
                        input = text,
                        agentContext = context,
                        pageContext = pageContext
                    )
                    handleResult(result)
                }
            },
            onDismiss = { isExpanded = false }
        )
    }
}
```

---

## 实施计划

### Phase 1: 基础设施（本周）
1. 创建 `SceneManager` 场景管理器
2. 重构 `PromptBuilder` 支持分层 prompt
3. 扩展 `Capability` 接口支持场景绑定和页面上下文
4. 创建 `NavigationCapability`

### Phase 2: Gallery Agent（下周）
1. 创建 `GalleryCapability`
2. 扩展命令解析器支持 Gallery 命令
3. 在 GalleryScreen 集成 Agent Panel
4. 添加 Gallery 相关 system prompt

### Phase 3: Settings Agent（下周）
1. 创建 `SettingsCapability`
2. 支持设置相关命令
3. 在 SettingsScreen 集成 Agent Panel

### Phase 4: 全局 Agent（下周）
1. 实现全局 Agent Panel（悬浮窗形式）
2. 场景自动感知
3. 页面上下文自动收集

### Phase 5: 完善（后续）
1. EditCapability
2. 快捷指令面板
3. 语音命令增强
