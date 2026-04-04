# PicMe

PicMe 是一个面向长期演进的智能相机项目，核心强调三件事：
- AI 协作研发范式落地（Agent Team + Spec）
- 商业级隐私、性能与稳定性
- R Plan 视觉能力库化（美颜 / 滤镜 / 妆容）

---

## 项目三大目标

1. **探索 AI Coding 范式并落地到相机研发**
   - 在真实工程中实践 Agent Team（PM / RD / CR / QA 协同）
   - 推进 Spec 驱动开发（需求-实现-测试-评审全链路可追踪）
   - 沉淀可复用的 AI 协作工程方法论

2. **打造可达商业级水平的相机应用**
   - 隐私优先：核心处理尽量本地化，最小化数据暴露
   - 性能稳定：首帧、帧率、交互延迟、容灾回退可观测可验证
   - 可交付：通过自动化测试与质量门禁保障版本可靠性

3. **沉淀优秀的视觉能力基础库**
   - 将 R Plan 自研能力（美颜 / 滤镜 / 妆容）从 App 业务逐步解耦
   - 长期演进为独立基础库（类似 PixelFree 的产品形态，核心自研可控）
   - 提供稳定 API 与版本治理，支持跨业务复用

---

## 当前技术路线（简述）

- **双引擎策略**：`R_PLAN` 为默认主引擎，`PIXEL_FREE` 为稳定兜底
- **容灾机制**：主引擎失败自动回退，冷却窗口后自动重试
- **观测能力**：关键指标统一输出（FPS、处理耗时、延迟、CPU、空帧）
- **长期方向**：逐步抽离 `beauty-core` / `beauty-engine-rplan`，实现库化

---

## 项目结构（高层）

- `app/src/main/java/com/picme/domain`：领域模型、用例、仓储接口
- `app/src/main/java/com/picme/data`：数据源、仓储实现、偏好存储
- `app/src/main/java/com/picme/features`：业务页面与交互编排
- `app/src/main/java/com/picme/core/image`：图像处理、渲染与引擎适配
- `app/src/main/java/com/picme/di`：依赖装配与运行时策略
- `docs/`：产品、交互、技术与测试规范文档

---

## 快速开始

### 环境要求

- Android Studio（建议最新稳定版）
- JDK 11
- Android SDK（`compileSdk 36`）

### 常用命令

```bash
# 单元测试
./gradlew :app:testDebugUnitTest

# androidTest 编译检查（无设备场景）
./gradlew :app:compileDebugAndroidTestKotlin

# 仪器测试（需连接设备或模拟器）
./gradlew :app:connectedDebugAndroidTest
```

---

## 质量与协作

- 质量清单：`docs/R_PLAN_QA_CHECKLIST.md`
- R Plan 技术指南：`docs/R_PLAN_GUIDE.md`
- 相机预览指南：`docs/CAMERA_PREVIEW_GUIDE.md`
- PixelFree 兜底方案：`docs/PIXELFREE_INTEGRATION.md`

协作规范与单一事实来源：
- 产品需求：`PRODUCT.md`
- 功能交互：`docs/FEATURES.md`
- 工程与 Agent 规范：`AGENTS.md`

---

## 路线图（简版）

- **近阶段**：补强 P0 自动化真实断言，持续稳定双引擎容灾链路
- **中阶段**：按 Clean Architecture 收敛边界，降低 feature/data 直接耦合
- **长期**：完成 R Plan 能力库化，形成可独立发布与复用的视觉底座

