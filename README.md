# PicMe

> **AI Coding 范式试验场** —— 探索 AI 友好的客户端框架与研发流程

PicMe 是一个元实验：我们不仅探索「端侧 AI Agent 驱动应用」的技术可行性，更重要的是**探索如何让 AI 成为软件开发的主导力量**——从需求分析到代码实现，从架构设计到质量验收。

美颜相机只是试验载体，真正的研究对象是**AI 友好的工程体系**。

---

## 三重实验维度

```
┌─────────────────────────────────────────────────────────────────┐
│  维度 1: 端侧 Agent 架构（运行时）                                │
│  ├─ 目标：验证 LLM 能否成为应用的中枢神经系统                      │
│  └─ 产出：Agent Runtime、Capability 系统、对话式交互范式          │
├─────────────────────────────────────────────────────────────────┤
│  维度 2: AI 友好的客户端框架（架构层）                             │
│  ├─ 目标：让 AI 能高效理解、修改、扩展代码                         │
│  └─ 产出：显式架构、声明式 UI、自描述代码、结构化可观测性           │
├─────────────────────────────────────────────────────────────────┤
│  维度 3: AI 主导的研发流程（流程层）                               │
│  ├─ 目标：让 AI 成为开发流程的 orchestrator                       │
│  └─ 产出：角色化协作、Self-Heal、自动化工具链、文档驱动开发         │
└─────────────────────────────────────────────────────────────────┘
```

---

## AI 友好的客户端架构

PicMe 的架构设计遵循**AI 优先原则**——每一个设计决策都考虑「AI 是否能高效理解和操作」。

### 1. 显式架构边界（Explicit Boundaries）

```kotlin
// ❌ AI 不友好：隐式依赖，分散在各处
class CameraViewModel {
    private val beautyEngine = BeautyEngine.getInstance() // 隐式全局状态
}

// ✅ AI 友好：显式依赖注入，接口契约清晰
class CameraViewModel(
    private val beautyEngine: BeautyEngine,              // 显式依赖
    private val agentUseCase: AiAgentUseCase,            // 显式依赖
    private val settingsRepository: SettingsRepository   // 显式依赖
) : ViewModel()
```

**AI 收益**：AI 通过构造函数即可理解组件关系，无需全局代码搜索。

### 2. 声明式状态管理（Declarative State）

```kotlin
// ✅ AI 友好：Sealed Class 枚举所有可能状态
sealed interface CameraUiState {
    data object Initializing : CameraUiState
    data class Previewing(
        val beautySettings: BeautySettings,
        val agentDialogState: AgentDialogState
    ) : CameraUiState
    data class Error(val reason: String, val recoverable: Boolean) : CameraUiState
}
```

**AI 收益**：状态转移图显式编码，AI 可枚举所有状态组合，不会遗漏边界情况。

### 3. 自描述 Capability 系统（Self-Describing Capabilities）

```kotlin
// ✅ AI 友好：每个能力自包含元数据
class AdjustBeautyCapability : Capability {
    override val id = "adjust_beauty"
    override val description = "调节美颜参数（磨皮、美白、瘦脸、大眼）"
    override val parameters = listOf(
        Parameter("smooth", ParameterType.INT, range = 0..100),
        Parameter("whiten", ParameterType.INT, range = 0..100)
    )
    
    override suspend fun execute(params: Map<String, Any>): Result<Unit> {
        // 实现自包含，AI 可直接理解输入输出
    }
}
```

**AI 收益**：Agent 通过反射即可发现可用能力，自动生成工具描述（Tool Description）。

### 4. 结构化可观测性（Structured Observability）

```kotlin
// ✅ AI 友好：结构化日志，非纯文本
Logger.log(LogEvent.AgentCommandParsed(
    rawInput = "调高美颜",
    parsedIntent = Intent.AdjustBeauty,
    confidence = 0.95,
    extractedParams = mapOf("smooth" to 50)
))
```

**AI 收益**：AI 可解析日志结构，自动诊断问题、生成报告，无需正则表达式提取。

### 5. 文档即契约（Docs as Contract）

| 文档 | 职责 | AI 价值 |
|------|------|---------|
| `PRODUCT.md` | 产品目标与验收标准 | AI 理解「为什么要做」 |
| `docs/FEATURES.md` | 交互流程与体验规则 | AI 理解「用户怎么用」 |
| `AGENTS.md` | 模块规范与实现约束 | AI 理解「代码该怎么写」 |
| `[kimi-task]` | 可执行的任务描述 | AI 直接解析为执行计划 |

**AI 收益**：需求→设计→实现的链条显式化，AI 可在各层之间保持一致性。

---

## AI 主导的研发流程

PicMe 采用**AI 角色化协作模型**——将传统 SDLC 转换为 AI 可执行的流程。

### 角色分工（CO → PM → RD → CR → QA）

```
User Request
    ↓
[CO] 协调者 ──→ 任务分级、状态板维护
    ↓
[PM] 产品经理 ──→ 更新 PRODUCT.md、FEATURES.md
    ↓
[RD] 全栈工程师 ──→ 代码实现、文档同步
    ↓
[CR] 规范守护者 ──→ 架构合规审查
    ↓
[QA] 质量专家 ──→ 边界测试、验收确认
    ↓
Delivered
```

### Self-Heal 工作流

```bash
# RD 执行代码变更
./scripts/auto-dev-loop.sh

# AI 自动完成：
# 1. 编译检查 (ktlint/detekt/编译)
# 2. 安装到设备
# 3. 自动截屏对比
# 4. 日志收集与分析
# 5. 生成报告（成功/失败/警告）
```

**AI 收益**：RD Agent 具备闭环验证能力，减少人工介入。

### 自动化工具链

| 脚本 | 功能 | AI 调用方式 |
|------|------|-------------|
| `impact-analyzer.sh` | 变更影响分析 | CO Agent 自动触发，决定任务分级 |
| `doc-sync-guardian.sh` | 文档同步检查 | CR Agent 验证 PR 时调用 |
| `test-generator.py` | 测试骨架生成 | RD Agent 实现功能后自动补充测试 |
| `screenshot-diff.py` | UI 回归检测 | QA Agent 验收时自动比对 |

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

# AI 自动化开发闭环
./scripts/auto-dev-loop.sh
```

---

## 研究价值

### 已验证的假设

| 假设 | 结论 | 证据 |
|------|------|------|
| AI 能理解显式架构并生成合规代码 | ✅ 成立 | RD Agent 成功实现多模块功能 |
| 文档驱动开发可减少沟通损耗 | ✅ 成立 | PRODUCT→FEATURES→AGENTS 链条有效 |
| Self-Heal 可减少人工介入 | ✅ 成立 | 编译/安装/验证闭环自动化 |
| Capability 系统支持热插拔 | ✅ 成立 | 新增能力无需修改 Agent 核心 |

### 待验证的问题

1. **规模上限**：AI 能高效处理的代码库规模上限是多少？
2. **复杂重构**：AI 能否主导大规模架构重构？
3. **跨项目迁移**： learned patterns 能否迁移到其他项目？
4. **人机协作边界**：哪些决策必须人工介入，哪些可完全自动化？

---

## 文档

| 文档 | 内容 |
|------|------|
| [`PRODUCT.md`](PRODUCT.md) | 产品定义、核心命题、验收标准 |
| [`docs/FEATURES.md`](docs/FEATURES.md) | 功能交互细节 |
| [`AGENTS.md`](AGENTS.md) | AI 协作治理规范 |
| [`agents/README.md`](agents/README.md) | AI 角色定义与执行手册 |

---

## 许可

MIT License — 仅用于研究与学习目的。
