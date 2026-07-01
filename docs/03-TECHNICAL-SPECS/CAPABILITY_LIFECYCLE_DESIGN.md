# Capability 生命周期设计规范

> **状态**: 草案  
> **创建**: 2026-06-06  
> **更新**: 2026-06-06  
> **作者**: [RD] 全栈工程师  
> **评审**: [CR] 规范守护者

---

## 1. 设计目标

| 目标 | 优先级 | 说明 |
|------|--------|------|
| **零内存泄漏** | P0 | Capability 不得持有 Activity/Fragment/Screen 的强引用 |
| **生命周期对齐** | P0 | Capability 的生命周期必须与页面生命周期严格对齐 |
| **组合优于单例** | P0 | 优先使用依赖注入和组合，避免全局单例 |
| **跨页面命令** | P1 | 支持从任意页面发送命令到目标页面 |
| **低功耗** | P1 | 避免后台轮询和无效状态检查 |

---

## 2. 当前架构问题

### 2.1 问题清单

```
┌──────────────────────────────────────────────────────────────┐
│  问题 1: 单例持有页面引用（内存泄漏风险）                       │
├──────────────────────────────────────────────────────────────┤
│  CameraCapability.getInstance() ──► WeakReference<Delegate>  │
│  ▲ 问题: WeakReference 只能缓解，不能根治                      │
│  ▲ 问题: 匿名 Delegate 实现隐式持有 CameraScreen 的闭包变量     │
│  ▲ 问题: 单例生命周期 > Activity 生命周期                      │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  问题 2: DisposableEffect 时序竞争（delegate 绑定后立即解绑）   │
├──────────────────────────────────────────────────────────────┤
│  CameraScreen 重组 ──► DisposableEffect.onDispose()          │
│  ▲ 问题: Compose 重组频繁，onDispose 被过早调用                │
│  ▲ 问题: 导航动画期间，旧页面 DisposableEffect 先 dispose      │
│  ▲ 问题: 新页面 DisposableEffect 后 enter，存在时间窗口        │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  问题 3: Application 级注册僵化（无法动态扩展）                 │
├──────────────────────────────────────────────────────────────┤
│  Application.onCreate() ──► registry.register(capability)    │
│  ▲ 问题: 注册后无法注销，无法热插拔 Capability                 │
│  ▲ 问题: 所有 Capability 常驻内存，增加基础内存占用              │
│  ▲ 问题: 单元测试需要清理全局状态，增加测试复杂度                │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  问题 4: SceneManager 与 Compose 生命周期脱节                   │
├──────────────────────────────────────────────────────────────┤
│  MainActivity 设置 scene ──► SceneManager.transitionTo()     │
│  CameraScreen DisposableEffect ──► bindDelegate()            │
│  ▲ 问题: 两个系统独立运行，存在状态不一致窗口                    │
│  ▲ 问题: SceneManager 引用计数复杂，容易出错                   │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 内存泄漏路径分析

```kotlin
// 当前代码（泄漏路径）
class CameraCapability : BaseCapability() {
    companion object {
        private var instance: CameraCapability? = null  // 静态引用，永不释放
        fun getInstance() = instance!!
    }
    
    private var delegateRef: WeakReference<Delegate>? = null
}

// CameraScreen.kt
DisposableEffect(Unit) {
    val cameraCapability = CameraCapability.getInstance()  // 获取单例
    cameraCapability.bindDelegate(object : CameraCapability.Delegate {
        override fun onSwitchRatio(ratio: String) {
            aspectRatio = ratio  // 匿名类隐式持有 CameraScreen 的 aspectRatio
        }
        // ... 其他方法同样持有 CameraScreen 的状态引用
    })
    // 即使 WeakReference 被清理，单例仍然存活，且匿名类的类加载器引用链复杂
}
```

---

## 3. 新架构设计

### 3.1 核心原则

#### 原则 1: 页面级 Capability（Page-Scoped Capability）

```kotlin
// ✅ 新设计: Capability 随页面创建和销毁
@Composable
fun CameraScreen(
    viewModel: MediaViewModel,
    // Capability 通过参数注入，而非全局单例
    cameraCapability: CameraCapability = remember { CameraCapability() }
) {
    // CameraCapability 直接持有状态，无需 delegate 模式
    DisposableEffect(Unit) {
        // 注册到当前页面的 Capability 集合
        LocalCapabilityHost.current.register(cameraCapability)
        onDispose {
            LocalCapabilityHost.current.unregister(cameraCapability)
        }
    }
}
```

#### 原则 2: 组合优于单例（Composition over Singleton）

```kotlin
// ❌ 旧设计: 单例访问
val registry = CapabilityRegistry.getInstance()
registry.register(CameraCapability.getInstance())

