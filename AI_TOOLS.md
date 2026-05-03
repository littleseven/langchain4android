# PicMe AI 工具配置索引

> **用途**：速查本项目所有 AI 辅助工具的配置位置与规范来源。  
> **维护**：新增 AI 工具或调整配置路径时，必须同步更新本文件。

## 工具配置速查表

| 工具 | 配置位置 | 读取范围 | 用途 |
|------|----------|----------|------|
| **kimi-cli** | `.kimi/AGENTS.md` + `.kimi/skills/` | 项目级 | 终端交互式 AI 开发 |
| **Lingma** | `.lingma/skills/` | 项目级 | IDE 内代码补全与问答 |
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
.lingma/skills/          ← 唯一事实来源
    ↓ 符号链接
.openclaw/skills/        ← OpenClaw / kimi-cli 读取
    ↓ 符号链接
.kimi/skills/            ← kimi-cli 项目级 Skills
```

**新增 Skill 的标准流程**：

```bash
# 1. 在唯一事实来源创建 Skill
mkdir -p .lingma/skills/my-skill
echo "---" > .lingma/skills/my-skill/SKILL.md
echo "name: my-skill" >> .lingma/skills/my-skill/SKILL.md
echo "---" >> .lingma/skills/my-skill/SKILL.md

# 2. 同步到 OpenClaw
ln -sf ../../.lingma/skills/my-skill .openclaw/skills/my-skill

# 3. 同步到 kimi-cli
ln -sf ../../.lingma/skills/my-skill .kimi/skills/my-skill

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
| `av-gl-expert` | 音视频与 OpenGL 渲染专家 | 全平台 |
| `coordinate-system-standard` | 人脸关键点坐标系规范化 | 全平台 |
| `doc-sync-guardian` | 三层文档体系一致性检查 | 全平台 |
| `gpupixel-porting` | GPUPixel 算法移植规范 | 全平台 |
| `image-quality-checker` | 截屏图片质量分析 | 全平台 |
| `mediapipe-landmark-mapping` | MediaPipe 关键点映射规范 | 全平台 |

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
| 交互规范 | `docs/FEATURES.md` | 交互与体验规则 |
| 技术规范 | `docs/AGENTS_SPEC.md` | 代码风格与审查清单 |
| 相机技术 | `docs/CAMERA_PREVIEW_TECH_SPEC.md` | 坐标转换、Viewport 计算 |
| 美颜引擎 | `docs/BIG_BEAUTY_TECH_SPEC.md` | Shader 架构、多 Pass 渲染 |

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

## 兼容性变更记录

| 日期 | 变更 | 影响 |
|------|------|------|
| 2026-05-03 | 创建 `.kimi/AGENTS.md` 与 `.kimi/skills/` | kimi-cli 获得独立项目配置入口 |
| 2026-05-03 | `.openclaw/skills/` 同步新增 skills 符号链接 | OpenClaw 可见完整 skills 列表 |
| 2026-05-03 | 删除断裂的 `shader-debug` 符号链接 | 消除 OpenClaw 加载错误 |
| 2026-05-03 | 精简 `agents/README.md` | 避免与根 `AGENTS.md` 重复 |
| 2026-05-03 | 修正 `scripts/kimi-cli.sh` APK 路径 | `app-debug` → `picme-debug` |
