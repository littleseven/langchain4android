# PicMe Wiki

> **Agent First 工程试验场** —— 以 Agent 为中心的客户端框架与研发流程

---

## 📚 快速导航

### 核心文档
- [产品定义](./Product-Definition) - 产品目标与验收标准
- [功能交互](./Features-Specification) - 功能交互细节规范
- [非功能性需求](./NFR-Specification) - 性能/稳定性指标
- [架构设计](./Architecture-Overview) - 系统架构概览

### Agent 能力
- [Agent 架构](./Agent-Architecture) - Agent 运行时架构
- [Capability 注册表](./Capability-Registry) - 所有 Capability 列表
- [命令参考](./Command-Reference) - 命令语法与示例
- [实现指南](./Capability-Implementation-Guide) - 新增 Capability 步骤

### 技术规范
- [美颜引擎](./Beauty-Engine) - 大美丽技术规格
- [帧同步美妆](./Frame-Sync-Makeup) - 帧同步系统详解
- [相机预览](./Camera-Preview) - 相机预览管线
- [人脸检测](./Face-Detection-Engines) - 多引擎对比与映射

### 开发规范
- [工作流](./Development-Workflow) - 双螺旋演进工作流
- [代码审查](./Code-Review-Checklist) - CR 检查清单
- [任务标记](./Task-Markup-Spec) - `[kimi-task]` 规范

### 质量标准
- [QA 验收](./QA-Execution-Checklist) - 端到端测试清单
- [坐标系标准](./Coordinate-System) - 人脸关键点坐标规范
- [术语词典](./Glossary) - 统一术语定义

### 容灾降级
- [美颜引擎容灾](./Beauty-Engine-Fallback) - 降级策略与恢复机制

---

## 🎯 项目目标

PicMe 是一个元实验，探索三个层次：

1. **运行时**: 端侧 AI Agent 架构 - LLM 能否成为应用的中枢神经系统？
2. **架构层**: Agent First 客户端框架 - 什么样的架构让 Agent 最高效？
3. **流程层**: Agent First 研发流程 - Agent 如何通过编排 Tools 完成开发？

---

## 📖 文档体系

```
┌─────────────────────────────────────────────────────────┐
│  PRODUCT (产品层) - What & Why                          │
│  • Product Definition                                   │
│  • Features Specification                               │
│  • NFR Specification                                    │
└─────────────────────────────────────────────────────────┘
                           ↓ 引用
┌─────────────────────────────────────────────────────────┐
│  ARCHITECTURE (架构层) - How                            │
│  • Architecture Overview                                │
│  • Agent Architecture                                   │
│  • Architecture Decisions                               │
└─────────────────────────────────────────────────────────┘
                           ↓ 指导
┌─────────────────────────────────────────────────────────┐
│  TECHNICAL SPECS (技术规范) - Implementation            │
│  • Beauty Engine                                        │
│  • Frame Sync Makeup                                    │
│  • Camera Preview                                       │
│  • Face Detection Engines                               │
└─────────────────────────────────────────────────────────┘
                           ↓ 实现
┌─────────────────────────────────────────────────────────┐
│  CAPABILITIES (Agent 能力) - Commands & Tools           │
│  • Capability Registry                                  │
│  • Command Reference                                    │
│  • Capability Implementation Guide                      │
└─────────────────────────────────────────────────────────┘
```

---

## 🚀 快速开始

```bash
# 克隆项目
git clone https://github.com/littleseven/PicMe.git
cd PicMe

# 构建 Debug APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# 自动化开发闭环
./scripts/auto-dev-loop.sh
```

---

## 🤝 贡献指南

### 文档更新流程

1. **需求变更** → 更新 `PRODUCT.md` / `FEATURES.md`
2. **架构调整** → 更新 `AGENT_ARCHITECTURE.md` + ADR
3. **技术实现** → 更新 `*_TECH_SPEC.md`
4. **代码同步** → 代码变更后同步更新对应 Spec 文档

### 文档规范

- 所有文档使用统一的头部格式（版本、状态、维护者）
- 添加反向链接注释（`// Spec: ...`）
- 遵循三层文档体系：`PRODUCT.md` → `FEATURES.md` → 模块 `AGENTS.md`

---

## 📊 项目统计

- **文档总数**: 26 个 Markdown 文件
- **目录结构**: 8 个逻辑层
- **Agent 角色**: CO, PM, RD, CR, QA
- **Capabilities**: Camera, Gallery, Settings, Navigation, Edit

---

## 🔗 相关链接

- [GitHub Repository](https://github.com/littleseven/PicMe)
- [AGENTS.md (顶层治理)](../AGENTS.md)
- [00-INDEX.md (文档导航)](../docs/00-INDEX.md)

---

> **最后更新**: 2026-05-29  
> **维护者**: CO Agent
