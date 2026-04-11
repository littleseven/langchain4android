# PicMe

PicMe 是一个面向长期演进的智能相机项目，核心强调三件事：
- AI 协作研发范式落地（Agent Team + Spec）
- 商业级隐私、性能与稳定性
- 大美丽 视觉能力库化（美颜 / 滤镜 / 妆容）

---

## 项目三大目标

1. **探索 AI Coding 范式并落地到相机研发**
   - Agent Team（CO / PM / RD / CR / QA）已在同一会话内串行协作，完成从需求到测试的全链路交付
   - Spec 驱动开发已落地：`PRODUCT.md` → `docs/FEATURES.md` → 模块 `AGENTS.md` 三层文档体系持续同步
   - 单元测试由 Agent 自主编写并维护（12 个纯 JVM 测试文件，覆盖坐标算法、状态机、端到端流程等），Bug 复现与修复过程可追溯
   - 沉淀中的 AI 协作方法论：自愈闭环（最多 2 次）、原子化修改、红线暂停机制

2. **打造可达商业级水平的相机应用**
   - 隐私优先：核心处理尽量本地化，最小化数据暴露
   - 性能稳定：首帧、帧率、交互延迟、容灾回退可观测可验证
   - 可交付：通过自动化测试与质量门禁保障版本可靠性

3. **沉淀优秀的视觉能力基础库**
   - 将 大美丽 自研能力（美颜 / 滤镜 / 妆容）从 App 业务逐步解耦
   - 长期演进为独立基础库（核心自研可控，无商业 SDK 依赖）
   - 提供稳定 API 与版本治理，支持跨业务复用

---

## 当前技术路线（简述）

- **单主引擎策略**：`BIG_BEAUTY` 为默认主引擎（自研 OpenGL ES + EGL 管线），`GPUPIXEL` 为实验性备选
- **容灾机制**：主引擎失败展示无美颜原生预览，冷却窗口后自动重试大美丽
- **观测能力**：关键指标统一输出（FPS、处理耗时、延迟、CPU、空帧）
- **逶出 PixelFree**：第三方商业 SDK 已于 2026-04 完全移除，第一性原则改由自研引擎主导
- **长期方向**：持续演进 `:beauty-engine` 模块，实现能力库化

---

## 项目结构（高层）

- `app/src/main/java/com/picme/domain`：领域模型、用例、仓储接口
- `app/src/main/java/com/picme/data`：数据源、仓储实现、偏好存储
- `app/src/main/java/com/picme/features`：业务页面与交互编排
- `app/src/main/java/com/picme/core/image`：图像处理、渲染与引擎适配
- `app/src/main/java/com/picme/di`：依赖装配与运行时策略
- `beauty-engine/`：美颜引擎独立库（OpenGL ES + EGL 渲染管线，对外暴露 `api/` 契约）
- `docs/`：产品、交互、技术与测试规范文档

---

## 快速开始

### 环境要求

- Android Studio（建议最新稳定版）
- JDK 11
- Android SDK（`compileSdk 36`）

### 常用命令

```bash
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

## 质量与协作

- 质量清单：`docs/BIG_BEAUTY_QA_EXECUTION_CHECKLIST.md`
- 大美丽 技术规范：`docs/BIG_BEAUTY_TECH_SPEC.md`
- 相机预览规范：`docs/CAMERA_PREVIEW_TECH_SPEC.md`
- 容灾降级说明：`docs/BEAUTY_ENGINE_FALLBACK.md`
- ~~PixelFree 兜底规范~~：~~`docs/PIXELFREE_FALLBACK_TECH_SPEC.md`~~ **（已废弃并移除，2026-04）**

协作规范与单一事实来源：
- 产品需求：`PRODUCT.md`
- 功能交互：`docs/FEATURES.md`
- 工程与 Agent 规范：`AGENTS.md`

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
  - 收敛依赖方向：`features -> domain usecase -> domain repository -> data impl`
  - 移除 domain 对 `android.*` / `features.*` 的依赖污染

### Phase 3（长期，8~16 周）：大美丽 能力库化

- 目标映射：
  - 目标 3（基础库沉淀）：将美颜/滤镜/妆容能力演进为独立视觉能力库
  - 目标 2（商业级应用）：以大美丽为主，评估 GPUPixel 实验性集成，持续提升效果质量与稳定性
- 关键动作：
  - 抽离 `beauty-core`（纯 Kotlin）：策略模型、参数协议、回退/恢复状态机
  - 持续迭代 `:beauty-engine` 模块：渲染与平台适配（Surface/CameraX/OpenGL）
  - 定义稳定 API 与语义版本，App 仅依赖能力接口

### 里程碑验收（跨阶段）

- M1：P0 自动化真实断言通过率 100%，关键链路可无人值守回归
- M2：核心模块完成依赖收敛，domain 层无平台/feature 污染
- M3：大美丽 形成可独立发布的能力模块，App 侧完成接口化接入

