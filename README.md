# PicMe

> **AI Agent 试验场** —— 以自然语言交互为核心的端侧智能相机

PicMe 是一个技术探索实验，验证「端侧 AI Agent 驱动应用」的工程可行性。美颜相机只是试验场景：它提供了丰富的实时计算需求和直观的用户价值验证路径。

**核心命题**：当 LLM 成为应用的中枢神经系统，传统的 GUI 架构将如何演进？

---

## 核心特性

### 🤖 Agent 交互
- 自然语言控制相机：「调高美颜」「换个冷调滤镜」「拍一张」
- 端侧 Qwen3-0.6B 推理，零网络依赖
- 对话记忆与上下文感知

### 📷 实时美颜
- 自研 OpenGL ES 渲染管线
- 磨皮、美白、瘦脸、大眼、唇色、腮红
- GPU 离屏拍照，预览/输出一致性

### 🔒 100% 端侧
- LLM、人脸检测、OCR 全部本地运行
- 零云端，零网络权限
- 极致隐私保护

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
│  ├─ CapabilityRegistry     设备能力路由                   │
│  ├─ MemoryManager          对话上下文                     │
│  └─ PrivacyGuard           隐私分级守卫                   │
├─────────────────────────────────────────────────────────┤
│  Capability Layer                                         │
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

# 查看日志
adb logcat -s "PicMe:*"

# 自动化开发闭环（编译→安装→启动→截屏→日志）
./scripts/auto-dev-loop.sh
```

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 界面 | Jetpack Compose |
| Agent | Qwen3-0.6B / MNN-LLM |
| 渲染 | OpenGL ES 3.0 / EGL |
| 人脸检测 | InsightFace(ONNX) / MediaPipe / NCNN / MNN |
| 架构 | Clean Architecture + Agent-centric |

---

## 项目定位

**PicMe 不是商业化产品**，而是一个回答以下问题的实验：

1. 端侧 LLM 能否支撑实时交互应用？
2. Agent 架构与传统 MVVM/MVI 如何共存？
3. 自然语言交互能否替代复杂 GUI？
4. 端侧 AI 的隐私边界在哪里？

**明确不做**：云端服务、社交功能、商业变现、跨平台、通用 Agent。

---

## 文档

| 文档 | 内容 |
|------|------|
| [`PRODUCT.md`](PRODUCT.md) | 产品定义、核心命题、验收标准 |
| [`docs/FEATURES.md`](docs/FEATURES.md) | 功能交互细节 |
| [`docs/AGENT_ARCHITECTURE_MEMO.md`](docs/AGENT_ARCHITECTURE_MEMO.md) | Agent 架构设计 |
| [`agents/README.md`](agents/README.md) | AI 协作开发角色定义 |

---

## 许可

MIT License — 仅用于研究与学习目的。

测试图片中的公众人物（刘亦菲、迪丽热巴、杨颖）仅用于内部技术验证，如有侵权请联系移除。
