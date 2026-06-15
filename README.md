<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/minSdk-24-3DDC84" alt="Min SDK">
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/OpenGL-ES%203.0-5586A4?logo=opengl&logoColor=white" alt="OpenGL ES">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
</p>

<h1 align="center">觅影相机-PicMe</h1>

<p align="center">
  <b>下一代端侧 AI 智能相机</b><br>
  <i>自然语言驱动 · 全链路 GPU 渲染 · 端侧智能</i>
</p>

<p align="center">
  <a href="#-核心特性">特性</a> ·
  <a href="#-技术架构">架构</a> ·
  <a href="#-快速开始">快速开始</a> ·
  <a href="#-文档">文档</a> ·
  <a href="#-agent-first-研发范式">Agent 范式</a>
</p>

---

## 概览

PicMe 是一款探索「端侧 AI Agent 驱动应用」的智能相机应用。用户可以通过**自然语言**与相机交互——说「调高美颜」「换个冷调滤镜」「拍一张」即可控制全部功能，无需手动调节复杂参数。

项目同时是一个**Agent First 工程试验场**，验证 Agent 作为研发流程第一公民的可行性，探索面向 Agent 的架构设计、协作机制与研发范式。

> 美颜相机是试验载体，真正的研究对象是**端侧 Agent 运行时架构**与**Agent First 工程范式**。

---

## ✨ 核心特性

### 🤖 自然语言交互

| 你说 | PicMe 做 |
|------|----------|
| 「拍张照」 | 立即拍摄并保存 |
| 「调高美颜」 | 平滑提升磨皮/美白强度 |
| 「换个冷调滤镜」 | 切换冷色调风格滤镜 |
| 「打开前置」 | 翻转至前置摄像头 |

- **端侧运行 Qwen3-1.7B 大模型**，本地推理处理相机控制指令，响应快、无网络依赖
- **Agent Runtime 模块化**：核心编排逻辑位于 `:agent-core` 纯 Kotlin 模块，`app` 层通过 `AiAgentUseCase` Facade 桥接
- **远程 LLM 编排**（可选）：配置个人 OpenAI/Claude 兼容 Token 解锁云端推理，支持 Kimi/OpenAI API
- **Capability 系统**支持热插拔扩展，新增能力零侵入 Agent 核心
- **多轮对话上下文记忆**（MemoryManager），交互体验连贯自然
- **语音控制**（试验性）：唤醒词触发 + 语音指令识别，快速迭代验证中

### 📷 自研实时美颜引擎

- **全自研 OpenGL ES + EGL 渲染管线**（无第三方美颜 SDK）
- 完整美颜链路：磨皮、美白、瘦脸、大眼、唇色、腮红、眉毛
- **GPU 离屏拍照**：预览与输出使用同一套 Shader，效果一致性 99%+
- **双引擎人脸检测**：MediaPipe 468→106（主）+ MNN/NCNN（备），InsightFace ONNX 路径已移除
- **帧同步妆容系统**：解决检测帧率与渲染帧率不匹配导致的"妆容甩飞"问题

### 🔒 隐私与离线能力

| 功能 | 运行位置 | 网络依赖 |
|------|----------|----------|
| 本地 LLM 推理 | 本地 MNN-LLM | 可离线 |
| 人脸检测 | 本地 MediaPipe / NCNN / MNN | 零云端推理 |
| OCR 文字识别 | 本地 ML Kit | 零云端推理 |
| 美颜渲染 | 本地 GPU | 零云端推理 |
| 远程复杂编排 | Kimi API（可选） | 仅非敏感指令 |

PicMe 核心 AI 能力（美颜、人脸检测、OCR、本地指令解析）均可离线运行；远程 LLM 为可选增强，仅处理非敏感指令。

---

## 🏗 技术架构

