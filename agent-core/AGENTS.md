# Agent Core 模块

## 状态

**已激活** — 纯 Kotlin 模块，包含 Agent 基础设施核心接口。

## 模块定位

`:agent-core` 是 **Agent 基础设施层**，提供平台无关的泛型接口：

| 组件 | 职责 |
|------|------|
| `Capability<T,C,P,A>` | 能力抽象接口（泛型化） |
| `CommandExecutor<T,C,P,A>` | 命令执行器（超时+异常处理） |
| `CrossPageCommandQueue<T,C,P,A>` | 跨页面命令队列（TTL+重试） |
| `SceneManager` | 场景管理（单例+引用计数） |
| `AgentLogger` | 日志抽象接口 |

## 设计原则

**零业务依赖**：不依赖 `BeautySettings`、`FilterType`、`MediaType`、`ExecutionPlan` 等业务类型。
**泛型化**：通过 `<T, C, P, A>` 类型参数让业务模块注入具体类型。
**纯 Kotlin**：使用 `java-library` + `kotlin("jvm")` 插件，无 Android 依赖。

## 与 App 模块的关系

```
:agent-core (基础设施)
    ↑ 被依赖
:app (业务实现)
    - AgentCommand 密封类（含 BeautySettings 等）
    - Capability 接口（特化为 AgentCommand/AgentContext/PageContext/AgentAction）
    - CameraCapability / GalleryCapability / SettingsCapability
```

## 文件清单

- `src/main/java/com/picme/agent/core/Capability.kt` — 泛型 Capability 接口
- `src/main/java/com/picme/agent/core/CommandExecutor.kt` — 命令执行器
- `src/main/java/com/picme/agent/core/CrossPageCommandQueue.kt` — 跨页面队列
- `src/main/java/com/picme/agent/core/SceneManager.kt` — 场景管理器
- `src/main/java/com/picme/agent/core/AgentLogger.kt` — 日志接口

## 编译验证

```bash
./gradlew :agent-core:compileKotlin  # ✅ BUILD SUCCESSFUL
```