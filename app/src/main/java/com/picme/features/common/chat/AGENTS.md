# AI Chat UI 组件库

统一聊天界面组件，为 PicMe 所有模块提供一致的 Chat UI 体验。

## 组件列表

### 1. AiChatScreen

主聊天界面组件，使用 ModalBottomSheet 设计。

**特性：**
- ModalBottomSheet 设计，系统自动处理键盘 insets
- 折叠/展开功能，节省屏幕空间
- 文字 + 语音输入切换
- 支持多种消息类型（UserText、AgentText、PlanPreview、PlanProgress、PlanResult）
- 优雅的动画效果
- 拖拽把手设计

**使用示例：**

```kotlin
@Composable
fun MyScreen() {
    val chatState = remember { mutableStateOf(ChatState()) }
    
    AiChatScreen(
        visible = chatState.isVisible,
        messages = chatState.messages,
        isProcessing = chatState.isProcessing,
        onVisibleChange = { chatState.isVisible = it },
        onSendMessage = { input ->
            // Handle message send
            chatState.messages.add(AgentMessage.UserText(input))
            
            // Simulate AI response
            LaunchedEffect(input) {
                delay(1000)
                chatState.messages.add(AgentMessage.AgentText("Response"))
            }
        },
        onCommand = { command ->
            // Execute agent command
        },
        onPlanConfirm = {
            // Confirm plan execution
        },
        onPlanCancel = {
            // Cancel plan execution
        }
    )
}
```

### 2. AgentMessage

消息类型定义，支持以下类型：

```kotlin
sealed class AgentMessage {
    data class UserText(val content: String) : AgentMessage()
    data class AgentText(val content: String) : AgentMessage()
    data class PlanPreview(val content: String, val plan: ExecutionPlan? = null) : AgentMessage()
    data class PlanProgress(val content: String) : AgentMessage()
    data class PlanResult(val content: String) : AgentMessage()
}
```

**消息样式：**
- **UserText**: 右侧显示，主题色背景，白色文字
- **AgentText**: 左侧显示，深灰色半透明背景，白色文字
- **PlanPreview**: 左侧显示，主题色文字，带确认/取消按钮
- **PlanProgress**: 左侧显示，次要颜色文字，浅背景
- **PlanResult**: 左侧显示，第三颜色文字，中等背景

## 设计原则

### 1. 显式优于隐式
所有回调和状态都通过参数显式传递，避免隐式依赖。

### 2. 枚举优于条件
使用 sealed class 定义消息类型，确保类型安全。

### 3. 自描述优于注释
组件名称和参数名清晰表达意图，减少注释需求。

### 4. 结构化可观测性
消息类型包含完整上下文信息，便于日志记录和调试。

## 与旧版对比

### 旧版 (Gallery AiChatPanel)
```kotlin
// ❌ 简单的 Dialog 设计
Dialog(onDismissRequest = onDismiss) {
    Surface(...) {
        Column(...) {
            // Basic layout
        }
    }
}
```

### 新版 (AiChatScreen)
```kotlin
// ✅ ModalBottomSheet + 折叠 + 动画
ModalBottomSheet(...) {
    AiChatScreenContent(...) {
        // Rich features
    }
}
```

**改进点：**
1. 使用 ModalBottomSheet 替代 Dialog，更好的系统集成
2. 添加折叠/展开功能，节省屏幕空间
3. 优雅的滑入滑出动画
4. 拖拽把手设计，提升交互体验
5. 支持更多消息类型（PlanPreview、PlanProgress、PlanResult）
6. 统一的视觉风格，符合 Material Design 3

## 跨模块复用

所有模块都应使用此统一组件：

- ✅ **Camera**: 已使用类似设计（AiAgentPanel）
- ✅ **Gallery**: 已迁移到 AiChatScreen
- ⏳ **Settings**: 待迁移（如有需要）

## 维护说明

- 所有颜色使用 `MaterialTheme.colorScheme`，确保主题适配
- 所有文案提取到 `strings.xml`，支持多语言
- 新增消息类型时，需同时更新 `AgentMessage` sealed class 和 `ChatBubble` 的 when 表达式
