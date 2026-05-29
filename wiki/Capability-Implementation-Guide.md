# PicMe Agent Capability 实现指南

> **边界声明（Boundary Statement）**
> - 本文档指导如何新增或扩展 Agent Capability。
> - 架构设计以 [`../02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](./02-ARCHITECTURE/AGENT_ARCHITECTURE.md) 为准。
> - 命令语法以 [`COMMAND_REFERENCE.md`](./COMMAND_REFERENCE.md) 为准。

**模块定位**: Capability 实现步骤与最佳实践  
**主要维护者**: [RD] 全栈工程师  
**阅读对象**: RD、AI Agent  
**版本**: 1.0  
**最后更新**: 2026-05-29  

---

## 📋 目录

1. [新增 Capability 流程](#1-新增-capability-流程)
2. [Capability 接口详解](#2-capability-接口详解)
3. [命令解析器扩展](#3-命令解析器扩展)
4. [页面上下文集成](#4-页面上下文集成)
5. [测试与验证](#5-测试与验证)
6. [常见陷阱](#6-常见陷阱)

---

## 1. 新增 Capability 流程

### 步骤 1: 定义 Capability 接口实现

```kotlin
class YourNewCapability : Capability {
    
    // 1. 定义能力标识
    override val name = "your_feature"
    override val description = "功能的简短描述，用于 System Prompt"
    
    // 2. 声明活跃场景
    override fun activeScenes() = listOf(
        SceneManager.Scene.YOUR_SCENE
    )
    
    // 3. 列出支持的命令
    override fun supportedCommands() = listOf(
        "command_1",
        "command_2",
        "text_reply"
    )
    
    // 4. 实现命令执行逻辑
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.YourCommand -> {
                // 执行具体逻辑
                performYourAction(command)
                Result.success(AgentAction.Success(command))
            }
            
            is AgentCommand.TextReply -> {
                Result.success(AgentAction.Text(command.message))
            }
            
            else -> {
                Result.success(AgentAction.Error("不支持的命令：${command::class.simpleName}"))
            }
        }
    }
    
    private fun performYourAction(cmd: AgentCommand.YourCommand): AgentAction {
        // TODO: 实现你的业务逻辑
        // 注意：不要直接依赖 UI 层，使用回调注入
    }
}
```

### 步骤 2: 注册到 CapabilityRegistry

```kotlin
// 在 Application 或 ViewModel 初始化时
val capabilityRegistry = CapabilityRegistry().apply {
    register(YourNewCapability())
    // 确保 NavigationCapability 始终注册
    register(NavigationCapability(onNavigate = {...}, onBack = {...}))
}
```

### 步骤 3: 扩展 AgentCommand

```kotlin
sealed class AgentCommand {
    // ... 现有命令
    
    // 新增命令
    data class YourCommand(val param1: String, val param2: Int) : AgentCommand()
}
```

### 步骤 4: 更新 PromptBuilder

```kotlin
class PromptBuilder(private val sceneManager: SceneManager) {
    
