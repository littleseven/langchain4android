# 觅影相机 PicMe — Wiki 文档中心

**下一代端侧 AI 智能相机 · Agent First 工程试验场**

PicMe 是一款探索「端侧 AI Agent 驱动应用」的智能相机。用户通过**自然语言**控制相机——说「调高美颜」「换个冷调滤镜」「拍一张」即可。项目同时验证 Agent First 研发范式。

---

## 🏗 项目模块

| 模块 | 说明 |
|------|------|
| `:app` | 主应用模块（UI + DI + Data + Features） |
| `:agent-core` | Agent Runtime 核心（编排、推理、语音、远程） |
| `:beauty-api` | 美颜接口契约层（纯 Kotlin） |
| `:beauty-engine` | 美颜引擎（OpenGL ES + EGL 渲染管线） |

## 📖 文档快速导航

| 我想... | 看这里 |
|---------|--------|
| 了解 PicMe 是什么 | [项目 README](../README.md) |
| 理解产品定位和验收标准 | [PRODUCT.md](../../PRODUCT.md) |
| 了解 Agent First 研发范式 | [AGENTS.md](../../AGENTS.md) |
| 全局文档索引 | [00-INDEX.md](../00-INDEX.md) |
| 理解 Agent 运行时架构 | [Agent 架构设计](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) |
| 了解美颜引擎技术细节 | [美颜引擎规格](../03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) |
| 了解帧同步系统 | [帧同步技术规格](../03-TECHNICAL-SPECS/FRAME_SYNC_TECH_SPEC.md) |
| 实现新的 Agent 能力 | [Capability 实现指南](../04-AGENT-CAPABILITIES/CAPABILITY_IMPLEMENTATION_GUIDE.md) |
| 执行 QA 验收 | [QA 验收清单](../06-QA/QA_EXECUTION_CHECKLIST.md) |

## 🔑 核心概念

- **Agent Runtime** — `:agent-core` 模块，LLM 作为应用中杻神经系统
- **Capability 系统** — 可插拔、自描述的设备能力热注册机制
- **Beauty Engine** — 全自研 OpenGL ES + EGL 渲染管线（零第三方 SDK）
- **帧同步妆容** — 解决人脸检测帧率与渲染帧率不匹配的"妆容甩飞"问题
- **PrivacyGuard** — PUBLIC / SENSITIVE / RESTRICTED 三级隐私自动分级

## 📊 当前进展（2026-06）

| 领域 | 状态 |
|------|------|
| 端侧 LLM 推理（Qwen3-1.7B） | ✅ 已落地 |
| Agent 运行时模块化（:agent-core） | ✅ 已落地 |
| beauty-api 契约层提取 | ✅ 已落地 |
| 人脸检测（MediaPipe + MNN/NCNN） | ✅ 已落地 |
| 帧同步妆容系统 | ✅ 已落地 |
| GPU 离屏拍照 | ✅ 已落地 |
| 远程 LLM 编排（可选） | ✅ 已落地 |
| Capability 系统（5 个 Capability） | ✅ 已落地 |
| 语音控制（试验性） | 🔄 迭代中 |

---

> 📝 完整文档索引见 [00-INDEX.md](../00-INDEX.md) · 反馈请提 [GitHub Issue](https://github.com/littleseven/PicMe/issues)
# 觅影相机 PicMe — Wiki 文档中心

**下一代端侧 AI 智能相机 · Agent First 工程试验场**

PicMe 是一款探索「端侧 AI Agent 驱动应用」的智能相机。用户通过**自然语言**控制相机——说「调高美颜」「换个冷调滤镜」「拍一张」即可。项目同时验证 Agent First 研发范式。

---

## 🏗 项目模块

| 模块 | 说明 |
|------|------|
| `:app` | 主应用模块（UI + DI + Data + Features） |
| `:agent-core` | Agent Runtime 核心（编排、推理、语音、远程） |
| `:beauty-api` | 美颜接口契约层（纯 Kotlin） |
| `:beauty-engine` | 美颜引擎（OpenGL ES + EGL 渲染管线） |

## 📖 文档快速导航

| 我想... | 看这里 |
|---------|--------|
| 了解 PicMe 是什么 | [项目 README](../README.md) |
| 理解产品定位和验收标准 | [PRODUCT.md](../../PRODUCT.md) |
| 了解 Agent First 研发范式 | [AGENTS.md](../../AGENTS.md) |
| 全局文档索引 | [00-INDEX.md](../00-INDEX.md) |
| 理解 Agent 运行时架构 | [Agent 架构设计](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) |
| 了解美颜引擎技术细节 | [美颜引擎规格](../03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) |
| 了解帧同步系统 | [帧同步技术规格](../03-TECHNICAL-SPECS/FRAME_SYNC_TECH_SPEC.md) |
| 实现新的 Agent 能力 | [Capability 实现指南](../04-AGENT-CAPABILITIES/CAPABILITY_IMPLEMENTATION_GUIDE.md) |
| 执行 QA 验收 | [QA 验收清单](../06-QA/QA_EXECUTION_CHECKLIST.md) |

## 🔑 核心概念

- **Agent Runtime** — `:agent-core` 模块，LLM 作为应用中杻神经系统
- **Capability 系统** — 可插拔、自描述的设备能力热注册机制
- **Beauty Engine** — 全自研 OpenGL ES + EGL 渲染管线（零第三方 SDK）
- **帧同步妆容** — 解决人脸检测帧率与渲染帧率不匹配的"妆容甩飞"问题
- **PrivacyGuard** — PUBLIC / SENSITIVE / RESTRICTED 三级隐私自动分级

## 📊 当前进展（2026-06）

| 领域 | 状态 |
|------|------|
| 端侧 LLM 推理（Qwen3-1.7B） | ✅ 已落地 |
| Agent 运行时模块化（:agent-core） | ✅ 已落地 |
| beauty-api 契约层提取 | ✅ 已落地 |
| 人脸检测（MediaPipe + MNN/NCNN） | ✅ 已落地 |
| 帧同步妆容系统 | ✅ 已落地 |
| GPU 离屏拍照 | ✅ 已落地 |
| 远程 LLM 编排（可选） | ✅ 已落地 |
| Capability 系统（5 个 Capability） | ✅ 已落地 |
| 语音控制（试验性） | 🔄 迭代中 |
| 引导滤波（磨皮 Phase 2） | ⏳ 规划中 |

---

> 📝 完整文档索引见 [00-INDEX.md](../00-INDEX.md) · 反馈请提 [GitHub Issue](https://github.com/littleseven/PicMe/issues)