// ✅ 新设计: 依赖注入
class MainActivity : ComponentActivity() {
    private val navigationCapability = NavigationCapability()  // Activity 级
    
    override fun onCreate(savedInstanceState: Bundle?) {
        setContent {
            val capabilityHost = rememberCapabilityHost(navigationCapability)
            CompositionLocalProvider(LocalCapabilityHost provides capabilityHost) {
                NavHost(...) { ... }
            }
        }
    }
}
```

#### 原则 3: 状态内聚（State Cohesion）

```kotlin
// ❌ 旧设计: 状态分散在 Screen 和 Capability 之间
class CameraScreen {
    var aspectRatio by remember { mutableIntStateOf(AspectRatio.RATIO_FULL) }
    // Capability 通过 delegate 回调修改 Screen 状态
}

// ✅ 新设计: Capability 持有自己的状态
class CameraCapability {
    var aspectRatio by mutableIntStateOf(AspectRatio.RATIO_FULL)
        private set
    
    fun switchRatio(ratio: String) {
        aspectRatio = parseRatio(ratio)
    }
}
```

### 3.2 架构分层

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1: Application（全局配置，无状态）                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  - LogModuleConfig 默认值                                │   │
│  │  - BeautyEngine 全局初始化（仅一次）                      │   │
│  │  - 不持有任何 Capability 实例                             │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: Activity（导航级 Capability）                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  NavigationCapability ── 绑定 NavController              │   │
│  │  - 生命周期: Activity.onCreate() ~ Activity.onDestroy()  │   │
│  │  - 作用域: 所有页面共享同一个 NavigationCapability        │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: Screen（页面级 Capability）                             │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  CameraCapability ── 绑定 Camera 状态                    │   │
│  │  - 生命周期: Screen Enter ~ Screen Exit                  │   │
│  │  - 作用域: 仅当前 CameraScreen                           │   │
│  │  - 状态: aspectRatio, lensFacing, beautySettings...      │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 4: ViewModel（业务逻辑，跨配置变更存活）                     │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  MediaViewModel ── 媒体数据管理                           │   │
│  │  - 生命周期: Activity 配置变更存活                         │   │
│  │  - 不持有 Capability 引用（通过回调通信）                   │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 3.3 Capability 生命周期分类

| 类型 | 生命周期 | 典型示例 | 创建位置 | 销毁位置 |
|------|----------|----------|----------|----------|
| **应用级** | Application | `BeautyEngine` | `Application.onCreate()` | 永不销毁 |
| **活动级** | Activity | `NavigationCapability` | `Activity.onCreate()` | `Activity.onDestroy()` |
| **页面级** | Screen | `CameraCapability` | `Screen 首次重组` | `Screen 从组合树移除` |
| **用例级** | UseCase | `AiAgentUseCase` | `需要时创建` | `不再使用时释放` |

---

## 4. 详细设计

### 4.1 CapabilityHost（Capability 容器）

```kotlin
/**
 * Capability 宿主
 *
 * 管理当前作用域内所有 Capability 的注册和查询。
 * 支持层级查找：如果当前宿主找不到，会委托给父宿主。
 */
class CapabilityHost(
    private val parent: CapabilityHost? = null
) {
    private val capabilities = mutableMapOf<String, Capability>()
    
    fun register(capability: Capability) {
        capabilities[capability.name] = capability
    }
    
    fun unregister(capability: Capability) {
        capabilities.remove(capability.name)
    }
    
    fun find(name: String): Capability? {
        return capabilities[name] ?: parent?.find(name)
    }
    
    fun findForScene(scene: SceneManager.Scene): List<Capability> {
        return capabilities.values.filter { 
            it.activeScenes().contains(scene) || it.activeScenes().isEmpty()
        }
    }
}

// Compose 集成
val LocalCapabilityHost = compositionLocalOf<CapabilityHost> { 
    error("CapabilityHost not provided") 
}

@Composable
fun rememberCapabilityHost(vararg capabilities: Capability): CapabilityHost {
    val parent = LocalCapabilityHost.current
    return remember(capabilities) {
        CapabilityHost(parent).apply {
            capabilities.forEach { register(it) }
        }
    }
}
```

### 4.2 页面级 CameraCapability

```kotlin
/**
 * 相机控制 Capability（页面级）
 *
 * 由 CameraScreen 创建和持有，Screen 销毁时自动释放。
 * 不再使用 delegate 模式，状态直接内聚在 Capability 中。
 */
