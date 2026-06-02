# PicMe Wiki - 技术文档

## 📚 文档导航

### 核心文档
- [README.md](../../README.md) - 项目概览与快速开始
- [PRODUCT.md](../../PRODUCT.md) - 产品定义与验收标准
- [AGENTS.md](../../AGENTS.md) - Agent First 研发范式治理文档

### 技术专题
- [Chat UI 统一化](./02-TECH/CHAT_UI_UNIFICATION.md) - 统一聊天界面组件设计与实现
- [美颜引擎架构](./03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) - 自研 OpenGL ES 渲染管线
- [Agent 运行时架构](./02-ARCHITECTURE/AGENT_ARCHITECTURE.md) - Agent 编排与 Capability 系统
- [人脸检测方案](./03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md) - InsightFace + MediaPipe 双引擎

---

## 🔧 最新技术更新

### 2026-05: Chat UI 统一化改造

**背景**: PicMe 项目中存在两套 Chat UI 实现（Camera 和 Gallery），导致用户体验不一致、维护成本高。

**解决方案**: 创建统一的 `AiChatScreen` 组件，为所有模块提供一致的聊天界面体验。

**核心改进**:
1. **零重复代码** - Camera 与 Gallery 共享同一套 UI 逻辑
2. **完全一致体验** - 两个页面的 Chat UI 视觉效果和交互完全相同
3. **易于维护** - 修改一处，所有页面受益
4. **未来可扩展** - 新增消息类型只需修改 sealed class

**技术亮点**:
- ModalBottomSheet 设计，系统自动处理键盘 insets
- 折叠/展开功能，拖拽把手直观操作
- 优雅的滑入滑出动画
- 支持文字 + 语音输入切换
- 5 种消息类型（UserText、AgentText、PlanPreview、PlanProgress、PlanResult）
- Material Design 3 主题适配，深色模式完美支持

**相关文件**:
- [`AiChatScreen.kt`](../app/src/main/java/com/picme/features/common/chat/AiChatScreen.kt) - 主组件
- [`AgentMessage.kt`](../app/src/main/java/com/picme/features/common/chat/AgentMessage.kt) - 消息类型定义
- [`CHAT_UI_UNIFICATION.md`](./02-TECH/CHAT_UI_UNIFICATION.md) - 详细技术文档
- [`README.md`](../README.md) - 构建与使用入口文档

**对比效果**:

| 特性 | 旧版 Gallery | Camera | 新版 Gallery |
|------|-------------|--------|-------------|
| ModalBottomSheet | ❌ Dialog | ✅ | ✅ |
| 折叠/展开 | ❌ | ✅ | ✅ |
| 拖拽把手 | ❌ | ✅ | ✅ |
| 滑入滑出动画 | ❌ | ✅ | ✅ |
| 语音输入 | ❌ | ✅ | ✅ |
| PlanPreview 按钮 | ❌ | ✅ | ✅ |
| Material3 主题 | ⚠️ 部分 | ✅ | ✅ |

**使用示例**:

```kotlin
@Composable
fun MyFeatureScreen() {
    val chatState = remember { mutableStateOf(ChatState()) }
    
    AiChatScreen(
        visible = chatState.isVisible,
        messages = chatState.messages,
        isProcessing = chatState.isProcessing,
        onVisibleChange = { chatState.isVisible = it },
        onSendMessage = { input ->
            // Handle message send
        },
        onCommand = { command ->
            // Execute agent command
        }
    )
}
```

**完整文档**: 详见 [Chat UI 统一化技术文档](./02-TECH/CHAT_UI_UNIFICATION.md)；Wiki 镜像入口见 `docs/wiki/index.md`

---

## 📖 核心概念

### Agent First 研发范式

PicMe 不仅是一款应用，更是对「Agent 能否主导软件研发全流程」的系统性验证。

**三重实验维度**:
1. **端侧 Agent 架构** - 验证 LLM 能否成为应用的中枢神经系统
2. **Agent First 客户端框架** - 让 Agent 高效理解、修改、扩展代码
3. **Agent First 研发流程** - Agent 主导协作，基础设施原子化为 Tools

**已验证的假设**:
- ✅ 显式架构可被 Agent 高效理解
- ✅ 文档驱动开发减少沟通损耗
- ✅ Tools 化支持 Self-Heal 闭环
- ✅ Capability 系统支持热插拔

### 技术原则

#### 显式优于隐式（Explicit > Implicit）
```kotlin
// ❌ 隐式依赖
object BeautyEngine {
    fun getInstance() = instance
}

// ✅ 显式注入
class CameraViewModel(
    private val beautyEngine: BeautyEngine,
    private val agentUseCase: AiAgentUseCase
) : ViewModel()
```

#### 枚举优于条件（Exhaustive > Conditional）
```kotlin
// ❌ 布尔标志组合爆炸
class CameraState(
    val isLoading: Boolean,
    val hasError: Boolean
)

// ✅ 枚举所有合法状态
sealed interface CameraState {
    data object Initializing : CameraState
    data class Previewing(val settings: BeautySettings) : CameraState
    data class Error(val reason: String) : CameraState
}
```

#### 自描述优于注释（Self-Describing > Commented）
```kotlin
// ❌ 注释与代码可能脱节
fun adjust(params: Map<String, Int>) // AI 不知道有哪些参数

// ✅ 类型系统即文档
data class BeautyParameters(
    val smooth: IntRange = 0..100,
    val whiten: IntRange = 0..100
)
fun adjust(params: BeautyParameters) // 类型即契约
```

---

## 🏗 架构设计

