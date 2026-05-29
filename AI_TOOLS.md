# PicMe AI 工具配置索引

> **用途**：速查本项目所有 AI 辅助工具的配置位置与规范来源。  
> **维护**：新增 AI 工具或调整配置路径时，必须同步更新本文件。

## 工具配置速查表

| 工具 | 配置位置 | 读取范围 | 用途 |
|------|----------|----------|------|
| **Qoder** | `.qoder/skills/` | 项目级 | 当前主力 AI 开发环境（Skills 目录） |
| **kimi-cli** | `.kimi/AGENTS.md` + `.kimi/skills/` | 项目级 | 终端交互式 AI 开发 |
| **OpenClaw** | `.openclaw/workspace/` + `.openclaw/skills/` | 项目级 | 工作区上下文与角色定义 |
| **Cursor** | `.cursorrules` | 项目级 | Cursor IDE 规范与上下文 |
| **通用** | `AGENTS.md`（根目录） | 项目级 | 顶层治理与全局红线 |

## 规范优先级（同目录内）

```
模块级 AGENTS.md   ← 最近层级优先
    ↓
根目录 AGENTS.md   ← 全局治理
    ↓
.kimi/AGENTS.md    ← kimi-cli 专用补充
    ↓
.cursorrules       ← 通用代码规范
```

## Skills 统一来源

```text
.qoder/skills/           ← 唯一事实来源（当前主力）
    ↓ 符号链接
.openclaw/skills/        ← OpenClaw 读取
    ↓ 符号链接
.kimi/skills/            ← kimi-cli 读取
```

**新增 Skill 的标准流程**：

```bash
# 1. 在唯一事实来源创建 Skill
mkdir -p .qoder/skills/my-skill
echo "---" > .qoder/skills/my-skill/SKILL.md
echo "name: my-skill" >> .qoder/skills/my-skill/SKILL.md
echo "---" >> .qoder/skills/my-skill/SKILL.md

# 2. 同步到 OpenClaw
ln -sf ../../.qoder/skills/my-skill .openclaw/skills/my-skill

# 3. 同步到 kimi-cli
ln -sf ../../.qoder/skills/my-skill .kimi/skills/my-skill

# 4. 更新索引文档
# - .openclaw/skills/README.md
# - .kimi/skills/README.md
# - 本文件（AI_TOOLS.md）
```

## 当前可用 Skills

| Skill | 描述 | 适用工具 |
|-------|------|----------|
| `adb-bot` | ADB 自动化控制与调试 | 全平台 |
| `android-build-debug` | Android 编译、安装、日志调试 | 全平台 |
| `auto-dev-loop` | **开发自循环**：编译→安装→验证→报告一键闭环 | 全平台 |
| `av-gl-expert` | 音视频与 OpenGL 渲染专家 | 全平台 |
| `coordinate-system-standard` | 人脸关键点坐标系规范化 | 全平台 |
| `doc-sync-guardian` | 三层文档体系一致性检查 | 全平台 |
| `egl-state-machine` | EGL 上下文与离屏渲染状态机规范 | 全平台 |
| `error-healer` | **编译错误修复**：Kotlin/Gradle 错误分类与自愈 | 全平台 |
| `gallery-delete-test` | 相册删除功能测试流程 | 全平台 |
| `gpupixel-porting` | 历史参考：GPUPixel 算法移植规范（GPUPixel 已移除） | 全平台 |
| `image-quality-checker` | 截屏图片质量分析 | 全平台 |
| `intent-router` | **意图路由**：自然语言需求解析与上下文加载 | 全平台 |
| `mediapipe-landmark-mapping` | MediaPipe 关键点映射规范 | 全平台 |
| `mnn-landmark-diagnosis` | MNN 关键点诊断与调试（C++ 层计时分析） | 全平台 |
| `onnx-model-integration` | ONNX 模型接入 Checklist（颜色/归一化/激活函数） | 全平台 |
| `rd-reflection` | **RD 复盘模板**：开发陷阱记录与流程优化 | 全平台 |

## 角色定义文件

