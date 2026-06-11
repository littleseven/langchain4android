# IDENTITY.md — PicMe 项目配置

## 项目信息
- **项目名称**: PicMe
- **项目类型**: Android 相机应用
- **项目路径**: ~/AndroidStudioProjects/PicMe
- **包名**: com.mamba.picme

## 开发者信息
- **主要开发者**: littleseven
- **协作模式**: AI Agent 辅助开发

## 技术规范
- **最小 SDK**: 24
- **目标 SDK**: 35
- **Kotlin 版本**: 2.1.0
- **Gradle 版本**: 8.10.2
- **构建工具**: Gradle + Kotlin DSL

## 核心模块
1. **camera**: 相机预览与拍摄
2. **gallery**: 相册浏览与管理
3. **editor**: 照片编辑（AI 美颜、滤镜）
4. **settings**: 应用设置
5. **data**: 数据层（Room、Repository）
6. **di**: 依赖注入（Hilt）

## 项目文档索引
- 产品需求: `PRODUCT.md`
- AI Agent 规范: `AGENTS.md`（根目录） / `.kimi/AGENTS.md`（kimi-cli 专用）
- 交互规范: `docs/01-PRODUCT/FEATURES.md`
- 技术规范: `docs/AGENTS_SPEC.md`
- 相机技术: `docs/03-TECHNICAL-SPECS/CAMERA_PREVIEW_TECH_SPEC.md`

## 快捷命令
```bash
# 进入项目目录
cd ~/AndroidStudioProjects/PicMe

# 构建调试版本
./gradlew assembleDebug

# 运行单元测试
./gradlew test

# 清理构建
./gradlew clean
```

## 模型配置
- **默认模型**: kimi/kimi-code（快速响应）
- **深度分析**: kimi/kimi-k2.5（复杂问题）

## kimi-cli 远程开发

### 环境要求
- **kimi-cli**: 已安装 (`npm install -g @kimi/kimi-cli`)
- **项目路径**: `~/AndroidStudioProjects/PicMe`

### kimi-cli 项目配置
- **项目级规范**: `.kimi/AGENTS.md`
- **项目级 Skills**: `.kimi/skills/`

### 远程开发配置
```bash
# 启动 kimi-cli 会话（推荐）
cd ~/AndroidStudioProjects/PicMe
kimi-cli chat

# 或使用项目脚本
./kimi-cli.sh
```

### 工作区路径
- **项目路径**: `/Users/guoshuai/AndroidStudioProjects/PicMe`
- **配置目录**: `.openclaw/workspace/`

### 快捷入口
```bash
# 方式 1: 直接进入项目并使用 kimi-cli
cd ~/AndroidStudioProjects/PicMe
kimi-cli chat

# 方式 2: 使用脚本启动（如果已配置）
./kimi-cli.sh
```
