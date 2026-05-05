# PicMe Wiki

欢迎使用 **PicMe** - Android 平台上最快、最私密、审美最纯粹的相机与相册应用。

## 📖 快速导航

### 🚀 入门指南
- [项目概述](Home) - 了解 PicMe 的愿景与核心能力
- [快速开始](Quick-Start) - 环境配置与构建指南
- [架构概览](Architecture-Overview) - Clean Architecture + 单引擎设计

### 🎨 核心功能
- [实时美颜系统](Beauty-Engine) - 大美丽引擎技术详解
- [人脸检测双引擎](Face-Detection-Engines) - InsightFace + MediaPipe 架构
- [滤镜系统](Filter-System) - 色调滤镜与风格特效
- [拍照 GPU 化](GPU-Photo-Processing) - 离屏渲染管线

### 📐 技术文档
- [架构决策记录 (ADR)](Architecture-Decisions) - 关键技术决策文档
- [坐标系统标准](Coordinate-System) - 人脸关键点与渲染坐标系
- [相机预览技术规范](Camera-Preview-Spec) - CameraX 集成与优化
- [容灾降级机制](Fallback-Mechanism) - 引擎失败处理策略

### 🛠️ 开发指南
- [代码规范](Code-Standards) - Kotlin/Java 编码规范
- [AI 协作流程](AI-Collaboration) - Agent Team 工作流
- [测试指南](Testing-Guide) - 单元测试与仪器测试
- [贡献指南](Contributing) - 如何参与项目开发

### 📊 质量保障
- [QA 执行清单](QA-Checklist) - 大美丽质量验收标准
- [性能指标](Performance-Metrics) - FPS、延迟、内存监控
- [隐私与安全](Privacy-Security) - 本地 AI 与数据保护

---

## 🌟 项目亮点

### 1. 极致性能
- **启动速度**: 冷启动 < 500ms,无启动页
- **拍摄延迟**: 快门响应 < 50ms
- **相册滚动**: 1000+ 照片保持 120fps
- **拍照处理**: 1080p < 300ms (GPU 路径)

### 2. 隐私优先
- **100% 本地 AI**: 人脸检测、OCR、分类全部端侧运行
- **零云端依赖**: 不申请网络权限,离线可用
- **数据安全**: 所有数据存储在设备本地

### 3. 先进架构
- **Clean Architecture**: Domain → Data → Features 分层清晰
- **单引擎设计**: 自研 OpenGL ES + EGL 渲染管线
- **双人脸检测**: InsightFace (NNAPI 加速) + MediaPipe (备选回退)
- **多 Pass 渲染**: FaceMakeupPass 支持唇色/腮红精细妆容

### 4. HyperOS 美学
- **大圆角设计**: 28dp+ 统一圆角
- **流体动效**: Bezier 曲线模拟物理惯性
- **实时高斯模糊**: 毛玻璃效果提升质感
- **微交互反馈**: 触感 + 音效 + 视觉三位一体

---

## 📦 技术栈

| 类别 | 技术选型 |
|------|----------|
| **语言** | Kotlin 1.9+, Java 11 |
| **UI 框架** | Jetpack Compose, Material Design 3 |
| **相机** | CameraX (ImageCapture, ImageAnalysis, Preview) |
| **人脸检测** | InsightFace 2D106 (ONNX Runtime + NNAPI), MediaPipe Face Mesh 468 |
| **渲染引擎** | OpenGL ES 2.0, EGL Off-screen Rendering |
| **数据存储** | Room Database, DataStore Preferences |
| **依赖注入** | 手动 DI (Koin 评估中) |
| **构建工具** | Gradle Kotlin DSL, KSP |

---

## 🔗 相关资源

- **GitHub Repository**: [PicMe](https://github.com/littleseven/PicMe)
- **产品需求**: [PRODUCT.md](../PRODUCT.md)
- **功能交互**: [docs/FEATURES.md](../docs/FEATURES.md)
- **Agent 规范**: [AGENTS.md](../AGENTS.md)
- **技术专项**: [docs/](../docs/)

---

## 📅 更新日志

### v2026.05 (当前版本)
- ✅ InsightFace 2D106 默认首选,启用 NNAPI GPU/NPU 加速
- ✅ 拍照 GPU 化完成,预览/拍照效果一致性 99%+
- ✅ 风格特效移植到大美丽引擎 (卡通/素描/浮雕/色块化/交叉线)
- ✅ GPUPixel 完全移除,单引擎架构收敛
- ✅ 人脸检测双引擎容灾机制落地

### v2026.04
- ✅ MediaPipe Face Mesh 468→106 映射完成
- ✅ 大美丽多 Pass 渲染管线 (FaceMakeupPass)
- ✅ Clean Architecture 重构 (Domain/Data/Features)
- ✅ Agent Team 协作流程固化

---

**最后更新**: 2026-05-05  
**维护者**: PicMe RD Team  
**许可证**: MIT License
