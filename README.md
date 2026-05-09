# PicMe

PicMe 是一个面向长期演进的智能相机项目，核心强调三件事：
- AI 协作研发范式落地（Agent Team + Spec）
- 商业级隐私、性能与稳定性
- 大美丽 视觉能力库化（美颜 / 滤镜 / 妆容）

---

## 架构决策

| ADR | 决策 | 状态 | 说明 |
|-----|------|------|------|
| [ADR-001](docs/ADR-001-beauty-engine-architecture.md) | 大美丽单引擎架构 | ✅ 已接受 | `beauty-engine:api` / `egl` 两层拆分，App 仅依赖 api 契约；GPUPixel 已移除 |
| [ADR-002](docs/ADR-002-opengl-offscreen-unified-pipeline.md) | OpenGL 离屏渲染统一管线 | ✅ 已接受 | `OffscreenRenderer` 已落地于 `beauty-engine/egl`，预览/拍照使用同一套 Shader |

### 大美丽单引擎架构

```
┌─────────────────────────────────────────────────────────┐
│                    App Layer (PicMe)                    │
│              ↓ 依赖 beauty-engine:api                   │
└────────────────────┬──────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────┐
│  API Layer: beauty-engine:api                           │
│  ├─ BeautySettings / BeautyParams / FilterType        │
│  ├─ Face / BeautyProcessor                            │
│  ├─ BeautyPreviewEngine (Interface)                   │
│  └─ BeautyCallback                                    │
└────────────────────┬──────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────┐
│  Impl Layer: beauty-engine:egl                          │
│  ├─ BeautyRenderer (OpenGL ES 多 Pass 管线)          │
│  ├─ CameraPreviewRenderer                             │
│  ├─ StyleEffectShader                                 │
│  └─ EGLContextManager                                 │
└─────────────────────────────────────────────────────────┘
```

**依赖规则**：
- App 层**只能**依赖 `beauty-engine:api`
- `beauty-engine:egl/` 为 internal，不对外暴露
- 所有 GPU/EGL 操作封装在 `beauty-engine:egl` 内部

---

## 项目三大目标

1. **探索 AI Coding 范式并落地到相机研发**
   - Agent Team（CO / PM / RD / CR / QA）已在同一会话内串行协作，完成从需求到测试的全链路交付
   - Spec 驱动开发已落地：`PRODUCT.md` → `docs/FEATURES.md` → 模块 `AGENTS.md` 三层文档体系持续同步
   - AI 工具链统一：kimi-cli / Lingma / OpenClaw 三端配置兼容，Skills 统一来源（`.lingma/skills/`），通过符号链接同步到 `.openclaw/skills/` 与 `.kimi/skills/`
   - 单元测试由 Agent 自主编写并维护（纯 JVM 测试，覆盖坐标算法、状态机、端到端流程等），Bug 复现与修复过程可追溯
   - 沉淀中的 AI 协作方法论：自愈闭环（最多 2 次）、原子化修改、红线暂停机制

2. **打造可达商业级水平的相机应用**
   - 隐私优先：所有 AI 处理（人脸、OCR、分类）100% 本地化，严禁云端推理
   - 性能稳定：首帧、帧率、交互延迟、容灾回退可观测可验证
   - 可交付：通过自动化测试与质量门禁保障版本可靠性

3. **沉淀优秀的视觉能力基础库**
   - 将 大美丽 自研能力（美颜 / 滤镜 / 妆容）从 App 业务逐步解耦
   - 长期演进为独立基础库（核心自研可控，无商业 SDK 依赖）
   - 提供稳定 API 与版本治理，支持跨业务复用

---

## 当前技术路线（2026-05）

