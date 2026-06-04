<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/minSdk-24-3DDC84" alt="Min SDK">
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/OpenGL-ES%203.0-5586A4?logo=opengl&logoColor=white" alt="OpenGL ES">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
</p>

<h1 align="center">PicMe</h1>

<p align="center">
  <b>下一代端侧 AI 智能相机</b><br>
  <i>自然语言驱动 · 全链路 GPU 渲染 · 100% 隐私安全</i>
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

- **端侧运行 Qwen3-1.7B 大模型**，支持更强本地推理；隐私敏感数据优先本地处理
- **远程 LLM 编排**（可选）：默认 DeepSeek 模型有频次限制，配置个人 OpenAI/Claude 兼容 Token 可解锁无限云端推理；腾讯云赠送额度已验证可用
- **Capability 系统**支持热插拔扩展，新增能力无需修改 Agent 核心
- **多轮对话上下文记忆**，交互体验连贯自然
- **统一聊天界面**，Camera 与 Gallery 页面视觉一致，支持折叠/展开、语音输入

### 📷 自研实时美颜引擎

- **全自研 OpenGL ES + EGL 渲染管线**（无第三方美颜 SDK）
- 完整美颜链路：磨皮、美白、瘦脸、大眼、唇色、腮红、眉毛
- **GPU 离屏拍照**：预览与输出使用同一套 Shader，效果一致性 99%+
- **双引擎人脸检测**：InsightFace 2D106（主）+ MediaPipe 468→106（备）
- **帧同步妆容系统**：解决检测帧率与渲染帧率不匹配导致的"妆容甩飞"问题

### 🔒 隐私优先

| 功能 | 运行位置 | 网络依赖 |
|------|----------|----------|
| 本地 LLM 推理 | 本地 MNN-LLM | 可离线 |
| 人脸检测 | 本地 ONNX Runtime | 零云端推理 |
| OCR 文字识别 | 本地 ML Kit | 零云端推理 |
| 美颜渲染 | 本地 GPU | 零云端推理 |
| 远程复杂编排 | Kimi API（可选） | 仅非敏感指令 |

PicMe 对隐私敏感数据采用**强制本地处理**策略；在远程模式下，仅非敏感指令允许走网络编排。

### 💬 统一聊天界面（2026-05 新增）

- **跨模块统一设计**：Camera 与 Gallery 页面共享同一套 Chat UI 组件
- **ModalBottomSheet 设计**：底部弹出，半透明遮罩，系统自动处理键盘 insets
- **智能折叠/展开**：节省屏幕空间，拖拽把手直观操作
- **优雅动画过渡**：滑入滑出流畅自然，视觉体验一致
- **多模态输入**：支持文字输入与语音切换，按住说话便捷高效
- **丰富的消息类型**：用户消息、AI 回复、计划预览、执行进度、结果反馈
- **Material Design 3**：完整主题适配，深色模式完美支持

---

## 🏗 技术架构

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
│  ├─ MemoryManager          多轮对话上下文管理                  │
│  └─ PrivacyGuard           隐私分级守卫                        │
├──────────────────────────────────────────────────────────────┤
│  Capability Layer（可插拔、自描述）                            │
│  ├─ CameraCapability       相机控制（拍摄/切换/参数）          │
│  ├─ BeautyCapability       美颜参数调节                        │
│  ├─ GalleryCapability      相册管理                            │
│  ├─ SettingsCapability     设置管理                            │
│  └─ NavigationCapability   页面导航                            │
├──────────────────────────────────────────────────────────────┤
│  beauty-engine (独立模块 · OpenGL ES + EGL)                   │
│  ├─ CameraPreviewRenderer  实时预览渲染                        │
│  ├─ BeautyRenderer         美颜 Shader 多 Pass 管线            │
│  ├─ PhotoProcessorImpl     GPU 离屏拍照处理                    │
│  ├─ FrameSyncManager       帧同步妆容系统                      │
│  └─ FaceDetectionEngine    InsightFace + MediaPipe 双引擎      │
└──────────────────────────────────────────────────────────────┘
```

### 架构亮点

- **显式优于隐式**：构造函数即文档，依赖关系一目了然
- **声明式状态空间**：Sealed Class 枚举所有合法状态，消除隐式组合
- **自描述 Capability**：Capability 自包含元数据，Agent 可反射发现与调用
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

PicMe 不仅是一款应用，更是对「Agent 能否主导软件研发全流程」的系统性验证。

### 三重实验维度

| 维度 | 目标 | 关键产出 |
|------|------|----------|
| **端侧 Agent 架构** | 验证 LLM 能否成为应用的中枢神经系统 | Agent Runtime、Capability 系统、对话式交互 |
| **Agent First 客户端框架** | 让 Agent 高效理解、修改、扩展代码 | 显式边界、声明式状态、自描述能力 |
| **Agent First 研发流程** | Agent 主导协作，基础设施原子化为 Tools | 角色化协作、Self-Heal、即时验证 |

### 已验证的假设

| 假设 | 结论 |
|------|------|
| 显式架构可被 Agent 高效理解 | ✅ 成立 — RD Agent 成功实现跨模块功能 |
| 文档驱动开发减少沟通损耗 | ✅ 成立 — PRODUCT→FEATURES→AGENTS 链条有效 |
| Tools 化支持 Self-Heal 闭环 | ✅ 成立 — 编译/安装/验证全自动化 |
| Capability 系统支持热插拔 | ✅ 成立 — 新增能力零侵入 Agent 核心 |

### 待探索的问题

1. **规模上限**：Agent 能高效处理的代码库规模上限是多少？
2. **复杂重构**：Agent 能否主导跨模块架构级重构？
3. **跨项目迁移**：Learned patterns 能否泛化到其他项目？
4. **人机协作边界**：哪些决策必须保留人工介入？

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

## 📄 许可

MIT License — 研究、学习、二次开发均可自由使用。

---

<p align="center">
  <i>PicMe — 让相机听懂你说的话</i>
</p>
