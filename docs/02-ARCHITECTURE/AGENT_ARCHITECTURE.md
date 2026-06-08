# PicMe Agent 架构设计 (Agent Architecture)

> **边界声明（Boundary Statement）**
> - 本文档定义 PicMe AI Agent 的运行时架构、Capability 模型与推理模式选型。
> - 产品目标与验收口径以 [`../01-PRODUCT/FEATURES.md`](./01-PRODUCT/FEATURES.md) 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 [`AGENTS.md`](../../AGENTS.md) 为准。
> - 模块级实现细节以 `agent-core/src/main/java/com/picme/agent/core/` 源码为准。注意：Agent Runtime 核心组件已从 `app/domain/agent/` 迁移至独立的 `:agent-core` 模块。

**模块定位**: PicMe 相机 AI 助手"小觅"的 Runtime 架构与推理模式选型  
**主要维护者**: [RD] 全栈工程师  
**阅读对象**: RD、AI Agent  
**版本**: 2.0 (合并 V2+MEMO)  
**最后更新**: 2026-05-29  

---

## 📋 目录

1. [核心产品逻辑](#1-核心产品逻辑-core-product-logic)
2. [架构图](#2-架构图)
3. [核心组件设计](#3-核心组件设计)
4. [推理模式选型](#4-推理模式选型)
5. [命令扩展](#5-命令扩展)
6. [执行规约](#6-执行规约)
7. [常见陷阱检查清单](#7-常见陷阱检查清单)
8. [架构演进路线图](#8-架构演进路线图)

---

## 1. 核心产品逻辑 (Core Product Logic)

### 1.1 红线约束

| 约束 | 定义 | 验证方式 |
|------|------|----------|
| **[PRIVACY]** | 敏感数据强制本地推理，人脸/对话数据严禁上云 | PrivacyGuard 拦截数据流 |
| **[PERF]** | 交互反馈 < 100ms，LLM 推理后台完成 | 端侧 Qwen3-1.7B 首 token 目标 < 600ms |
| **[I18N]** | System Prompt 及用户可见回复禁止硬编码中文 | 接入 string 资源 |
| **[OFFLINE]** | 本地模型未下载时提供明确引导 | 非静默失败 |
| **[TYPE_SAFE]** | AgentCommand / AgentAction 必须使用 Sealed Class | 禁止字符串魔法值 |

### 1.2 当前架构模式

**单轮 Function Calling（指令解析 - 执行模式）**，非 ReAct 模式。

```
用户输入 → 构建 System Prompt → LLM 一次性生成 → 解析为 AgentCommand → Capability 执行
```

**核心组件状态**:

| 组件 | 职责 | 状态 |
|------|------|------|
| `AgentOrchestrator` | Prompt 构建、LLM 推理、响应解析、命令路由 | 🔄 部分实现 |
| `LocalLlmEngine` | 封装 MNN-LLM 客户端，支持多模型切换 | ✅ 已落地 |
| `MemoryManager` | DataStore 持久化对话历史，按 session 隔离 | ✅ 已落地 |
| `CapabilityRegistry` | Capability 注册与命令分发 | ✅ 已落地 |
| `Capability` | 领域能力抽象（相机控制、相册管理等） | 🔄 部分实现 |
| `PrivacyGuard` | 输入内容隐私分级与本地优先约束 | ✅ 已落地 |

---

## 2. 架构图

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
│  │                     GlobalAgentPanel (全局 Agent 面板)                  │   │
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
│  │                    AgentOrchestrator (编排器)                         │   │
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
│  │   │NavigationCapability │  │  (预留扩展)        │  │                  │  │   │
│  │   │ • 页面切换         │  │                  │  │                  │  │   │
│  │   │ • 返回/退出        │  │                  │  │                  │  │   │
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

## 3. 核心组件设计

### 3.1 SceneManager 场景管理器

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

### 3.2 分层 Prompt 构建器

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
            appendLine("当前页面：${scene.name}")
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

### 3.3 Capability 接口扩展

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

### 3.4 GalleryCapability 示例

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
            
            else -> Result.success(AgentAction.Error("不支持的命令"))
        }
    }
}
```

### 3.5 NavigationCapability 示例

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
                    else -> return Result.success(AgentAction.Error("未知页面：${command.destination}"))
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

## 4. 推理模式选型

### 4.1 与 ReAct 模式的区别

| 维度 | 当前架构 | ReAct |
|------|---------|-------|
| **推理步骤** | 单轮：输入 → 输出 | 多轮 Thought-Action-Observation 循环 |
| **LLM 调用次数** | 1 次 | 多次（每步一次） |
| **中间观察** | 无，执行结果直接返回用户 | 工具结果反馈给 LLM 继续推理 |
| **决策深度** | 单步指令 | 多步骤规划、条件判断、错误恢复 |
| **延迟** | 低（单次推理） | 高（多轮 RTT） |

### 4.2 端侧推理模式选型（Qwen3-1.7B）

**不推荐完整 ReAct**，原因：
- 2B 模型 COT（链式思考）能力弱，Thought 质量不稳定
- 相机场景要求 < 100ms 反馈，多轮推理无法满足 `[PERF]` 红线
- 端侧电池/发热敏感

**推荐演进路径**:

```
Phase 1（当前）: 单轮 Function Calling
       ↓
Phase 2: 多指令批量执行（Batch Function Calling）
       ↓
Phase 3: Plan-and-Execute（预定义模板）
       ↓
Phase 4: 轻量 ReAct（限 2-3 步，仅复杂场景）
```

### 4.3 远端推理模式选型

远端模式下**减少 LLM 调用次数**比端侧更重要（RTT 成本主导）。

**推荐分层自适应模式**:

| 层级 | 模式 | 适用场景 | RTT 次数 |
|------|------|---------|---------|
| Layer 1 | 本地规则缓存 | "拍照"等高频指令 | 0 |
| Layer 2 | Batch Function Calling | 连续动作指令 | 1 |
| Layer 3 | Plan-and-Execute | 条件/多步任务 | 1（规划）+ 本地执行 |
| Layer 4 | ReAct（限步） | 极少数动态推理场景 | N（≤3，超时熔断） |

**远端优化策略**:
- 连接池 + Keep-Alive 复用 TCP
- 100ms 防抖窗口合并请求
- 2s 超时降级到本地规则或文本提示
- 常见意图响应缓存（LruCache）

### 4.4 Qwen3-1.7B Prompt 工程建议

- 使用模型原生 `tools` 参数定义相机控制函数，替代手写 JSON format prompt
- System Prompt 精简：只传关键状态，大模型理解力强无需冗长描述
- 历史裁剪更激进：`maxHistoryRounds = 5`（端侧 10）

---

## 5. 命令扩展

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

### 5.1 功能覆盖矩阵

#### 当前已接入功能（✅）

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

#### V2 新增功能（🆕）

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
| **编辑** | 进入编辑 | 预留 | 待后续独立 Capability 落地 | P2 |
| | 保存编辑 | 预留 | 待后续独立 Capability 落地 | P2 |
| | 撤销/重做 | 预留 | 待后续独立 Capability 落地 | P2 |

---

## 6. Agent 执行规约 (Execution Rules)

- **JSON 解析**: 必须使用 `kotlinx.serialization.json`，严禁正则提取字段
- **System Prompt**: 禁止硬编码在 `AgentOrchestrator` 内，需抽象为 `PromptBuilder` 策略接口
- **Capability 注册**: 新增 Capability 必须同步更新 `CapabilityRegistry` 的命令映射，禁止遗漏
- **Memory 持久化**: `appendConversation` 需引入内存缓存 + 批量刷盘，禁止每条消息 2 次 DataStore IO
- **ChatML 格式**: `LocalLlmEngine` 禁止硬编码 Qwen ChatML，需抽象 `ChatFormat` 接口按模型注册
- **线程安全**: `AgentOrchestrator` 的 `agentMode` / `currentModelId` 需同步控制，禁止并发修改
- **模型加载**: 快速连续调用需加并发锁，避免触发多次加载
- **隐私拦截**: `PrivacyGuard` 必须接入 LLM 输入输出流和 Capability 执行链路，禁止仅做断言
- **日志规范**: 统一使用 `PicMe:Agent` 前缀，禁止各组件标签不一致

---

## 7. 常见陷阱检查清单 (Checklist)

- [ ] JSON 解析是否使用了正则？（必须用 kotlinx.serialization，正则无法处理嵌套/转义）
- [ ] System Prompt 是否硬编码在类内？（需按场景插件化，违反 OCP）
- [ ] 新增 AgentCommand 子类后是否同步更新了 CapabilityRegistry 的映射？（易遗漏）
- [ ] MemoryManager 是否存在 IO 放大问题？（每条消息 2 次 DataStore 读写需优化）
- [ ] ChatML 格式是否与模型耦合？（换模型如 Llama/Gemma 需改代码）
- [ ] CameraCapability 回调是否为 null？（11 个可选回调需 Builder 模式或统一接口）
- [ ] 模型加载是否有并发控制？（快速连续调用可能触发多次加载）
- [ ] PrivacyGuard 是否实际拦截了数据流？（当前仅断言，未接入 LLM 和 Capability）
- [ ] 用户可见文案是否硬编码中文？（需接入 strings.xml 支持多语言）
- [ ] AgentAction.Success 是否携带了语义冗余？（应携带执行结果数据，而非原命令）

---

## 8. 架构演进路线图

```
┌─────────────────────────────────────────────────────────────────┐
│                        端侧推理演进路线                           │
├─────────────────────────────────────────────────────────────────┤
│  P0（立即）:                                                    │
│  1. JSON 解析改用 kotlinx.serialization                         │
│  2. System Prompt 提取为 PromptBuilder 接口                      │
│  3. MemoryManager 引入内存缓存 + 批量刷盘                         │
│                                                                 │
│  P1（近期）:                                                    │
│  4. 支持 Batch Function Calling（JSON 数组输出）                  │
│  5. ChatFormat 抽象，支持多模型切换                              │
│  6. Capability 命令映射改为注解驱动或属性声明                      │
│                                                                 │
│  P2（中期）:                                                    │
│  7. Plan-and-Execute 模板引擎                                   │
│  8. 本地意图缓存（高频指令 0ms 响应）                              │
│  9. Token 预算管理与上下文压缩                                    │
│                                                                 │
│  P3（远期）:                                                    │
│  10. 轻量 ReAct（限 2-3 步，超时熔断）                           │
│  11. 记忆摘要（长期对话不丢失上下文）                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 附录：参考文档

- [AGENTS.md](../../AGENTS.md) — 顶层治理规则
- [FEATURES.md](../01-PRODUCT/FEATURES.md) — 功能交互细节
- [COMMAND_REFERENCE.md](../04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md) — 命令参考手册
- `agent-core/src/main/java/com/picme/agent/core/` — 源码目录（Agent Runtime 核心）
- `app/src/main/java/com/picme/domain/usecase/AiAgentUseCase.kt` — Facade 桥接层