- **单引擎架构**：仅保留 `BIG_BEAUTY`（自研 OpenGL ES + EGL 管线），GPUPixel 已完全移除
- **人脸检测双引擎**：支持 InsightFace 2D106（默认首选，ONNX Runtime + NNAPI GPU 加速）和 MediaPipe Face Mesh 468→106 映射（备选）
- **容灾机制**：引擎失败展示无美颜原生预览，冷却窗口后自动重试；InsightFace 漏检时可回退到 MediaPipe
- **观测能力**：关键指标统一输出（FPS、处理耗时、延迟、CPU、空帧）
- **拍照后处理**：GPU 离屏渲染（`PhotoProcessorImpl`，复用预览同一套 Shader 管线）已落地为标准路径，CPU Canvas 路径降级为 Fallback；RenderScript 已废弃
- **风格特效**：自研 Shader 支持卡通、素描、浮雕、色块化、交叉线等 5 种风格特效实时切换
- **长期方向**：持续演进 `:beauty-engine` 模块，实现能力库化
- **AI 工具链统一**：kimi-cli / Lingma / OpenClaw 三端配置已完成兼容，Skills 统一来源（`.lingma/skills/`），通过符号链接同步到 `.openclaw/skills/` 与 `.kimi/skills/`

---

## 项目结构（高层）

```
PicMe/
├── app/                             # 主应用模块
│   └── src/main/java/com/picme/
│       ├── domain/                  # 领域模型、用例、仓储接口（纯 Kotlin，无 Android 依赖）
│       ├── data/                    # 数据源、仓储实现、偏好存储、Room 数据库
│       ├── features/                # 业务页面与交互编排（Compose）
│       │   ├── camera/              # 相机预览与拍摄
│       │   │   └── preview/gl/      # 大美丽预览策略（BIG_BEAUTY 路径）
│       │   ├── gallery/             # 相册浏览与管理
│       │   ├── editor/              # 照片编辑（涂鸦 / 马赛克）
│       │   ├── settings/            # 应用设置
│       │   └── debug/               # 调试工具面板
│       ├── core/
│       │   ├── image/               # 拍照后处理 CPU Fallback（GPU 路径失败时降级）
│       │   ├── designsystem/        # 通用 UI 组件（HyperOS 风格）
│       │   └── common/              # Logger、扩展函数等基础工具
│       ├── di/                      # 依赖装配与运行时状态（手动 DI + BeautyEngineRuntimeState）
│       └── navigation/              # 页面路由
├── beauty-engine/                   # 实时美颜引擎独立库（OpenGL ES 单引擎）
│   └── src/main/java/com/picme/beauty/
│       ├── api/                     # 对外稳定 API 契约（BeautySettings/FilterType/StyleFilter/Face/BeautyProcessor）
│       └── egl/                     # 自研 OpenGL ES + EGL 渲染管线（禁止外部直接依赖）
├── data/                            # 本地测试数据与配置
└── docs/                            # 产品、交互、技术与测试规范文档
```

**架构依赖方向（Clean Architecture）**：
```
features → domain usecase → domain repository → data impl
features → beauty-engine api/（禁止直接引用 egl/ 内部类）
core/image/ → beauty-engine api/（拍照后处理，与实时预览隔离）
```

---

## 快速开始

### 环境要求

- Android Studio（建议最新稳定版）
- JDK 17
- Android SDK（`compileSdk 36`）


### AI 开发入口

本项目支持多款 AI 辅助工具协同开发：

| 工具 | 配置位置 | 启动方式 |
|------|----------|----------|
| **kimi-cli** | `.kimi/AGENTS.md` | `cd ~/AndroidStudioProjects/PicMe && kimi-cli chat` |
| **Lingma** | `.lingma/skills/` | IDE 内 `Cmd+Shift+L`（macOS） |
| **OpenClaw** | `.openclaw/workspace/` | 终端 `kimi-cli chat` |

> 工具配置索引与兼容性说明见 [`AI_TOOLS.md`](AI_TOOLS.md)。

### 常用命令

完整命令列表见 [`DEVELOPMENT.md`](DEVELOPMENT.md)。