```
┌────────────────────────────────────────────────────────────────┐
│                  App Layer (Jetpack Compose)                    │
│  ┌───────────┐ ┌────────────┐ ┌─────────┐ ┌────────────┐      │
│  │  Camera   │ │  Gallery   │ │  Editor │ │  Settings  │      │
│  │ 预览+对话  │ │  相册浏览   │ │  静态图  │ │  偏好配置   │      │
│  └─────┬─────┘ └──────┬─────┘ └────┬────┘ └──────┬─────┘      │
│        └──────────────┼────────────┼─────────────┘             │
│                ┌──────┴────────────┴──────┐                     │
│                │ Capability Layer (热插拔) │                     │
│                │ Camera·Beauty·Gallery    │                     │
│                │ Settings·Navigation      │                     │
│                └────────────┬─────────────┘                     │
│                             │                                   │
│          AiAgentUseCase (Facade 桥接层)                          │
├─────────────────────────────┼───────────────────────────────────┤
│        :agent-core (纯 Kotlin · 端侧 Agent 中枢)                 │
│  ┌──────────────────────────┴──────────────────────────────┐   │
│  │  AgentOrchestrator (编排核心)                             │   │
│  │  ┌──────────────────┐  ┌──────────────────┐              │   │
│  │  │ LOCAL 本地路径    │  │ REMOTE 远程路径   │              │   │
│  │  │ Qwen3-1.7B       │  │ Kimi / OpenAI    │              │   │
│  │  │ MNN-LLM 推理     │  │ 兼容 API         │              │   │
│  │  │ (100% 离线)      │  │ (仅 PUBLIC 指令)  │              │   │
│  │  └────────┬─────────┘  └────────┬─────────┘              │   │
│  │           └───────────┬─────────┘                        │   │
│  │               InferenceRouter ← PrivacyGuard             │   │
│  │               MemoryManager · SceneManager               │   │
│  │               CapabilityRegistry                         │   │
│  │  voice/ (试验性): ASR + VAD + AudioRecorder              │   │
│  └──────────────────────────────────────────────────────────┘   │
├────────────────────────────────────────────────────────────────┤
│         :beauty-api (纯 Kotlin · 接口契约层)                     │
│  BeautySettings  FilterType  StyleFilter  Face  FaceDetector    │
│  FrameSyncConfig  BeautyProcessor  PhotoProcessor               │
│                           ↑ 实现                                 │
│  :beauty-engine (Android Library · 自研 GPU 渲染引擎)            │
│  ┌────────────────────────────────────────────────────────┐     │
│  │ api/   BeautyPreviewEngine · PhotoProcessor            │     │
│  │        BeautyParams · BeautyPerfStats                  │     │
│  ├────────────────────────────────────────────────────────┤     │
│  │ render/  CameraPreviewRenderer · BeautyRenderer        │     │
│  │          ShaderModuleLoader (assets/shaders/)           │     │
│  │          PhotoProcessorImpl · EGLCore                  │     │
│  ├────────────────────────────────────────────────────────┤     │
│  │ internal/  FrameSyncManager · MotionTracker             │     │
│  │            MediaPipe + MNN + NCNN 多引擎人脸检测         │     │
│  └────────────────────────────────────────────────────────┘     │
└────────────────────────────────────────────────────────────────┘
```

### Agent 推理架构详解

PicMe 的 Agent Runtime 是项目的核心创新——验证 LLM 能否成为应用的「中枢神经系统」：

```
用户输入（自然语言）
    ↓
AgentOrchestrator.processUserInput()
    ├── 本地模式（LOCAL）：LocalLlmEngine → MNN-LLM（Qwen3-1.7B）
    │   └── 端侧推理，无网络依赖，适合明确相机控制指令
    ├── 远程模式（REMOTE）：InferenceRouter → RemoteOrchestrator
    │   └── 云端 LLM 编排，处理复杂推理与开放式对话
    └── 隐私守卫：PrivacyGuard.classify() 自动分级输入内容
        ├── RESTRICTED（坐标/人脸数据）→ 强制本地
        ├── SENSITIVE（照片/OCR）→ 强制本地
        └── PUBLIC（相机控制）→ 本地优先，可配置远程
    ↓
AgentCommandParser.parseLlmResponse() → 结构化 AgentCommand
    ↓
CapabilityRegistry.dispatch()
    ├── 同页面指令 → 直接执行
    └── 跨页面指令 → 自动入队，目标页面激活后执行
    ↓
CameraCapability / BeautyCapability / GalleryCapability ...
```

**关键设计决策**：
- **InferenceRouter 自适应路由**：根据指令复杂度、网络状态、模型可用性自动选择本地或远程推理
- **CapabilityRegistry 跨页面指令队列**：NavigationCapability 支持 "去相册删除第一张照片" 等跨场景指令，命令自动排队并在目标页面激活后执行
- **SceneManager 场景状态机**：Camera / Gallery / Settings 三场景，Capability 按场景注册和激活
- **MemoryManager 持久化记忆**：按场景分 session 存储，支持跨会话恢复，最大保留 10 轮历史