class CameraCapability : BaseCapability() {
    override val name: String = "camera"
    override val description: String = "控制相机拍摄、美颜参数、滤镜..."
    
    // 状态直接内聚在 Capability 中
    var aspectRatio by mutableIntStateOf(AspectRatio.RATIO_FULL)
        private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
        private set
    var beautySettings by mutableStateOf(BeautySettings(enabled = false))
        private set
    
    // 命令执行直接修改内部状态
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.SwitchRatio -> {
                aspectRatio = parseRatio(command.ratio)
                Result.success(AgentAction.Success(...))
            }
            // ... 其他命令
        }
    }
    
    override fun isAvailable(): Boolean = true  // 页面级 Capability 只要存在就可用
}
```

### 4.3 Screen 与 Capability 的绑定

```kotlin
@Composable
fun CameraScreen(
    viewModel: MediaViewModel,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    // 创建页面级 Capability
    val cameraCapability = remember { CameraCapability() }
    
    // 注册到当前 CapabilityHost
    val host = LocalCapabilityHost.current
    DisposableEffect(cameraCapability) {
        host.register(cameraCapability)
        onDispose { host.unregister(cameraCapability) }
    }
    
    // 将 Capability 的状态绑定到 UI
    val aspectRatio = cameraCapability.aspectRatio
    val lensFacing = cameraCapability.lensFacing
    
    // UI 使用 Capability 状态
    CameraPreviewContent(
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        // ...
    )
}
```

### 4.4 跨页面命令处理

```kotlin
/**
 * 跨页面命令由 NavigationCapability 统一处理
 *
 * 导航到目标页面后，目标页面的 Capability 自然可用。
 * 无需复杂的排队和轮询机制。
 */
class NavigationCapability(
    private val navController: NavController
) : BaseCapability() {
    override val name: String = "navigation"
    
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.NavigateTo -> {
                // 导航到目标页面
                navController.navigate(command.destination)
                // 导航完成后，目标页面的 Capability 会自动接管后续命令
                Result.success(AgentAction.Success(...))
            }
            // ...
        }
    }
}
```

---

## 5. 迁移路径

### 5.1 阶段 1: 引入 CapabilityHost（向后兼容）

```kotlin
// 1. 添加 CapabilityHost 和 CompositionLocal
// 2. 修改 CapabilityRegistry 支持从 CapabilityHost 查询
// 3. CameraScreen 同时注册到单例和 CapabilityHost
class CapabilityRegistry {
    fun dispatch(command: AgentCommand, context: AgentContext): Result<AgentAction> {
        // 优先从 CapabilityHost 查找
        val host = LocalCapabilityHost.currentOrNull
        val capability = host?.findForCommand(command) 
            ?: findCapabilityForCommand(command)
        // ...
    }
}
```

### 5.2 阶段 2: 移除单例（破坏性变更）

```kotlin
// 1. 移除 CameraCapability.getInstance()
// 2. 移除 Application 中的 initializeCapabilities()
// 3. MainActivity 创建 NavigationCapability 并注入
// 4. 各 Screen 创建自己的 Capability
```

### 5.3 阶段 3: 清理废弃代码

```kotlin
// 1. 移除 SceneManager 的引用计数机制
// 2. 移除 CapabilityRegistry 的命令队列
// 3. 移除所有 WeakReference delegate 模式
```

---

## 6. 内存影响评估

| 指标 | 旧架构 | 新架构 | 变化 |
|------|--------|--------|------|
| 常驻 Capability 数 | 4（永不释放） | 1（Navigation） | -75% |
| CameraCapability 内存占用 | 常驻 | 仅在相机页 | 按需分配 |
| 匿名 Delegate 实例 | 1/页面（泄漏风险） | 0 | 完全消除 |
| 命令队列轮询 | 500ms 间隔 | 无 | 节省 CPU |
| SceneManager 引用计数 | 复杂 | 简单 | 降低复杂度 |

---

## 7. 红线合规检查

| 红线 | 合规状态 | 说明 |
|------|----------|------|
| [PRIVACY] | ✅ | 无变更 |
| [PERF] | ✅ | 减少常驻内存和后台轮询 |
| [I18N] | ✅ | 无变更 |
| [DOC-SYNC] | ✅ | 本文档同步架构变更 |
| [AGENT-FIRST] | ✅ | 显式生命周期、枚举状态、自描述类型 |

---

## 8. 参考

- [AGENTS.md](../../AGENTS.md) - Agent First 架构原则
- [CAPABILITY_REGISTRY.md](../04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md) - Capability Registry 文档
- [Compose 生命周期](https://developer.android.com/jetpack/compose/lifecycle) - 官方文档