```bash
# 编译 Debug 包
./gradlew assembleDebug

# JVM 单元测试（无需设备）
./gradlew :app:testDebugUnitTest

# 仪器测试（需连接设备或模拟器）
./gradlew :app:connectedDebugAndroidTest
```

---

## 核心能力一览（2026-05）

### 实时美颜（大美丽单引擎）

| 能力 | BIG_BEAUTY（自研） |
|---|---|
| 磨皮 | ✅ 双边滤波（9pt 快速近似） |
| 美白 | ✅ YUV 亮度调整 |
| 瘦脸 | ✅ FaceWarp 网格变形 |
| 大眼 | ✅ 径向放大变换 |
| 唇色 | ✅ HSV 色相调整 + 纹理妆容 |
| 腮红 | ✅ 双颊椭圆染色 |
| 专业调色 | ✅ 曝光/对比度/饱和度/色温/色调/亮度/RGB 通道 |
| 风格特效 | ✅ 卡通/素描/浮雕/色块化/交叉线 |
| 色调滤镜 | ✅ ColorMatrix（OpenGL Shader） |

### 人脸检测引擎（2026-05）

- **InsightFace 2D106（默认首选）**：本地 ONNX Runtime 推理，启用 NNAPI GPU/NPU 硬件加速，两阶段检测（Det10G ROI → 2D106 关键点），预期性能提升 3-5x
- **MediaPipe Face Mesh 468→106（备选）**：异步分析流，通过精确的 468→106 点语义映射支撑精细美型与妆容贴合
- **Auto 模式**：优先使用 InsightFace，主链路漏检或初始化失败时自动回退到 MediaPipe，保障可用性

### 滤镜系统

- **色调滤镜**：徕卡经典、徕卡生动、徕卡黑白、胶片金、富士胶片、复古、冷色、暖色 —— 通过 `ColorMatrix` 实时 Shader 变换
- **风格特效**：卡通、素描、色块化、浮雕、交叉线 —— 自研 Shader 实时渲染

### 拍照后处理

- **标准路径（GPU）**：`PhotoProcessorImpl` 通过 OpenGL ES 离屏渲染处理静态 Bitmap，复用预览同一套多 Pass Shader 管线（磨皮/美白/妆容/美型/调色/风格特效），效果与预览完全一致。详见 `docs/ADR-002-opengl-offscreen-unified-pipeline.md`
- **降级路径（CPU）**：GPU 路径失败时回退到 `GpuBeautyProcessor`（位于 `core/image/`），使用 Canvas + ColorMatrix 亮度近似（原 RenderScript 已废弃）
- 色调滤镜：`FilterType.toAndroidColorMatrix()` 在 CPU 路径下应用于保存前的 Bitmap
- 人脸数据：`Face` / `FaceContour` / `FaceLandmark` 统一由 `beauty-engine:api` 提供

---

## 质量与协作

### 文档索引

| 文档 | 说明 |
|---|---|
| `PRODUCT.md` | 产品需求规格说明书（SSOT，What） |
| `docs/FEATURES.md` | 功能交互规范（How） |
| `AGENTS.md` | Agent 协作治理规范（顶层） |
| `AI_TOOLS.md` | AI 工具配置索引（kimi-cli / Lingma / OpenClaw） |
| `DEVELOPMENT.md` | 通用开发指南（构建、调试、IDE、发布） |
| `docs/BIG_BEAUTY_TECH_SPEC.md` | 大美丽渲染链路、容灾、冷却恢复 |
| `docs/CAMERA_PREVIEW_TECH_SPEC.md` | 相机预览技术规范 |
| `docs/BEAUTY_ENGINE_FALLBACK.md` | 容灾降级统一说明 |
| `docs/FACE_DETECTION_ENGINE_ARCHITECTURE.md` | 人脸检测双引擎架构（InsightFace + MediaPipe） |
| `docs/BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md` | 大美丽 QA 执行清单 |
| `docs/ADR-001-beauty-engine-architecture.md` | ADR-001: 大美丽单引擎架构（GPUPixel 已移除） |
| `beauty-engine/AGENTS.md` | beauty-engine 模块实现规范 |

