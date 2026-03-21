# PicMe 项目概览

PicMe 是一款基于 Jetpack Compose 和 CameraX 构建的高性能现代化 Android 相机应用。它具备实时人脸检测、先进的美颜滤镜以及智能媒体相册。

## 技术栈
- **语言**: Kotlin 2.2.10
- **UI 框架**: Jetpack Compose (Material Design 3)
- **相机引擎**: CameraX (ImageCapture, VideoCapture, ImageAnalysis)
- **AI/ML**: Google ML Kit (人脸检测)
- **数据库**: Room (KSP) 用于媒体元数据存储
- **持久化**: DataStore (Preferences) 用于设置存储
- **媒体加载**: Coil 2.7.0 (包含 VideoFrameDecoder)
- **视频播放**: Media3 ExoPlayer 1.5.1
- **架构**: MVVM 配合 Repository 模式

## 项目结构
我们将项目划分为四个核心顶层包：core（底层能力）、data（数据源）、domain（业务契约）、features（功能模块）。


com.picme
├── core/                # 【核心层】不依赖业务，只提供基础设施
│   ├── common/          # 扩展函数 (Context, Flow)、常量、Result 包装类
│   ├── designsystem/    # UI 规范：Theme.kt, Color.kt, Typography.kt, Icons.kt
│   ├── image/           # 图片处理引擎 (ImageProcessor, Effects)
│   └── camera/          # 相机底层封装 (CameraManager, Analyzer)
│
├── data/                # 【数据层】负责数据持久化与原始转换
│   ├── local/           # Room (Database, Dao)
│   ├── preferences/     # DataStore (UserPreferences)
│   ├── model/           # 数据库 Entity (MediaEntity)
│   └── repository/      # Repository 的具体实现 (MediaRepositoryImpl)
│
├── domain/              # 【领域层】纯 Kotlin，定义业务逻辑契约
│   ├── model/           # 领域模型 (MediaAsset, BeautySettings)
│   ├── repository/      # Repository 接口定义
│   └── usecase/         # 独立业务逻辑 (GroupMediaUseCase, SavePhotoUseCase)
│
├── features/            # 【功能层】按业务模块划分 UI 与状态
│   ├── camera/          # 相机拍摄模块
│   │   ├── components/  # CameraOverlays.kt, ShutterButton.kt
│   │   ├── CameraScreen.kt
│   │   └── CameraViewModel.kt
│   ├── gallery/         # 相册模块
│   │   ├── components/  # MediaGrid.kt, MediaGroupHeader.kt
│   │   ├── GalleryScreen.kt
│   │   └── MediaViewModel.kt
│   └── editor/          # 图片编辑模块
│
└── navigation/          # 【导航层】
├── NavGraph.kt      # 路由图
└── Screen.kt        # 路由定义

## 核心功能与实现
1. **多模式相机系统**: 
   - 支持标准拍照、视频录制、人像模式以及 **专业模式 (PRO)**。
   - **专业模式**: 提供手动控制曝光补偿 (EV) 和白平衡 (WB) 的功能，并具备实时反馈。
2. **AI 驱动的智能特性**:
   - **人脸检测**: 使用 ML Kit 实时检测人脸及关键点。
   - **智能自动对焦**: 针对检测到的人脸自动触发 `CameraControl` 对焦和测光，确保人像清晰。
3. **均衡的 UI 布局**:
   - **拆分式工具栏设计**: 左侧承载系统/辅助功能（设置、调试），右侧承载创意配置（信息、比例、美颜、滤镜）。
   - **对称式底部栏**: 相册缩略图与翻转摄像头按钮围绕主快门按钮左右对称分布。
4. **智能相册**:
   - 按日期降序排列，支持多种分组模式（按人脸、按人物）。
   - 批量管理功能（长按进入选择模式、多选、全选、批量删除）。
   - 集成 ExoPlayer 的全屏分页查看器。
   - **导航顺序优化**: 全屏预览的翻页顺序与当前网格的分组和排序保持一致。
5. **国际化**: 全面支持英文、简体中文及 **繁体中文** (支持运行时切换)。
6. **UI 迭代**: 所有主界面均提供完善的 `@Preview` 支持，并配合模拟数据。
7. **动漫风格自适应图标**: 基于古典美学参考设计的女性轮廓图标，取代了原有的技术风格图标。

## 构建与运行
- **编译**: `./gradlew assembleDebug`
- **运行**: 标准 Android Studio 运行配置。
- **最低 SDK**: 24
- **目标 SDK**: 35
