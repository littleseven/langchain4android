# .kimi/skills — 项目级 Skills

本目录包含 PicMe 项目的 kimi-cli Skills，通过符号链接统一指向 `.lingma/skills/`（唯一事实来源）。

> **同步机制**：`.kimi/skills/` → 符号链接 → `.lingma/skills/`  
> **同时同步**：`.openclaw/skills/` → 符号链接 → `.lingma/skills/`

## 添加新 Skill

```bash
# 1. 在 .lingma/skills/ 下创建 Skill（唯一事实来源）
mkdir -p .lingma/skills/my-new-skill
# ... 创建 SKILL.md 等文件

# 2. 同步创建符号链接
ln -sf ../../.lingma/skills/my-new-skill .kimi/skills/my-new-skill
ln -sf ../../.lingma/skills/my-new-skill .openclaw/skills/my-new-skill
```

## 当前可用 Skills

| Skill | 描述 |
|-------|------|
| adb-bot | ADB 自动化控制与调试 |
| android-build-debug | Android 编译、安装、日志调试流程 |
| auto-dev-loop | **开发自循环**：一键完成编译→安装→验证→报告完整闭环 |
| av-gl-expert | 音视频与 OpenGL 渲染专家 |
| coordinate-system-standard | 人脸关键点坐标系规范化 |
| doc-sync-guardian | 三层文档体系一致性检查 |
| error-healer | **编译错误修复**：Kotlin/Gradle 错误分类与自愈策略 |
| gpupixel-porting | 历史参考：GPUPixel 算法移植规范（GPUPixel 已移除） |
| image-quality-checker | 截屏图片质量分析 |
| intent-router | **意图路由**：自然语言需求解析与上下文自动加载 |
| mediapipe-landmark-mapping | MediaPipe 关键点映射规范 |

## 注意事项

- **不要直接修改**本目录下的符号链接文件，请修改 `.lingma/skills/` 下的源文件
- 删除 Skill 时，同时清理 `.lingma/skills/`、`.kimi/skills/` 和 `.openclaw/skills/` 下的对应项