### 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│  User Interface (Jetpack Compose)                             │
│  ├─ 相机预览 + Agent 对话面板                                  │
│  ├─ 实时美颜调节面板                                          │
│  └─ 相册浏览与静态图编辑                                       │
├──────────────────────────────────────────────────────────────┤
│  Agent Runtime (domain/agent/)                                │
│  ├─ AgentOrchestrator      意图解析与任务编排                  │
│  ├─ LocalLlmEngine         Qwen3-1.7B / MNN-LLM 推理         │
│  ├─ CapabilityRegistry     设备能力路由（自描述元数据）        │
│  └─ PrivacyGuard           隐私分级守卫                        │
├──────────────────────────────────────────────────────────────┤
│  Capability Layer（可插拔、自描述）                            │
│  ├─ CameraCapability       相机控制                          │
│  ├─ BeautyCapability       美颜参数调节                      │
│  ├─ GalleryCapability      相册管理                          │
│  └─ SettingsCapability     设置管理                          │
├──────────────────────────────────────────────────────────────┤
│  beauty-engine (独立模块 · OpenGL ES + EGL)                   │
│  ├─ CameraPreviewRenderer  实时预览渲染                      │
│  ├─ BeautyRenderer         美颜 Shader 多 Pass 管线            │
│  └─ PhotoProcessorImpl     GPU 离屏拍照处理                    │
└──────────────────────────────────────────────────────────────┘
```

### 关键设计决策

#### 为什么选择 DataStore 而非 SharedPreferences？
- ✅ **类型安全** - 编译期检查 key 和 value 类型
- ✅ **Flow 原生支持** - 响应式数据流，自动通知订阅者
- ✅ **异步非阻塞** - 避免主线程 IO 操作
- ✅ **协程友好** - 与 Kotlin Coroutines 深度整合

#### 为什么使用 Sealed Class 表示状态？
- ✅ ** exhaustiveness check** - 编译器确保 when 表达式覆盖所有情况
- ✅ **类型安全** - 每个状态有明确的子类型和属性
- ✅ **自文档化** - 状态空间一目了然，无需额外注释
- ✅ **重构友好** - 修改状态时编译器会提示所有受影响位置

#### 为什么采用 Agent First 架构？
- ✅ **自然语言交互** - 降低用户使用门槛
- ✅ **能力热插拔** - 新增功能无需修改核心逻辑
- ✅ **上下文记忆** - 多轮对话连贯自然
- ✅ **可扩展性强** - 支持复杂任务编排

---

## 📊 性能指标

### 性能红线（必须满足）

| 指标 | 目标值 | 当前值 | 状态 |
|------|--------|--------|------|
| 冷启动时间 | < 500ms | ~450ms | ✅ |
| 快门延迟 | < 50ms | ~30ms | ✅ |
| 美颜预览延迟 | < 100ms | ~80ms | ✅ |
| Chat UI 帧率 | 60fps | 60fps | ✅ |
| OCR 识别时间 | < 2s | ~1.5s | ✅ |

### 内存占用

| 场景 | 目标 | 当前 |
|------|------|------|
| 空闲状态 | < 150MB | ~130MB |
| 相机预览 | < 300MB | ~280MB |
| Chat 面板打开 | < 350MB | ~320MB |

---

## 🛠 开发指南

### 环境要求

- **Android Studio**: Hedgehog (2023.1.1) 或更高版本
- **JDK**: 17 或更高版本
- **Android SDK**: API 24+ (Min), API 34+ (Target)
- **NDK**: r25c 或更高版本

### 快速开始

```bash
# 克隆仓库
git clone https://github.com/littleseven/PicMe.git
cd PicMe

# 构建 Debug APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 查看日志
adb logcat -s "PicMe:*"
```

### 常用命令

| 命令 | 说明 |
|------|------|
| `./gradlew clean` | 清理构建缓存 |
| `./gradlew test` | 运行单元测试 |
| `./gradlew connectedAndroidTest` | 运行仪器测试 |
| `./gradlew lint` | 静态代码检查 |
| `./scripts/auto-dev-loop.sh` | 一键开发闭环 |

### 代码风格

本项目使用 **Ktlint** 进行代码风格检查：

```bash
# 检查代码风格
./gradlew ktlintCheck

# 自动修复风格问题
./gradlew ktlintFormat
```

---

## 🤝 贡献指南

### 提交规范

Commit message 遵循 **[Conventional Commits](https://www.conventionalcommits.org/)** 规范：

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

**Type 列表**:
- `feat`: 新功能
- `fix`: Bug 修复
- `docs`: 文档更新
- `style`: 代码格式调整
- `refactor`: 重构
- `test`: 测试相关
- `chore`: 构建/工具相关

**示例**:
```
feat(chat-ui): 统一 Camera 与 Gallery 的聊天界面设计

- 新增 AiChatScreen 组件
- 迁移 Gallery 页面到统一组件
- 添加折叠/展开功能
- 支持语音输入切换

Closes #123
```

### Pull Request 流程

1. **Fork 仓库** - 创建自己的 fork
2. **创建分支** - `git checkout -b feature/new-feature`
3. **提交更改** - 遵循 commit 规范
4. **运行测试** - 确保所有测试通过
5. **推送分支** - `git push origin feature/new-feature`
6. **创建 PR** - 在 GitHub 上创建 Pull Request
7. **Code Review** - 等待 CR Agent 审查
8. **合并** - 审查通过后合并到 main

---

## 📄 许可

MIT License - 自由使用、学习、二次开发。

---

## 📞 联系方式

- **项目主页**: https://github.com/littleseven/PicMe
- **Issue 反馈**: https://github.com/littleseven/PicMe/issues
- **技术讨论**: GitHub Discussions

---

<p align="center">
  <i>PicMe — 让相机听懂你说的话</i>
</p>
