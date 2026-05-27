# PicMe

> **一个以 AI Coding 范式与音视频技术为探索目标的技术研究项目，相机产品是承载这一探索的具体 Case。**

PicMe 并非商业化产品，而是围绕两条技术主线展开的工程实验场：

- **AI Coding 范式**：探索端侧 Agent 机制和以 Agent 为中心的应用架构，验证"自然语言驱动设备能力"的可行性
- **音视频技术**：自研实时美颜引擎（大美丽 / BIG_BEAUTY），深耕 OpenGL ES + EGL 渲染管线、人脸检测多引擎、帧同步美妆等技术攻坚点

---

## 双轨探索模型

```
┌─────────────────────────────────────────────────────────┐
│                      PicMe 技术探索                       │
├─────────────────────────┬───────────────────────────────┤
│  App 模块               │  beauty-engine 模块             │
│  Agent 中心架构          │  音视频 + 美颜技术              │
├─────────────────────────┼───────────────────────────────┤
│  • 端侧 Agent Runtime    │  • OpenGL ES/EGL 渲染管线      │
│  • Qwen3-0.6B/MNN-LLM   │  • 多 Pass Shader 美颜         │
│  • 自然语言→设备能力     │  • 多引擎人脸检测（ONNX/NCNN/   │
│  • Agent-centric UI      │    MediaPipe/MNN）              │
│  • Capability 可插拔     │  • 帧同步美妆防甩飞             │
│  • 对话记忆与隐私守卫     │  • GPU 离屏拍照一致性          │
│  • 开发流程 AI Agent 化  │  • 风格特效/色调滤镜            │
└─────────────────────────┴───────────────────────────────┘
```

**核心理念**：不追求商业化收入，专注于 AI Coding 范式演进和音视频技术深度。相机美颜是这两个方向交汇的具体 Case——用自然语言控制美颜相机的同时，在底层验证实时渲染的极致性能。

---

## 核心红线

- **[PRIVACY]** 所有 AI 处理（LLM 推理、人脸检测、OCR）100% 端侧运行，零云端推理。使用 MNN-LLM 在设备本地运行 Qwen3-0.6B 等模型。
- **[PERF]** 交互反馈 < 100ms，拍摄快门延迟 < 50ms，预览帧率目标 60fps。
- **[I18N]** 禁止硬编码用户可见文案，必须同步 `values` / `values-zh-rCN` / `values-zh-rTW`。

---

## 架构概览

```
App Layer (Compose UI + ViewModel)
    ├─ domain/agent/              ← Agent Runtime
    │   ├─ AgentOrchestrator      自然语言→结构化命令
    │   ├─ LocalLlmEngine         MNN-LLM 本地推理
    │   ├─ CapabilityRegistry     设备能力注册/路由
    │   ├─ MemoryManager          对话记忆管理
    │   └─ PrivacyGuard           隐私分级守卫
    ├─ data/download/             ← LLM 模型下载管理
    │   └─ LlmModelDownloadManager  ModelScope 断点续传 + SHA256 校验
    ├─ domain/usecase/            ← AiAgentUseCase（Facade）
    └─ features/camera/           ← Agent 交互面板
        ↓ 仅允许依赖 beauty-engine:api
beauty-engine:api          ← 稳定 API 契约（BeautySettings / Face / FilterType / PhotoProcessor）
    ↑ 由 render/ 实现
beauty-engine:render       ← 内部 OpenGL ES + EGL 渲染管线
    ├─ CameraPreviewRenderer   预览渲染核心
    ├─ BeautyRenderer          美颜 Shader 多 Pass 管线
    ├─ PhotoProcessorImpl      拍照 GPU 离屏渲染
    ├─ EGLCore                 EGL 上下文与 Surface 管理
    ├─ internal/facedetect/    人脸检测多引擎（ONNX/NCNN/MNN/MediaPipe）
    └─ internal/framesync/    帧同步系统（FrameSyncManager / MotionTracker）
```

**依赖红线**：App 代码禁止直接引用 `beauty-engine:render/` 内部类，必须通过 `api/` 接口访问。

---

## Agent 系统（双重含义）

PicMe 中 "Agent" 有两层含义，体现了从开发工具到运行时架构的全链路 Agent 化探索：

### 1. 应用内 AI Agent（运行时）

位于 `domain/agent/`，实现**自然语言控制设备能力**的端侧 Agent 机制：

```
用户输入 "拉高美颜" / "换个冷调滤镜"
    → LocalLlmEngine（Qwen3-0.6B via MNN-LLM）
    → AgentCommandParser（LLM 响应 → 结构化命令）
    → CapabilityRegistry（路由到具体能力）
    → CameraCapability（执行设备操作）
```

**支持的设备命令**：美颜参数调节、滤镜/风格切换、场景切换、画面比例、曝光/变焦、翻转镜头、拍照、文字对话等。

**关键特性**：
- **100% 端侧推理**：Qwen3-0.6B 通过 MNN-LLM 在设备本地运行，无需网络
- **Capability 可插拔**：通过 `Capability` 接口注册，新能力只需实现接口即可接入
- **隐私分级守卫**：`PrivacyGuard` 根据操作敏感度分级控制
- **对话记忆**：`MemoryManager` 维护上下文，支持多轮对话

### 2. 开发流程 AI Agent（协作）

位于 `agents/` 目录，定义了一套 **AI 协作开发角色体系**（CO → PM → RD → CR → QA），将 SDLC 全流程 Agent 化。详见 `agents/README.md`。

---

## 端侧模型生态

