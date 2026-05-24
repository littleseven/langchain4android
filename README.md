# PicMe

PicMe 是一款 Android 智能相机应用，核心能力围绕**自研实时美颜引擎（大美丽 / BIG_BEAUTY）**构建，强调三件事：

- **商业级隐私**：所有人脸检测、OCR、图像分类 100% 端侧运行，零云端推理
- **极致性能**：预览帧率 ≥ 30fps（目标 60fps），单帧处理 ≤ 16ms，快门延迟 < 50ms
- **自研可控**：OpenGL ES + EGL 全链路自研，零第三方 SDK 授权成本，效果与性能可精准调优

---

## 架构概览

```
App Layer (Compose UI + ViewModel)
    ↓ 仅允许依赖 beauty-engine:api
beauty-engine:api          ← 稳定 API 契约（BeautySettings / BeautyParams /
                              FilterType / Face / PhotoProcessor / ...）
    ↑ 由 render/ 实现
beauty-engine:render       ← 内部 OpenGL ES + EGL 渲染管线
    ├─ CameraPreviewRenderer   预览渲染核心
    ├─ BeautyRenderer          美颜 Shader 多 Pass 管线
    ├─ PhotoProcessorImpl      拍照 GPU 离屏渲染
    ├─ EGLCore                 EGL 上下文与 Surface 管理
    └─ internal/               人脸检测适配器、帧同步系统
```

**依赖红线**：App 代码禁止直接引用 `beauty-engine:render/` 内部类，必须通过 `api/` 接口访问。

---

## 当前技术状态（2026-05）

| 维度 | 现状 |
|------|------|
| **渲染引擎** | 大美丽（BIG_BEAUTY）单引擎，自研 OpenGL ES + EGL；GPUPixel 已完全移除 |
| **人脸检测** | InsightFace 2D106（默认，ONNX Runtime + NNAPI 加速）+ MediaPipe Face Mesh 468→106（fallback） |
| **拍照处理** | GPU 离屏渲染为标准路径（`PhotoProcessorImpl`，复用预览 Shader），CPU Canvas 为 fallback |
| **帧同步美妆** | 🔄 框架已落地（FrameSyncManager / MotionTracker / DetectionQueue），预测补偿与 hide 策略待收尾 |
| **风格特效** | 自研 Shader 支持卡通、素描、浮雕、色块化、交叉线实时渲染 |
| **坐标系** | 图像坐标系与人脸坐标系统一管理（ADR-003），消除前置/后置/旋转歧义 |

### 已落地能力

| 能力 | 状态 | 说明 |
|------|------|------|
| 磨皮 | ✅ | 双边滤波快速近似（9pt Shader 内联） |
| 美白 | ✅ | YUV 亮度通道调整 |
| 瘦脸/大眼 | ✅ | 基于 106 点 landmarks 的径向变形场 |
| 唇色/腮红 | ✅ | HSV 色相调整 + 纹理妆容，受帧同步系统保护 |
| 专业调色 | ✅ | 曝光/对比度/饱和度/色温/色调/亮度/RGB 通道 |
| 色调滤镜 | ✅ | ColorMatrix 实时 Shader 变换（徕卡/胶片/冷暖等） |
| 风格特效 | ✅ | 卡通/素描/浮雕/色块化/交叉线 |
| 拍照 GPU 化 | ✅ | `PhotoProcessorImpl` 离屏渲染，预览/拍照一致性 ≥ 99% |
| 人脸检测双引擎 | ✅ | InsightFace 默认 + MediaPipe fallback，统一 106 点输出 |
| 帧同步美妆框架 | 🔄 | 核心组件已落地，完整预测补偿待收尾 |

### 演进中的能力

| 能力 | 状态 | 规划 |
|------|------|------|
| 引导滤波磨皮 | ⏳ | O(N) 复杂度，更优边缘保持，替换当前双边滤波近似 |
| 3D LUT 滤镜 | ⏳ | 预计算 64×64×64 颜色查找表，支持专业调色风格动态扩展 |
| 帧同步预测补偿 | ⏳ | 运动轨迹预测 + 缺失隐藏，彻底解决妆容甩飞 |
| 大美丽库化 | ⏳ | 抽离 `beauty-core` 纯 Kotlin 接口模块，定义语义版本 |

---

## 项目结构