### 架构亮点

- **显式优于隐式**：构造函数即文档，依赖关系一目了然
- **声明式状态空间**：Sealed Class 枚举所有合法状态，消除隐式组合
- **自描述 Capability**：Capability 自包含元数据（名称、支持命令、活跃场景），Agent 可反射发现与调用
- **结构化可观测性**：纯文本日志 → 结构化事件，Agent 可消费、可诊断
- **文档即契约**：PRODUCT.md → FEATURES.md → AGENTS.md 三层文档体系驱动开发

---

## 🚀 快速开始

```bash
# 克隆仓库
git clone https://github.com/littleseven/PicMe.git
cd PicMe

# 构建 Debug APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# 一键开发闭环（编译 → 安装 → 截屏 → 日志 → 报告）
./scripts/auto-dev-loop.sh
```

### 常用命令

| 命令 | 说明 |
|------|------|
| `./gradlew test` | 运行 JVM 单元测试 |
| `./gradlew connectedAndroidTest` | 运行仪器测试（需设备） |
| `./gradlew lint` | 静态代码检查 |
| `./scripts/ai-gate.sh` | 代码质量门禁 |
| `adb logcat -s "PicMe:*"` | 查看 PicMe 日志 |

---

## 📚 文档

PicMe 采用**文档驱动开发**，所有设计决策、技术规格、验收标准均以文档形式固化：

