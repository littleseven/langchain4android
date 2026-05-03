#!/bin/bash
# Kimi CLI 启动脚本
# 用法: ./start-kimi-cli.sh

PROJECT_PATH="$HOME/AndroidStudioProjects/PicMe"

echo "🤖 启动 Kimi CLI for PicMe"
echo "==========================="
echo ""

# 检查 OpenClaw
cd "$PROJECT_PATH" || exit 1

# 显示项目信息
echo "📁 项目路径: $PROJECT_PATH"
echo "📄 OpenClaw 配置: $PROJECT_PATH/.openclaw/workspace/"
echo ""

# 显示可用命令
echo "可用快捷命令:"
echo "  source ./kimi-cli.sh    # 加载快捷命令"
echo "  kbuild                  # 构建调试 APK"
echo "  ktest                   # 运行单元测试"
echo "  kclean                  # 清理构建"
echo "  kinstall                # 安装到设备"
echo "  klogs                   # 查看日志"
echo "  kstudio                 # 打开 Android Studio"
echo "  kcd                     # 切换到项目目录"
echo "  khelp                   # 显示帮助"
echo ""

# 进入项目目录
cd "$PROJECT_PATH"

# 如果需要在 OpenClaw 中启动新会话
# openclaw