| 技术 | 用途 | 推理引擎 |
|------|------|----------|
| Qwen3-0.6B | AI Agent 大脑，自然语言→设备命令 | MNN-LLM |
| Qwen3.5-2B | 可选更大模型 | MNN-LLM |
| InsightFace 2D106 | 默认人脸检测（106 点关键点） | ONNX Runtime（NNAPI 加速） |
| MediaPipe 468→106 | 备选人脸检测 | TFLite GPU |
| NCNN 人脸检测 | 备选人脸检测 | NCNN（Vulkan） |
| MNN 人脸检测 | 备选人脸检测 | MNN（Vulkan） |

---

## 项目结构

```
PicMe/
├── app/                               # 主应用模块
│   └── src/main/java/com/picme/
│       ├── domain/
│       │   ├── agent/                 # Agent Runtime（AgentOrchestrator / LocalLlmEngine）
│       │   ├── model/                 # 领域模型（AiAgentCommand 等）
│       │   └── usecase/               # 用例（AiAgentUseCase 等）
│       ├── data/
│       │   └── download/              # LLM 模型下载管理
│       ├── features/
│       │   ├── camera/                # 相机预览与拍摄（含 Agent 交互面板）
│       │   ├── gallery/               # 相册浏览与管理
│       │   ├── editor/                # 照片编辑
│       │   ├── settings/              # 应用设置
│       │   └── debug/                 # 调试工具面板
│       ├── core/                      # 通用组件（designsystem / image / common）
│       ├── di/                        # 依赖装配与运行时状态
│       └── navigation/                # 页面路由
├── beauty-engine/                     # 实时美颜引擎独立库
│   └── src/main/java/com/picme/beauty/
│       ├── api/                       # 对外稳定 API 契约
│       ├── render/                    # 内部 OpenGL ES + EGL 渲染管线
│       └── internal/                  # 人脸检测 / 帧同步 / LLM 模型管理
├── agents/                            # AI 协作角色定义（CO/PM/RD/CR/QA）
├── scripts/                           # 自动化工具链（编译/安装/测试/截屏）
└── docs/                              # 技术规范与架构决策文档
```

---

## 当前技术状态

| 维度 | 现状 |
|------|------|
| **Agent Runtime** | ✅ Qwen3-0.6B via MNN-LLM，支持自然语言控制相机能力 |
| **渲染引擎** | 大美丽（BIG_BEAUTY）单引擎，自研 OpenGL ES + EGL；GPUPixel 已完全移除 |
| **人脸检测** | 多引擎可插拔：ONNX（NNAPI）/ MediaPipe（TFLite GPU）/ NCNN（Vulkan）/ MNN（Vulkan），统一 106 点 |
| **拍照处理** | GPU 离屏渲染（PhotoProcessorImpl），复用预览 Shader，CPU Canvas 为 fallback |
| **帧同步美妆** | 🔄 框架已落地（FrameSyncManager / MotionTracker / DetectionQueue），预测补偿待收尾 |
| **Beauty 能力** | ✅ 磨皮/美白/瘦脸/大眼/唇色/腮红/调色/滤镜/风格特效 全部落地 |

---

## 快速开始

```bash
# 构建 Debug APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# JVM 单元测试（无需设备）
./gradlew test

# 查看 PicMe 日志
adb logcat -s "PicMe:*"

# 自动化开发闭环（编译→安装→启动→截屏→日志）
./scripts/auto-dev-loop.sh
```

完整开发指南见 [`DEVELOPMENT.md`](DEVELOPMENT.md)。

---

## 文档体系

| 层级 | 文档 | 职责 |
|------|------|------|
| What | [`PRODUCT.md`](PRODUCT.md) | 产品目标、用户画像 |
| How | [`docs/FEATURES.md`](docs/FEATURES.md) | 交互流程、体验规则 |
| Implementation | 模块 `AGENTS.md` | 代码规范、检查清单 |

### 技术专项文档

| 文档 | 说明 |
|------|------|
| [`docs/BIG_BEAUTY_TECH_SPEC.md`](docs/BIG_BEAUTY_TECH_SPEC.md) | 大美丽渲染链路、容灾回退 |
| [`docs/CAMERA_PREVIEW_TECH_SPEC.md`](docs/CAMERA_PREVIEW_TECH_SPEC.md) | 相机预览技术规范 |
| [`docs/FACE_DETECTION_ENGINE_ARCHITECTURE.md`](docs/FACE_DETECTION_ENGINE_ARCHITECTURE.md) | 人脸检测多引擎架构 |
| [`docs/PRD-FRAME-SYNC-MAKEUP.md`](docs/PRD-FRAME-SYNC-MAKEUP.md) | 帧同步美妆 PRD |
| [`docs/BEAUTY_ENGINE_FALLBACK.md`](docs/BEAUTY_ENGINE_FALLBACK.md) | 容灾降级说明 |

### 架构决策记录（ADR）

| ADR | 决策 |
|-----|------|
| [ADR-001](docs/ADR-001-beauty-engine-architecture.md) | 大美丽单引擎分层架构 |
| [ADR-002](docs/ADR-002-opengl-offscreen-unified-pipeline.md) | OpenGL 离屏渲染统一拍照管线 |
| [ADR-003](docs/ADR-003-coordinate-system-management.md) | 坐标系统一管理 |

---

## 致谢

本项目在开发过程中使用了以下公众人物的照片进行功能测试与技术验证：刘亦菲、迪丽热巴、杨颖（Angelababy）。这些照片仅用于内部技术测试与算法验证。

- 所有测试图片均来自互联网公开资源，版权归原权利人所有
- 本项目为技术研究项目，不涉及任何商业用途
- 如上述照片的使用侵犯了您的合法权益，请通过 GitHub Issues 提交侵权通知，我们将立即移除