| 层级 | 文档 | 读者 | 内容 |
|------|------|------|------|
| **导航** | [`docs/00-INDEX.md`](docs/00-INDEX.md) | 全部 | 完整文档导航索引 |
| **产品层** | [`PRODUCT.md`](PRODUCT.md) | PM/RD/QA | 产品定义、核心命题、验收标准 |
| | [`docs/01-PRODUCT/FEATURES.md`](docs/01-PRODUCT/FEATURES.md) | PM/RD/QA | 交互流程与体验规则 |
| | [`docs/01-PRODUCT/NFR_SPEC.md`](docs/01-PRODUCT/NFR_SPEC.md) | QA/RD | 性能/稳定性/隐私量化指标 |
| **架构层** | [`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md) | RD/CO | Agent 运行时架构、Capability 模型 |
| | [`docs/02-ARCHITECTURE/ADR/`](docs/02-ARCHITECTURE/ADR/) | RD/CR | 架构决策记录 |
| **技术规范** | [`docs/03-TECHNICAL-SPECS/`](docs/03-TECHNICAL-SPECS/) | RD | 美颜引擎、帧同步、人脸检测、相机预览 |
| **Agent 能力** | [`docs/04-AGENT-CAPABILITIES/`](docs/04-AGENT-CAPABILITIES/) | RD/PM | Capability 注册表、命令参考、实现指南 |
| **开发规范** | [`docs/05-DEVELOPMENT/`](docs/05-DEVELOPMENT/) | RD/CO | 工作流、CR 检查清单、任务标记规范 |
| **质量标准** | [`docs/06-QA/`](docs/06-QA/) | QA | 验收测试清单 |
| **标准词典** | [`docs/07-STANDARDS/`](docs/07-STANDARDS/) | 全部 | 坐标系标准、统一术语词典 |
| **容灾** | [`docs/08-FALLBACK/`](docs/08-FALLBACK/) | RD/QA | 引擎降级策略与恢复机制 |

---

## 🔬 Agent First 研发范式

PicMe 不仅是一款应用，更是对「Agent 能否主导软件研发全流程」的系统性验证——让 Agent 通过编排原子化 Tools，从辅助工具进化为研发主导力量。

### 核心方法论

PicMe 沉淀了四项让 Agent 高效工作的代码架构原则，确保 Agent 无需全局搜索即可推理系统行为：

| 原则 | 内涵 | 效果 |
|------|------|------|
| **显式优于隐式** | 构造函数即文档，依赖关系一目了然 | Agent 无需跨文件搜索即可理解组件协作 |
| **枚举优于条件** | Sealed Class 枚举所有合法状态，消除隐式组合 | Agent 可枚举全部边界情况，不遗漏 |
| **自描述优于注释** | 类型系统即契约，参数范围编译期校验 | Agent 靠类型推导而非易腐烂的注释 |
| **结构化可观测性** | 日志从纯文本升级为结构化事件 | Agent 可消费自身日志，实现自我诊断 |

### 三重实验维度

| 维度 | 目标 | 当前状态 | 关键产出 |
|------|------|----------|----------|
| **端侧 Agent 运行时** | 验证 LLM 能否成为应用中枢神经系统 | ✅ 稳定运行 | Qwen3-1.7B 本地推理、15+ Capability 热插拔系统、多轮对话记忆、语音交互 |
| **Agent First 客户端框架** | 让 Agent 高效理解/修改/扩展代码 | ✅ 模式成熟 | 4 项架构原则、`:agent-core` 纯 Kotlin 模块、`:beauty-api` 契约层、三层文档体系 |
| **Agent First 研发流程** | Agent 主导协作，基础设施原子化为 Tools | ✅ 流程闭环 | CO/PM/RD/CR/QA 五角色协作、Self-Heal 自愈闭环、7 个自动化脚本、文档-代码同步 |

### 已验证的假设（12 项，11 项已证实）

| # | 假设 | 结论 |
|---|------|------|
| 1 | 显式架构可被 Agent 高效理解 | ✅ — RD Agent 成功实现跨模块功能 |
| 2 | 文档驱动开发减少沟通损耗 | ✅ — PRODUCT→FEATURES→AGENTS 链条有效 |
| 3 | Tools 化支持 Self-Heal 闭环 | ✅ — 编译/安装/截屏/日志全自动化 |
| 4 | Capability 系统支持热插拔 | ✅ — 新增能力零侵入 Agent 核心 |
| 5 | 端侧 LLM 满足相机控制推理 | ✅ — Qwen3-1.7B 本地推理 < 500ms，通过自定义简洁 JSON 指令格式（method + params 平铺结构）实现可靠的命令解析与指令翻译；OpenAI tool_calls 和 LangChain ToolSpec 多层嵌套结构对移动端小模型过于臃肿 |
| 6 | 隐私分级守卫有效拦截敏感数据 | ✅ — RESTRICTED/SENSITIVE 指令本地执行，PUBLIC 指令可配置远程 |
| 7 | 文字交互模式稳定可用 | ✅ — 自然语言控制相机功能已稳定 |
| 8 | Agent 可主导跨模块架构重构 | ✅ — `:agent-core` 抽离、`:beauty-api` 契约提取、InsightFace→MNN/NCNN、GPUPixel 移除 |
| 9 | Agent 可维护文档-代码同步 | ✅ — 20+ 文件、43 处脱节修复，三层文档持续对齐 |
| 10 | 语音控制可行性 | 🔄 — 唤醒词 + 声控链路已跑通，精度优化中 |
| 11 | 云端推理结果缓存 | ✅ — IntentCache 机制被验证高效：一次远程推理成功后缓存结果，后续同类指令直接命中，跳过推理过程，大幅降低延迟与成本 |
| 12 | 多推理引擎资源隔离的挑战 | ❌ — MNN/NCNN/MediaPipe 多引擎共存时，底层 Vulkan/EGL 等 GPU 资源存在竞争（如 Vulkan Device Lost、EGL context 冲突），资源生命周期管理与线程隔离是显著调优成本 |

### 实践关键发现

PicMe 在深度实践中沉淀了以下关键认知，对端侧 Agent 架构设计有重要参考价值：

#### 指令格式：自定义优于通用规范

端侧小模型（Qwen3-1.7B/3.5-2B）对 OpenAI tool_calls 格式的 `function` 嵌套结构支持不稳定，超出其推理能力边界。实际采用 **自定义简洁 JSON 指令格式**（`method` + `params` 平铺结构）后，小模型指令生成的可靠性显著提升。LangChain 的 ToolSpec 机制虽然在大模型场景表现良好，但其多层嵌套结构对移动端小模型过于臃肿。

**结论**：端侧指令格式应追求极简平铺，避免通用规范的复杂嵌套。

#### GBNF 语法的有限约束力

GBNF grammar 对 JSON 格式生成有一定规范作用，但在实践中暴露了两个关键问题：

- **结构化指令生成**：无法完全保证输出质量，仍需以提示词工程为主力，GBNF 仅作为辅助约束
- **闲聊模式冲突**：GBNF 会严重限制模型表达能力（如移除 `\n` 的 grammar 导致模型无法换行），需根据当前模式（指令/闲聊）动态启用/关闭 grammar

**结论**：GBNF 适合格式约束，但绝非银弹；提示词工程仍是兜底主力；闲聊场景需绕过 GBNF。

#### 云端推理缓存：一次成功，永久受益

`IntentCache` 机制被验证为高效设计——云端 LLM 推理一次成功后，相同指令直接命中缓存返回，完全跳过后续推理过程。这不仅大幅降低端到端延迟，还显著节省 API 调用成本。当前命中率由自适应缓存策略持续优化，但方向已被验证正确。

**结论**：远程推理 + 端上缓存（Cache-aside Pattern）是端云混合架构的核心能力。

#### 多引擎资源隔离的挑战

同时集成 MNN、NCNN、MediaPipe 等多个人脸检测/推理引擎时，它们对 Vulkan、EGL/GL 等底层 GPU 资源的争夺是隐形的崩溃源。典型表现包括 `Vulkan Device Lost`、EGL context 冲突等。解决需要细致的资源生命周期管理、线程隔离以及引擎间显式上下文切换，调优成本显著高于单引擎方案。

**结论**：多引擎集成需在架构层面规划资源隔离（独立线程/上下文/生命周期），否则运行时崩溃将成为常态。

### 度量指标

| 指标 | 当前 | 目标 |
|------|------|------|
| Agent 生成代码占比 | ~65% | > 80% |
| Self-Heal 成功率 | ~75%（非编译期类型错误场景高） | > 85% |
| 文档-代码一致性 | ~95%（审计后） | > 98% |
| 人工介入频次 | ~15% | < 10% |
| 代码规模上限验证 | ~3 万行 Kotlin 稳定 | 探索 5~10 万行边界 |

### 待探索的问题

1. **规模上限**：Agent 能高效处理的代码库规模上限是多少？当前 ~3 万行 Kotlin 中表现稳定，上限待测
2. **复杂重构深度**：Agent 已能执行模块抽离、引擎迁移等结构化重构，但能否处理涉及编译期类型系统变更的深度重构（如 DSL 重设计、协议升级）？
3. **跨项目迁移**：PicMe 沉淀的 Agent First 模式（三层文档、显式架构、Tools 化）能否泛化到业务系统或其他领域？
4. **人机协作边界**：在架构决策、安全审计、产品方向等场景中，哪些决策必须保留人工介入？
5. **语音交互体验**：远场拾音、噪声抑制、方言适配能否达到产品级标准？当前 SherpaMnnAsr + VAD(40dB) 方案仍在打磨
6. **缓存策略优化**：IntentCache 已验证有效，但如何自适应预测缓存命中、动态设置缓存 TTL、处理部分匹配指令的模糊命中？
7. **GBNF 与提示词工程的边界**：什么场景下 grammar 约束优于提示词、什么场景相反？能否自动检测并切换策略？
8. **多引擎资源隔离标准化**：多个推理引擎（MNN/NCNN/MediaPipe/ONNX）共享 GPU 的设备上，如何形成可复用的资源隔离模式以消除运行时崩溃？
9. **多模态 Agent 交互**：文字 + 语音 + 视觉的组合输入能否统一为单一交互模型，让 Agent 在复杂场景（如"把这张照片调成和昨天那张一样的感觉"）中正确理解上下文？

---

## 🛠 自动化工具链

| 脚本 | 功能 | 触发者 |
|------|------|--------|
| [`auto-dev-loop.sh`](scripts/auto-dev-loop.sh) | 编译 → 安装 → 截屏 → 日志 → 报告 | RD |
| [`ai-gate.sh`](scripts/ai-gate.sh) | 代码质量门禁（lint + 编译 + 安装检查） | CI |
| [`impact-analyzer.sh`](scripts/impact-analyzer.sh) | 变更影响分析与文档同步提醒 | CO |
| [`doc-sync-guardian.sh`](scripts/doc-sync-guardian.sh) | 三层文档一致性检查 | CR |
| [`screenshot-diff.py`](scripts/screenshot-diff.py) | UI 回归像素级对比 | QA |

---

## 💬 联系作者

<p align="center">
  <img src="docs/assets/winxin.jpg" width="200" alt="微信二维码">
  <br>
  <i>扫码添加微信，交流端侧 AI 与 Agent First 研发实践</i>
</p>

---

## 📄 许可

MIT License — 研究、学习、二次开发均可自由使用。

---

<p align="center">
  <i>PicMe — 让相机听懂你说的话</i>
</p>
