# PicMe

PicMe 是一个面向长期演进的智能相机项目，核心强调三件事：
- AI 协作研发范式落地（Agent Team + Spec）
- 商业级隐私、性能与稳定性
- 大美丽 视觉能力库化（美颜 / 滤镜 / 妆容）

---

## 架构决策

| ADR | 决策 | 状态 | 说明 |
|-----|------|------|------|
| [ADR-001](docs/ADR-001-beauty-engine-architecture.md) | 大美丽与 GPUPixel 分层架构 | ✅ 已完成 | 2026-03-28 实施，api/impl 分层，EGL/GL 完整封装 |

### 大美丽与 GPUPixel 关系（重构后）

```
┌─────────────────────────────────────────────────────────┐
│                    App Layer (PicMe)                    │
│              ↓ 依赖 beauty-engine:api                   │
└────────────────────┬──────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────┐
│  Domain Layer: beauty-engine:api                        │
│  ├─ BeautyEngine (Interface)                          │
│  ├─ FilterType / BeautyParams                         │
│  └─ BeautyCallback                                    │
└────────────────────┬──────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────┐
│  Data Layer: beauty-engine:impl                       │
│  ├─ BeautyEngineImpl                                  │
│  ├─ GPUPixelAdapter (EGL/GL 封装)                    │
│  ├─ BigBeautyEngine (自研 OpenGL ES 管线)            │
│  └─ EGLContextManager                                 │
└────────────────────┬──────────────────────────────────┘
                     │
    ┌────────────────┴────────────────┐
    ▼                                 ▼
┌──────────────┐            ┌──────────────────┐
│  BIG_BEAUTY  │            │    GPUPIXEL      │
│ (自研引擎)   │            │ (Apache 2.0)     │
│ OpenGL ES    │            │ C++ JNI 库       │
└──────────────┘            └──────────────────┘
```

**依赖规则**：
- App 层**只能**依赖 `beauty-engine:api`
- `beauty-engine:impl` 为 internal，不对外暴露
- 所有 GPU/EGL 操作封装在 `GPUPixelAdapter` / `BigBeautyEngine`
- ArchUnit 自动化依赖检查已配置

---

## 项目三大目标

1. **探索 AI Coding 范式并落地到相机研发**
   - Agent Team（CO / PM / RD / CR / QA）已在同一会话内串行协作，完成从需求到测试的全链路交付
   - Spec 驱动开发已落地：`PRODUCT.md` → `docs/FEATURES.md` → 模块 `AGENTS.md` 三层文档体系持续同步
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

## 当前技术路线（2026-04）

- **双引擎策略**：`BIG_BEAUTY`（自研 OpenGL ES + EGL 管线）为默认主引擎；`GPUPIXEL`（Apache 2.0 开源，纯 C++11/OpenGL ES）为实验性备选，已完成集成
- **引擎切换**：通过用户设置动态切换，Composable 层零抖动平滑切换，无需重启相机
- **容灾机制**：主引擎失败展示无美颜原生预览，冷却窗口后自动重试大美丽
- **观测能力**：关键指标统一输出（FPS、处理耗时、延迟、CPU、空帧）
- **逐出 PixelFree**：第三方商业 SDK 已于 2026-04 完全移除，由自研引擎 + 开源引擎双轨主导
- **拍照后处理**：静态 Bitmap 后处理（CPU 路径）与实时预览（GPU 路径）完全解耦；RenderScript 已废弃替换为 Canvas + ColorMatrix
- **风格特效**：GPUPixel 模式下支持卡通、素描、浮雕、色块化、交叉线等 6 种风格特效实时切换
- **长期方向**：持续演进 `:beauty-engine` 模块，实现能力库化

---

## 项目结构（高层）

```
PicMe/
├── app/                             # 主应用模块
│   └── src/main/java/com/picme/
│       ├── domain/                  # 领域模型、用例、仓储接口（纯 Kotlin）
│       ├── data/                    # 数据源、仓储实现、偏好存储
│       ├── features/                # 业务页面与交互编排（Compose）
│       │   └── camera/
│       │       ├── preview/gl/      # 大美丽预览策略（BIG_BEAUTY 路径）
│       │       └── preview/gpupixel/ # GPUPixel 预览策略（GPUPIXEL 路径）
│       ├── core/
│       │   ├── image/               # 拍照后 CPU 静态 Bitmap 处理（与实时预览无关）
│       │   │   └── gl/              # BeautyParamsConverter（BeautySettings → BeautyParams）
│       │   ├── designsystem/        # 通用 UI 组件（HyperOS 风格）
│       │   └── common/              # Logger、扩展函数等基础工具
│       ├── di/                      # 依赖装配与运行时状态（手动 DI + BeautyEngineRuntimeState）
│       └── navigation/              # 页面路由
├── beauty-engine/                   # 实时美颜引擎独立库（OpenGL ES + GPUPixel）
│   └── src/main/java/com/picme/beauty/
│       ├── api/                     # 对外稳定 API 契约（禁止依赖 egl/ 内部实现）
│       ├── egl/                     # 自研 OpenGL ES + EGL 渲染管线（BIG_BEAUTY 主引擎）
│       └── gpupixel/                # GPUPixel 引擎适配层（实验性）
├── gpupixel/                        # GPUPixel 开源引擎（C++ JNI 库，Apache 2.0）
└── docs/                            # 产品、交互、技术与测试规范文档
```