```
PicMe/
├── app/                               # 主应用模块
│   └── src/main/java/com/picme/
│       ├── domain/                    # 领域模型、用例、仓储接口（纯 Kotlin）
│       ├── data/                      # 数据源、仓储实现、Room 数据库
│       ├── features/                  # Compose UI + ViewModel
│       │   ├── camera/                # 相机预览与拍摄
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
│       └── render/                    # 内部 OpenGL ES + EGL 渲染管线
└── docs/                              # 技术规范与架构决策文档
```

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
```

完整开发指南见 [`DEVELOPMENT.md`](DEVELOPMENT.md)。

---

## 文档体系

PicMe 采用三层文档体系，状态标注规范：✅ 已落地 / 🔄 部分实现 / ⏳ 规划中

| 层级 | 文档 | 职责 | 状态 |
|------|------|------|------|
| What | [`PRODUCT.md`](PRODUCT.md) | 产品目标、验收口径、用户画像 | ✅ |
| How | [`docs/FEATURES.md`](docs/FEATURES.md) | 交互流程、体验规则、业务逻辑 | ✅ |
| Implementation | 模块 `AGENTS.md` | 代码规范、检查清单、实现细则 | ✅ |

### 技术专项文档

| 文档 | 说明 | 状态 |
|------|------|------|
| [`docs/BIG_BEAUTY_TECH_SPEC.md`](docs/BIG_BEAUTY_TECH_SPEC.md) | 大美丽渲染链路、容灾回退、性能监控 | ✅ |
| [`docs/CAMERA_PREVIEW_TECH_SPEC.md`](docs/CAMERA_PREVIEW_TECH_SPEC.md) | 相机预览技术规范、坐标转换 | ✅ |
| [`docs/FACE_DETECTION_ENGINE_ARCHITECTURE.md`](docs/FACE_DETECTION_ENGINE_ARCHITECTURE.md) | 人脸检测双引擎架构（InsightFace + MediaPipe） | ✅ |
| [`docs/PRD-FRAME-SYNC-MAKEUP.md`](docs/PRD-FRAME-SYNC-MAKEUP.md) | 帧同步美妆 PRD（解决妆容甩飞） | 🔄 |
| [`docs/TECH-SPEC-FRAME-SYNC-MAKEUP.md`](docs/TECH-SPEC-FRAME-SYNC-MAKEUP.md) | 帧同步美妆技术规范 | 🔄 |
| [`docs/BEAUTY_ENGINE_FALLBACK.md`](docs/BEAUTY_ENGINE_FALLBACK.md) | 容灾降级统一说明 | ✅ |

### 架构决策记录（ADR）

| ADR | 决策 | 状态 |
|-----|------|------|
| [ADR-001](docs/ADR-001-beauty-engine-architecture.md) | 大美丽单引擎分层架构（`api` / `render` 拆分） | ✅ 已接受 |
| [ADR-002](docs/ADR-002-opengl-offscreen-unified-pipeline.md) | OpenGL 离屏渲染统一拍照管线 | ✅ 已接受 |
| [ADR-003](docs/ADR-003-coordinate-system-management.md) | 坐标系统一管理 | ✅ 已接受 |

### 模块实现规范

- `app/src/main/java/com/picme/core/AGENTS.md`
- `app/src/main/java/com/picme/core/designsystem/AGENTS.md`
- `app/src/main/java/com/picme/data/AGENTS.md`
- `app/src/main/java/com/picme/di/AGENTS.md`
- `app/src/main/java/com/picme/features/camera/AGENTS.md`
- `app/src/main/java/com/picme/features/gallery/AGENTS.md`
- `app/src/main/java/com/picme/features/editor/AGENTS.md`
- `app/src/main/java/com/picme/features/settings/AGENTS.md`
- `app/src/main/java/com/picme/features/debug/AGENTS.md`
- [`beauty-engine/AGENTS.md`](beauty-engine/AGENTS.md)

---

## 质量红线

- **[PRIVACY]** 所有 AI 处理必须 100% 本地化，严禁云端推理
- **[PERF]** 交互反馈 < 100ms，拍摄快门延迟 < 50ms
- **[I18N]** 禁止硬编码用户可见文案，必须同步 `values` / `values-zh-rCN` / `values-zh-rTW`

---

## 致谢

本项目在开发过程中使用了以下公众人物的照片进行功能测试与技术验证：刘亦菲、迪丽热巴、杨颖（Angelababy）。这些照片仅用于内部技术测试与算法验证，帮助我们优化人脸检测、关键点映射及美颜效果。

- 所有测试图片均来自互联网公开资源，版权归原权利人所有
- 本项目为开源学习项目，不涉及任何商业用途
- 如上述照片的使用侵犯了您的合法权益，请通过 GitHub Issues 提交侵权通知，我们将立即移除