### 各模块 AGENTS.md

- `app/src/main/java/com/picme/core/AGENTS.md`
- `app/src/main/java/com/picme/core/designsystem/AGENTS.md`
- `app/src/main/java/com/picme/data/AGENTS.md`
- `app/src/main/java/com/picme/di/AGENTS.md`
- `app/src/main/java/com/picme/features/camera/AGENTS.md`
- `app/src/main/java/com/picme/features/gallery/AGENTS.md`
- `app/src/main/java/com/picme/features/editor/AGENTS.md`
- `app/src/main/java/com/picme/features/settings/AGENTS.md`
- `app/src/main/java/com/picme/features/debug/AGENTS.md`
- `beauty-engine/AGENTS.md`

---

## 目标驱动重构计划

### Phase 1（近期，2~4 周）：建立质量与协作底座

- 目标映射：
  - 目标 1（AI Coding）：将 Agent Team 执行链路固化到需求、实现、评审、测试
  - 目标 2（商业级应用）：补齐 P0 自动化真实断言，确保关键链路可回归
- 关键动作：
  - 将 `docs/BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md` 的 P0 用例升级为真实断言（非 skeleton）
  - 建立基础门禁：`testDebugUnitTest` + `compileDebugAndroidTestKotlin` + 设备环境下 `connectedDebugAndroidTest`
  - 统一 CR 阻断规则：架构越层、I18N 漏同步、关键回归失败即阻断

### Phase 2（中期，4~8 周）：按 Clean Architecture 收敛边界

- 目标映射：
  - 目标 1（AI Coding）：Spec 驱动拆解按模块推进，Agent 并行执行
  - 目标 2（商业级应用）：降低改动回归风险，提升可维护性与可测试性
- 关键动作：
  - 先改 `settings`，再改 `gallery`，最后改 `camera`（风险从低到高）
  - 收敛依赖方向：`features → domain usecase → domain repository → data impl`
  - 移除 domain 对 `android.*` / `features.*` 的依赖污染

### Phase 3（长期，8~16 周）：大美丽 能力库化

- 目标映射：
  - 目标 3（基础库沉淀）：将美颜/滤镜/妆容能力演进为独立视觉能力库
  - 目标 2（商业级应用）：持续提升大美丽自研引擎效果质量与稳定性
- 关键动作：
  - 抽离 `beauty-core`（纯 Kotlin）：策略模型、参数协议、回退/恢复状态机
  - 持续迭代 `:beauty-engine` 模块：渲染与平台适配（Surface/CameraX/OpenGL）
  - 定义稳定 API 与语义版本，App 仅依赖能力接口

### 里程碑验收（跨阶段）

- M1：P0 自动化真实断言通过率 100%，关键链路可无人值守回归
- M2：核心模块完成依赖收敛，domain 层无平台/feature 污染
- M3：大美丽 形成可独立发布的能力模块，App 侧完成接口化接入

---

## 致谢与声明

本项目在开发过程中使用了以下公众人物的照片进行功能测试与技术验证：

- **刘亦菲**
- **迪丽热巴**
- **杨颖（Angelababy）**

在此对上述三位艺人表示诚挚的感谢！这些照片仅用于内部技术测试与算法验证，帮助我们优化人脸检测、关键点映射及美颜效果。

**版权声明**：
- 所有测试图片均来自互联网公开资源，版权归原权利人所有
- 本项目为开源学习项目，不涉及任何商业用途
- 如上述照片的使用侵犯了您的合法权益，请联系我们，我们将立即移除相关图片
- 联系方式：请通过 GitHub Issues 提交侵权通知

我们尊重每一位艺人的肖像权与知识产权，感谢您的理解与支持！