**架构依赖方向（Clean Architecture）**：
```
features → domain usecase → domain repository → data impl
features → beauty-engine api/（禁止直接引用 egl/ 内部类）
core/image/ → domain model（拍照后处理，与实时预览隔离）
```

---

## 快速开始

### 环境要求

- Android Studio（建议最新稳定版）
- JDK 11
- Android SDK（`compileSdk 36`）
- NDK（GPUPixel C++ 库编译所需，Android Studio 自动下载）

### 常用命令

```bash
# 编译 Debug 包
./gradlew assembleDebug

# JVM 单元测试（无需设备）
./gradlew :app:testDebugUnitTest

# 仅编译单元测试（快速校验）
./gradlew :app:compileDebugUnitTestKotlin

# androidTest 编译检查（无设备场景）
./gradlew :app:compileDebugAndroidTestKotlin

# 仪器测试（需连接设备或模拟器）
./gradlew :app:connectedDebugAndroidTest
```

---

## 核心能力一览（2026-04）

### 实时美颜（大美丽 / GPUPixel 双引擎）

| 能力 | BIG_BEAUTY（自研） | GPUPIXEL（开源） |
|---|---|---|
| 磨皮 | ✅ 双边滤波（9pt 快速近似） | ✅ BeautyFaceFilter |
| 美白 | ✅ YUV 亮度调整 | ✅ BeautyFaceFilter |
| 瘦脸 | ✅ FaceWarp 网格变形 | ✅ FaceReshapeFilter |
| 大眼 | ✅ 径向放大变换 | ✅ FaceReshapeFilter |
| 唇色 | ✅ HSV 色相调整 | ✅ LipstickFilter |
| 腮红 | ✅ 双颊椭圆染色 | ✅ BlusherFilter |
| 专业调色 | ➖ 使用 CameraX 参数 | ✅ 曝光/对比度/饱和度/白平衡 |
| 风格特效 | ❌ 不支持 | ✅ 卡通/素描/浮雕/色块化等 6 种 |
| 色调滤镜 | ✅ ColorMatrix（OpenGL Shader） | ➖ 暂用大美丽路径 |

### 滤镜系统

- **色调滤镜**（第一排）：徕卡经典、徕卡自然、影院、胶片等 —— 通过 `ColorMatrix` 实时 Shader 变换，大美丽/GPUPixel 均生效
- **风格特效**（第二排）：卡通、平滑卡通、素描、色块化、浮雕、交叉线 —— 仅 GPUPixel 引擎生效，大美丽模式下置灰

### 拍照后处理

- 静态 Bitmap 美颜由 `GpuBeautyProcessor`（CPU 路径）完成，与实时预览 GPU 路径完全隔离
- 磨皮：Canvas + ColorMatrix 亮度近似（原 RenderScript 已因 API 废弃替换）
- 色调滤镜：`FilterType.getColorMatrix()` 应用于保存前的 Bitmap

---

## 质量与协作

### 文档索引

| 文档 | 说明 |
|---|---|
| `PRODUCT.md` | 产品需求规格说明书（SSOT，What） |
| `docs/FEATURES.md` | 功能交互规范（How） |
| `AGENTS.md` | Agent 协作治理规范（顶层） |
| `docs/BIG_BEAUTY_TECH_SPEC.md` | 大美丽渲染链路、容灾、冷却恢复 |
| `docs/CAMERA_PREVIEW_TECH_SPEC.md` | 相机预览技术规范 |
| `docs/BEAUTY_ENGINE_FALLBACK.md` | 容灾降级统一说明 |
| `docs/BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md` | 大美丽 QA 执行清单 |
| `docs/ADR-001-beauty-engine-architecture.md` | ADR-001: 大美丽与 GPUPixel 分层架构 |
| `beauty-engine/AGENTS.md` | beauty-engine 模块实现规范 |

> ~~`docs/PIXELFREE_FALLBACK_TECH_SPEC.md`~~ **已废弃并移除（2026-04）**

### 各模块 AGENTS.md

- `app/src/main/java/com/picme/core/AGENTS.md`
- `app/src/main/java/com/picme/di/AGENTS.md`
- `app/src/main/java/com/picme/features/camera/AGENTS.md`
- `app/src/main/java/com/picme/features/gallery/AGENTS.md`
- `app/src/main/java/com/picme/features/editor/AGENTS.md`
- `app/src/main/java/com/picme/features/settings/AGENTS.md`
- `app/src/main/java/com/picme/features/debug/AGENTS.md`

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
  - 目标 2（商业级应用）：以大美丽为主，GPUPixel 实验性集成持续提升效果质量与稳定性
- 关键动作：
  - 抽离 `beauty-core`（纯 Kotlin）：策略模型、参数协议、回退/恢复状态机
  - 持续迭代 `:beauty-engine` 模块：渲染与平台适配（Surface/CameraX/OpenGL）
  - 定义稳定 API 与语义版本，App 仅依赖能力接口

### 里程碑验收（跨阶段）

- M1：P0 自动化真实断言通过率 100%，关键链路可无人值守回归
- M2：核心模块完成依赖收敛，domain 层无平台/feature 污染
- M3：大美丽 形成可独立发布的能力模块，App 侧完成接口化接入