| 角色 | 文件 | 说明 |
|------|------|------|
| [CO] 协调者 | `agents/co_agent.md` | 任务路由、状态管理、冲突仲裁 |
| [PM] 产品经理 | `agents/pm_agent.md` | 需求定义、UX 愿景、I18N 治理 |
| [RD] 全栈工程师 | `agents/rd_agent.md` | 技术实现、自愈修复、性能优化 |
| [CR] 规范审计 | `agents/review_agent.md` | 代码质量、架构合规、安全巡检 |
| [QA] 质量专家 | `agents/qa_agent.md` | 测试策略、边界测试、性能验收 |

> **快速参考**：`agents/README.md`（OpenClaw / kimi-cli 会话内速查）

## 核心项目文档

| 文档 | 路径 | 用途 |
|------|------|------|
| 产品需求 | `PRODUCT.md` | 目标与约束 |
| 交互规范 | `docs/01-PRODUCT/FEATURES.md` | 交互与体验规则 |
| 技术规范 | `AGENTS.md` | 代码风格与审查清单 |
| 相机技术 | `docs/03-TECHNICAL-SPECS/CAMERA_PREVIEW_TECH_SPEC.md` | 坐标转换、Viewport 计算 |
| 美颜引擎 | `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` | Shader 架构、多 Pass 渲染 |
| 人脸检测架构 | `docs/03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md` | 多引擎 ROI + Landmark 设计 |
| 帧同步妆容 | `docs/03-TECHNICAL-SPECS/FRAME_SYNC_TECH_SPEC.md` | 时序对齐、甩飞问题根治 |
| 性能基线 | `beauty-engine/README.md` | 各引擎耗时对比与设备基准 |

## 快捷命令

```bash
# 构建调试版本
./gradlew :app:assembleDebug

# 运行单元测试
./gradlew test

# 查看 PicMe 日志
adb logcat -s "PicMe:*"

# 启动 kimi-cli
cd ~/AndroidStudioProjects/PicMe && kimi-cli chat
```

> 完整开发指南（环境配置、构建命令、调试技巧、发布流程）：`DEVELOPMENT.md`

## IDE 内置 AI 助手

| 助手 | 配置位置 | 状态 | 备注 |
|------|----------|------|------|
| **Android Studio Studio Bot** | `.idea/studiobot.xml` | ⚠️ 已启用上下文共享 | 当前配置为 `shareContext="OptedIn"`。本项目代码涉及人脸识别、图像处理等敏感算法，如需严格符合 `[PRIVACY]` 红线（100% 本地），建议改为 `OptedOut`。 |
| **Qoder** | `.qoder/` | ✅ 已配置 | 当前主力 AI 开发环境 |
| **Lingma（通义灵码）** | `.lingma/skills/` | ⚠️ 已停用 | 原 Skills 已迁移至 `.qoder/skills/`，不再维护 |

## 兼容性变更记录

| 日期 | 变更 | 影响 |
|------|------|------|
| 2026-05-03 | 创建 `.kimi/AGENTS.md` 与 `.kimi/skills/` | kimi-cli 获得独立项目配置入口 |
| 2026-05-03 | `.openclaw/skills/` 同步新增 skills 符号链接 | OpenClaw 可见完整 skills 列表 |
| 2026-05-03 | 删除断裂的 `shader-debug` 符号链接 | 消除 OpenClaw 加载错误 |
| 2026-05-03 | 精简 `agents/README.md` | 避免与根 `AGENTS.md` 重复 |
| 2026-05-03 | 修正 `scripts/kimi-cli.sh` APK 路径 | `app-debug` → `picme-debug` |
| 2026-05-03 | 新增 `DEVELOPMENT.md` | 通用开发命令从 OpenClaw 独占迁移为全平台共用 |
| 2026-05-03 | 新增 `AI_TOOLS.md` | 统一索引所有 AI 工具配置位置 |
| 2026-05-20 | **Skills 唯一事实来源迁移** | `.lingma/skills/` → `.qoder/skills/`，Qoder 成为主力开发环境 |
| 2026-05-20 | 新增 `mnn-landmark-diagnosis` | MNN 关键点 C++ 层性能诊断 Skill |
| 2026-05-20 | 新增 `gallery-delete-test` | 相册删除功能测试 Skill |
| 2026-05-20 | 新增 `error-healer` / `intent-router` / `auto-dev-loop` | 编译自愈、意图路由、开发自循环 |
| 2026-05-22 | 移除 `armeabi-v7a` 架构支持 | 仅保留 `arm64-v8a`，APK 减小 54MB |
