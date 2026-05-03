# OpenClaw Skills 目录

本目录包含项目专用的 AI Agent Skills，用于增强 kimi-cli/OpenClaw 的开发能力。

## 同步机制

本目录下的所有 Skills 均通过**符号链接**指向 `.lingma/skills/`，确保 Lingma 和 OpenClaw 使用同一套技能定义。

### 添加新 Skill

```bash
# 1. 在 .lingma/skills/ 下创建 Skill
mkdir -p .lingma/skills/my-new-skill
# ... 创建 SKILL.md 等文件

# 2. 创建符号链接到 .openclaw/skills/
ln -sf ../../.lingma/skills/my-new-skill .openclaw/skills/my-new-skill
```

### 当前可用 Skills

| Skill | 描述 |
|-------|------|
| [adb-bot](../../.lingma/skills/adb-bot/) | ADB 自动化控制与调试 |
| [android-build-debug](../../.lingma/skills/android-build-debug/) | Android 编译调试流程 |
| [gpupixel-porting](../../.lingma/skills/gpupixel-porting/) | GPUPixel 算法移植 |
| [mediapipe-landmark-mapping](../../.lingma/skills/mediapipe-landmark-mapping/) | MediaPipe 关键点映射 |
| [shader-debug](../../.lingma/skills/shader-debug/) | OpenGL ES Shader 调试 |

## 目录结构示例

```
my-skill/
├── SKILL.md          # 必需：技能主文件（含 frontmatter）
├── reference.md      # 可选：详细参考文档
├── examples.md       # 可选：使用示例
└── scripts/          # 可选：辅助脚本
    └── helper.sh
```

## SKILL.md 格式

```yaml
---
name: my-skill-name
description: 简短描述技能的用途和触发场景
---

# 技能标题

## 使用说明
...
```

## 注意事项

- **不要直接修改** `.openclaw/skills/` 下的文件，请修改 `.lingma/skills/` 下的源文件
- 符号链接会自动同步内容，无需手动复制
- 删除 Skill 时，同时删除 `.lingma/skills/` 下的目录和 `.openclaw/skills/` 下的符号链接
