# TOOLS.md — OpenClaw 运行时工具引用

> **定位**：本文件仅保留 OpenClaw / kimi-cli **特有的运行时上下文**。  
> **通用开发指南**（环境、构建、调试、IDE 快捷键）：`../../DEVELOPMENT.md`

## OpenClaw + kimi-cli 远程开发

### 快速开始

```bash
# 1. 进入项目目录
cd ~/AndroidStudioProjects/PicMe

# 2. 启动 kimi-cli 会话
kimi-cli chat

# 3. 在 kimi-cli 中激活 Agent 团队
# 发送消息: "激活我的团队"
```

### 远程开发工作流

```bash
# 方式 1: 本地项目 + kimi-cli（推荐）
cd ~/AndroidStudioProjects/PicMe
kimi-cli chat

# 方式 2: 使用项目脚本
source scripts/kimi-cli.sh
```

### 常用 Gradle 命令速查

```bash
# 构建调试版本
./gradlew :app:assembleDebug

# 运行测试
./gradlew test

# 查看日志
adb logcat -s "PicMe:*"
```

> 完整命令列表见 `../../DEVELOPMENT.md`

---

*项目路径: ~/AndroidStudioProjects/PicMe*