    fun buildSystemPrompt(
        capabilities: List<Capability>,
        context: AgentContext
    ): String {
        return buildString {
            appendLine(basePrompt)
            appendLine()
            appendLine("当前页面：${sceneManager.currentScene.value.name}")
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

### 步骤 5: 添加自然语言映射（可选）

```kotlin
object NaturalLanguageMapper {
    fun parseToCommand(input: String): AgentCommand? {
        return when {
            input.contains("你的功能") -> AgentCommand.YourCommand("default", 0)
            input.contains("参数 1") -> AgentCommand.YourCommand("value1", 42)
            else -> null
        }
    }
}
```

---

## 2. Capability 接口详解

### 2.1 核心方法

#### `name: String`

**用途**: 能力的唯一标识符  
**要求**: 小写字母 + 下划线，无空格  
**示例**: `"camera"`, `"gallery"`, `"your_feature"`

#### `description: String`

**用途**: 用于 System Prompt 的自描述  
**要求**: 简洁明了，不超过 50 字  
**示例**: `"相机控制：拍照、录像、美颜、滤镜"`

#### `activeScenes(): List<SceneManager.Scene>`

**用途**: 声明该能力在哪些场景可用  
**要求**: 必须返回非空列表  
**示例**:
```kotlin
override fun activeScenes() = listOf(
    SceneManager.Scene.CAMERA,
    SceneManager.Scene.DEBUG
)
```

#### `supportedCommands(): List<String>`

**用途**: 列出所有支持的命令名称  
**要求**: 必须包含 `"text_reply"`  
**示例**:
```kotlin
override fun supportedCommands() = listOf(
    "perform_action",
    "cancel_action",
    "text_reply"
)
```

#### `execute(...): Result<AgentAction>`

**用途**: 执行解析后的命令  
**参数**:
- `command`: 解析后的结构化命令
- `context`: 全局上下文（对话历史、用户信息等）
- `pageContext`: 页面特定上下文（如当前选中的照片）

**返回值**:
- `Result.success(AgentAction.Success)` - 执行成功
- `Result.success(AgentAction.Error(reason))` - 执行失败
- `Result.success(AgentAction.Text(message))` - 文本回复

---

## 3. 命令解析器扩展

### 3.1 扩展 Sealed Class

```kotlin
sealed class AgentCommand {
    data class YourCommand(
        val param1: String,
        val param2: Int,
        val optionalParam: String? = null
    ) : AgentCommand()
}
```

### 3.2 JSON 解析规则

**必须使用 `kotlinx.serialization`，禁止正则**:

```kotlin
import kotlinx.serialization.json.*

fun parseJsonResponse(jsonString: String): List<AgentCommand> {
    return Json.decodeFromString<List<YourCommand>>(jsonString)
        .map { AgentCommand.YourCommand(it.param1, it.param2) }
}
```

### 3.3 错误处理

```kotlin
override suspend fun execute(
    command: AgentCommand,
    context: AgentContext,
    pageContext: PageContext?
): Result<AgentAction> {
    return try {
        when (command) {
            is AgentCommand.YourCommand -> {
                // 参数验证
                if (command.param2 < 0 || command.param2 > 100) {
                    return Result.success(AgentAction.Error("param2 必须在 0-100 范围"))
                }
                
                // 执行逻辑
                performAction(command)
                Result.success(AgentAction.Success(command))
            }
            
            else -> Result.success(AgentAction.Error("不支持的命令"))
        }
    } catch (e: Exception) {
        Log.e("YourCapability", "执行失败", e)
        Result.success(AgentAction.Error("执行异常：${e.message}"))
    }
}
```

---

## 4. 页面上下文集成

### 4.1 定义 PageContext

```kotlin
sealed class PageContext {
    data class YourContext(
        val currentData: YourData?,
        val selectedItems: List<YourItem>,
        val extraInfo: Map<String, Any>?
    ) : PageContext()
    
    object None : PageContext()
}
```

### 4.2 获取 PageContext

```kotlin
override suspend fun execute(
    command: AgentCommand,
    context: AgentContext,
    pageContext: PageContext?
): Result<AgentAction> {
    val yourContext = pageContext as? PageContext.YourContext
    
    return when (command) {
        is AgentCommand.YourCommand -> {
            val data = command.param1?.let { findDataById(it) }
                ?: yourContext?.currentData
            
            data?.let { performAction(it, command) }
            Result.success(AgentAction.Success(command))
        }
        
        else -> Result.success(AgentAction.Error("不支持的命令"))
    }
}
```

### 4.3 UI 层提供 Context

```kotlin
@Composable
fun YourScreen(
    viewModel: YourViewModel
) {
    val pageContext by viewModel.pageContext.collectAsState()
    
    GlobalAgentPanel(
        orchestrator = agentOrchestrator,
        pageContextProvider = { pageContext }
    )
}
```

---

## 5. 测试与验证

### 5.1 单元测试

```kotlin
class YourCapabilityTest {
    
    private lateinit var capability: YourCapability
    
    @Before
    fun setup() {
        capability = YourCapability(
            onPerformAction = { param1, param2 -> 
                // Mock 逻辑
            }
        )
    }
    
    @Test
    fun `test valid command executes successfully`() {
        val command = AgentCommand.YourCommand("valid", 50)
        val context = AgentContext()
        val pageContext = PageContext.None
        
        val result = capability.execute(command, context, pageContext)
        
        assert(result.getOrNull() is AgentAction.Success)
    }
    
    @Test
    fun `test invalid parameter returns error`() {
        val command = AgentCommand.YourCommand("valid", 150) // Out of range
        val context = AgentContext()
        val pageContext = PageContext.None
        
        val result = capability.execute(command, context, pageContext)
        
        assert(result.getOrNull() is AgentAction.Error)
        assert(result.getOrNull()?.message?.contains("范围") == true)
    }
    
    @Test
    fun `test text_reply returns message`() {
        val command = AgentCommand.TextReply("Hello")
        val context = AgentContext()
        val pageContext = PageContext.None
        
        val result = capability.execute(command, context, pageContext)
        
        assert(result.getOrNull() is AgentAction.Text)
        assert((result.getOrNull() as AgentAction.Text).message == "Hello")
    }
}
```

### 5.2 集成测试

```kotlin
class YourCapabilityIntegrationTest {
    
    @Test
    fun `test end-to-end command flow`() = runTest {
        // 1. 模拟用户输入
        val userInput = "执行你的功能，参数 1 为 test，参数 2 为 75"
        
        // 2. 构建 Orchestrator
        val orchestrator = AgentOrchestrator(
            llmEngine = mockLlmEngine,
            capabilityRegistry = registry
        )
        
        // 3. 执行解析与执行
        val result = orchestrator.processUserInput(userInput, createContext())
        
        // 4. 验证结果
        assertTrue(result.isSuccess)
        verify(mockService).performAction("test", 75)
    }
}
```

### 5.3 QA 验收清单

- [ ] 命令能被 LLM 正确解析
- [ ] 参数验证生效
- [ ] 错误信息友好
- [ ] 文本回复符合预期
- [ ] 页面上下文正确传递
- [ ] 多场景切换不崩溃
- [ ] 性能达标（执行耗时 < 100ms）

---

## 6. 常见陷阱

### ❌ 陷阱 1: 硬编码 System Prompt

**错误**:
```kotlin
class YourCapability : Capability {
    private val systemPrompt = """
        你是 PicMe 助手...
        可用功能：your_feature
        • command_1
    """.trimIndent()
}
```

**正确**:
```kotlin
class PromptBuilder(private val sceneManager: SceneManager) {
    fun buildSystemPrompt(capabilities: List<Capability>): String {
        // 动态构建，支持插件化
    }
}
```

### ❌ 陷阱 2: 直接依赖 UI 层

**错误**:
```kotlin
class YourCapability : Capability {
    override suspend fun execute(..., pageContext: PageContext?) {
        // ❌ 直接调用 UI 方法
        uiController.updateView(data)
    }
}
```

**正确**:
```kotlin
class YourCapability(
    private val onUpdateView: ((Data) -> Unit)? = null
) : Capability {
    override suspend fun execute(...) {
        // ✅ 通过回调注入
        onUpdateView?.invoke(data)
    }
}
```

### ❌ 陷阱 3: 使用正则解析 JSON

**错误**:
```kotlin
fun parseJson(json: String): YourCommand {
    val param1 = json Regex "\"param1\": \"([^\"]+)\"" groupValues[1]
    // ❌ 无法处理嵌套/转义
}
```

**正确**:
```kotlin
fun parseJson(json: String): YourCommand {
    return Json.decodeFromString(json)
    // ✅ 类型安全，支持复杂结构
}
```

### ❌ 陷阱 4: 忘记注册 Command 映射

**错误**:
```kotlin
// 新增 YourCommand 后未更新 CapabilityRegistry
class CapabilityRegistry {
    fun mapCommand(name: String): AgentCommand? {
        return when (name) {
            "command_1" -> ExistingCommand()
            // ❌ 遗漏 YourCommand
        }
    }
}
```

**正确**:
```kotlin
class CapabilityRegistry {
    fun mapCommand(name: String): AgentCommand? {
        return when (name) {
            "command_1" -> ExistingCommand()
            "your_command" -> YourCommand() // ✅ 同步更新
        }
    }
}
```

### ❌ 陷阱 5: 忽略线程安全

**错误**:
```kotlin
class YourCapability : Capability {
    private var counter = 0 // ❌ 非线程安全
    
    override suspend fun execute(...) {
        counter++ // 并发修改
    }
}
```

**正确**:
```kotlin
class YourCapability : Capability {
    private val counter = AtomicInteger(0) // ✅ 原子操作
    
    override suspend fun execute(...) {
        counter.incrementAndGet()
    }
}
```

---

## 附录：Checklist

### 代码审查清单

- [ ] Capability 接口实现完整
- [ ] `name` / `description` 清晰准确
- [ ] `activeScenes()` 正确声明
- [ ] `supportedCommands()` 包含所有命令
- [ ] `execute()` 处理所有命令分支
- [ ] 参数验证与错误处理完善
- [ ] 不使用正则解析 JSON
- [ ] 不直接依赖 UI 层
- [ ] 已注册到 CapabilityRegistry
- [ ] 已更新 PromptBuilder
- [ ] 单元测试覆盖核心路径
- [ ] 日志规范（`PicMe:YourCapability`）

### 文档同步清单

- [ ] 更新 `CAPABILITY_REGISTRY.md` 添加新能力
- [ ] 更新 `COMMAND_REFERENCE.md` 添加命令示例
- [ ] 更新 `FEATURES.md`（如有交互变更）
- [ ] 添加反向链接注释（`// Spec: ...`）

---

> **参考文档**:
> - [AGENT_ARCHITECTURE.md](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) — Agent 架构设计
> - [CAPABILITY_REGISTRY.md](./CAPABILITY_REGISTRY.md) — 能力注册表
> - [COMMAND_REFERENCE.md](./COMMAND_REFERENCE.md) — 命令参考手册
