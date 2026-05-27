# PicMe

> **Agent First 工程试验场** —— 以 Agent 为中心的客户端框架与研发流程

PicMe 是一个元实验：我们探索「端侧 AI Agent 驱动应用」的技术可行性，更重要的是**验证 Agent First 的工程范式**——Agent 作为第一公民，主导从需求分析到质量验收的完整研发流程。

美颜相机只是试验载体，真正的研究对象是**面向 Agent 的架构设计与协作机制**。

---

## 三重实验维度

```
┌─────────────────────────────────────────────────────────────────┐
│  维度 1: 端侧 Agent 架构（运行时）                                │
│  ├─ 目标：验证 LLM 能否成为应用的中枢神经系统                      │
│  └─ 产出：Agent Runtime、Capability 系统、对话式交互范式          │
├─────────────────────────────────────────────────────────────────┤
│  维度 2: Agent First 客户端框架（架构层）                          │
│  ├─ 目标：让 Agent 高效理解、修改、扩展代码                        │
│  └─ 产出：显式边界、声明式状态、自描述能力、结构化可观测性          │
├─────────────────────────────────────────────────────────────────┤
│  维度 3: Agent First 研发流程（流程层）                            │
│  ├─ 目标：Agent 主导协作，基础设施原子化为可调用的 Tools           │
│  └─ 产出：角色化协作、Self-Heal、即时验证、文档驱动开发            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 面向 Agent 的架构设计

PicMe 的每一个架构决策都遵循**显式优于隐式**的原则——让 Agent 通过代码结构本身即可理解系统，而非依赖易腐烂的注释或隐性约定。

### 1. 显式架构边界

构造函数即文档，依赖关系一目了然。

```kotlin
// ❌ 隐式依赖：Agent 需要全局搜索才能理解
class CameraViewModel {
    private val beautyEngine = BeautyEngine.getInstance()
}

