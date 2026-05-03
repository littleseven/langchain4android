# AGENTS.md — OpenClaw 运行时配置

## 与项目规范的关系

本文件**不重复**项目通用规范，仅声明 OpenClaw / kimi-cli 的**运行时差异**。

> **通用规范（唯一事实来源）**：`../../AGENTS.md`（项目根目录）  
> **交互细节**：`../../docs/FEATURES.md`  
> **技术规格**：`../../docs/AGENTS_SPEC.md`  

## kimi-cli 使用方式

### 启动会话

```bash
cd ~/AndroidStudioProjects/PicMe
kimi-cli chat
```

### Agent 团队指令（自然语言）

在 kimi-cli 中直接发送以下指令：

| 指令 | 作用 |
|------|------|
| `激活我的团队` | 启动 AI Agent 团队（CO → PM → RD → CR → QA） |
| `自动执行` | 启动全自动开发流程 |
| `保守执行` | 关键节点等待确认 |
| `执行吧` | RD-Review 闭环直至完成 |

### kimi-cli 内置命令

输入 `/` 查看所有可用命令。常用命令包括：

```
/clear          # 清空对话
/exit           # 退出会话
/help           # 查看帮助
```

## 工作模式

### 自动执行模式（默认）
- AI 自动推进: CO → PM → RD → CR → QA
- 适用于: 常规功能开发、Bug 修复

### 保守执行模式
- 需显式声明 "保守执行"
- 关键节点等待确认
- 适用于: 架构变更、数据库迁移、重大重构

## 外部工具集成

### Android Studio
- 通过 AppleScript 控制
- 支持打开文件、执行 Gradle 任务

### ADB
- 设备调试、日志查看
- APK 安装与卸载

### 灵码 (Lingma)
- IDE 内 AI 辅助
- 快捷键: `Ctrl+Shift+L`（Windows/Linux）或 `Cmd+Shift+L`（macOS）

## Skills 配置

项目采用统一的 Skills 目录结构，同时支持 Lingma 和 OpenClaw / kimi-cli。

### 目录结构

```text
.lingma/skills/       # Lingma 原生 Skills 目录（唯一事实来源）
.openclaw/skills/     # OpenClaw Skills 目录（符号链接指向 .lingma/skills/）
```

### 可用 Skills

| Skill | 用途 |
|-------|------|
| `adb-bot` | ADB 自动化控制与调试 |
| `android-build-debug` | Android 编译、安装、日志调试流程 |
| `av-gl-expert` | 音视频与 OpenGL 渲染专家 |
| `coordinate-system-standard` | 坐标系规范化 |
| `doc-sync-guardian` | 文档一致性守护 |
| `gpupixel-porting` | GPUPixel 算法移植规范 |
| `image-quality-checker` | 图片质量检查 |
| `mediapipe-landmark-mapping` | MediaPipe 关键点映射规范 |

### 使用方式

- **Lingma**: 自动加载 `.lingma/skills/` 下的 Skills
- **OpenClaw / kimi-cli**: 自动加载 `.openclaw/skills/` 下的 Skills
- **同步机制**: `.openclaw/skills/` 通过符号链接指向 `.lingma/skills/`，确保内容一致

---

*本文件与项目根目录 AGENTS.md 配合使用。实际可用命令以 `kimi-cli` 内置命令为准。*