// ✅ 显式注入：Agent 通过签名即可理解协作关系
class CameraViewModel(
    private val beautyEngine: BeautyEngine,
    private val agentUseCase: AiAgentUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel()
```

### 2. 声明式状态空间

枚举所有合法状态，消除隐式组合。

```kotlin
sealed interface CameraUiState {
    data object Initializing : CameraUiState
    data class Previewing(
        val beautySettings: BeautySettings,
        val agentDialogState: AgentDialogState
    ) : CameraUiState
    data class Error(val reason: String, val recoverable: Boolean) : CameraUiState
}
```

### 3. 自描述能力系统

Capability 自包含元数据，Agent 可反射发现。

```kotlin
class AdjustBeautyCapability : Capability {
    override val id = "adjust_beauty"
    override val description = "调节美颜参数（磨皮、美白、瘦脸、大眼）"
    override val parameters = listOf(
        Parameter("smooth", ParameterType.INT, range = 0..100),
        Parameter("whiten", ParameterType.INT, range = 0..100)
    )
    
    override suspend fun execute(params: Map<String, Any>): Result<Unit> { }
}
```

### 4. 结构化可观测性

纯文本日志 → 结构化事件，Agent 可消费、可诊断。

```kotlin
Logger.log(LogEvent.AgentCommandParsed(
    rawInput = "调高美颜",
    parsedIntent = Intent.AdjustBeauty,
    confidence = 0.95,
    extractedParams = mapOf("smooth" to 50)
))
```

### 5. 文档即契约

| 文档 | 职责 | 价值 |
|------|------|------|
| `PRODUCT.md` | 产品目标与验收标准 | 理解「为什么做」 |
| `docs/FEATURES.md` | 交互流程与体验规则 | 理解「用户怎么用」 |
| `AGENTS.md` | 模块规范与实现约束 | 理解「代码怎么写」 |
| `[kimi-task]` | 可执行的任务描述 | 直接解析为执行计划 |

---

## Agent 协作与 Tools 层

PicMe 采用**角色化协作模型**：CO、PM、RD、CR、QA 各司其职，通过标准化的 **Tools 层** 完成验证闭环。

### Tools 层：基础设施的原子化

传统研发流程中，编译、安装、测试、验证是离散的手动步骤。在 Agent First 范式中，这些基础设施被封装为**原子化的 Tools**，供 Agent 按需调用、组合编排：

```kotlin
// RD 编排 Tools 完成验证闭环
val verificationChain = listOf(
    CompileTool(),        // 编译检查
    InstallTool(),        // 安装到设备
    ScreenshotTool(),     // 截屏验证
    LogAnalysisTool()     // 日志分析
)

fun selfHeal(task: Task) {
    verificationChain.forEach { tool ->
        val result = tool.execute()
        if (!result.success) fixAndRetry(result.errors)
    }
}
```

**Tools 化带来的转变**：
- 验证从「批量测试」变为「即时反馈」
- 日志从「人工浏览」变为「结构化消费」
- 修复从「被动等待」变为「主动自愈」

### 角色分工

```
User Request
    ↓
[CO] 协调者 ──→ 任务分级、状态板维护
    ↓
[PM] 产品经理 ──→ 更新 PRODUCT.md、FEATURES.md
    ↓
[RD] 全栈工程师 ──→ 代码实现、文档同步、Tools 编排验证
    ↓
[CR] 规范守护者 ──→ 架构合规审查
    ↓
[QA] 质量专家 ──→ 边界测试、验收确认
    ↓
Delivered
```

### 自动化脚本

| 脚本 | 功能 | 触发者 |
|------|------|--------|
| `./scripts/auto-dev-loop.sh` | 编译→安装→截屏→日志→报告 | RD |
| `./scripts/ai-gate.sh` | 代码质量门禁 | CI |
| `./scripts/impact-analyzer.sh` | 变更影响分析 | CO |
| `./scripts/doc-sync-guardian.sh` | 文档同步检查 | CR |
| `./scripts/test-generator.py` | 测试骨架生成 | RD |
| `./scripts/screenshot-diff.py` | UI 回归检测 | QA |

---

## 核心特性（运行时）

### 🤖 Agent 交互
- 自然语言控制相机：「调高美颜」「换个冷调滤镜」「拍一张」
- 端侧 Qwen3-0.6B 推理，零网络依赖
- Capability 系统支持热插拔

### 📷 实时美颜
- 自研 OpenGL ES 渲染管线
- 磨皮、美白、瘦脸、大眼、唇色、腮红
- GPU 离屏拍照，预览/输出一致性

### 🔒 100% 端侧
- LLM、人脸检测、OCR 全部本地运行
- 零云端，零网络权限

---

## 架构概览

```
┌─────────────────────────────────────────────────────────┐
│  User Interface (Compose)                                 │
│  ├─ 相机预览 + Agent 对话面板                              │
│  └─ 传统控制栏（快捷入口）                                 │
├─────────────────────────────────────────────────────────┤
│  Agent Runtime (domain/agent/)                            │
│  ├─ AgentOrchestrator      意图解析与任务编排              │
│  ├─ LocalLlmEngine         Qwen3-0.6B / MNN-LLM           │
│  ├─ CapabilityRegistry     设备能力路由（自描述）          │
│  ├─ MemoryManager          对话上下文                     │
│  └─ PrivacyGuard           隐私分级守卫                   │
├─────────────────────────────────────────────────────────┤
│  Capability Layer（可插拔、自描述）                        │
│  ├─ CameraCapability       相机控制                       │
│  ├─ BeautyCapability       美颜参数调节                   │
│  └─ SystemCapability       系统级操作                     │
├─────────────────────────────────────────────────────────┤
│  beauty-engine (OpenGL ES + EGL)                          │
│  ├─ CameraPreviewRenderer  预览渲染                       │
│  ├─ BeautyRenderer         美颜 Shader 管线               │
│  ├─ PhotoProcessorImpl     GPU 离屏拍照                   │
│  └─ FaceDetectionEngine    多引擎人脸检测                 │
└─────────────────────────────────────────────────────────┘
```

---

## 快速开始

```bash
# 克隆项目
git clone https://github.com/littleseven/PicMe.git
cd PicMe

# 构建 Debug APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# 自动化开发闭环（编译→安装→验证→报告）
./scripts/auto-dev-loop.sh
```

---

## 研究价值

### 已验证的假设

| 假设 | 结论 | 证据 |
|------|------|------|
| 显式架构可被 Agent 高效理解 | ✅ 成立 | RD Agent 成功实现多模块功能 |
| 文档驱动开发减少沟通损耗 | ✅ 成立 | PRODUCT→FEATURES→AGENTS 链条有效 |
| Tools 化支持 Self-Heal 闭环 | ✅ 成立 | 编译/安装/验证自动化 |
| Capability 系统支持热插拔 | ✅ 成立 | 新增能力无需修改 Agent 核心 |

### 待验证的问题

1. **规模上限**：Agent 能高效处理的代码库规模上限是多少？
2. **复杂重构**：Agent 能否主导跨模块架构重构？
3. **跨项目迁移**：learned patterns 能否迁移到其他项目？
4. **人机协作边界**：哪些决策必须人工介入？
5. **Tools 扩展性**：新 Tools 能否被 Agent 自动发现？

---

## 文档

| 文档 | 内容 |
|------|------|
| [`PRODUCT.md`](PRODUCT.md) | 产品定义、核心命题、验收标准 |
| [`docs/FEATURES.md`](docs/FEATURES.md) | 功能交互细节 |
| [`AGENTS.md`](AGENTS.md) | Agent First 治理规范 |
| [`agents/README.md`](agents/README.md) | Agent 角色定义与执行手册 |

---

## 许可

MIT License — 仅用于研究与学习目的。
