# PicMe 项目概览

PicMe 是一款基于 Jetpack Compose 和 CameraX 构建的高性能现代化 Android 相机应用。它具备实时人脸检测、先进的美颜滤镜以及智能媒体相册。

## 核心哲学与规范 (Core Philosophy & Specs)

### 1. 奥卡姆剃刀原则 (Occam's Razor)
- **[MUST] 如无必要，勿增实体**: 在设计类、接口或逻辑流程时，优先选择最简单的路径。严禁为了“未来可能的需求”引入复杂的抽象层或设计模式。
- **[ACTION] 代码裁剪**: 任何无法证明其必要性的代码路径、变量或类定义都应被移除。

### 2. Unix 极简哲学 (Unix Minimalism)
- **[MUST] 只做一件事**: 每个组件（函数、类、模块）应保持职责单一（Single Responsibility），并将其做到极致。
- **[MUST] 组合优于集成**: 复杂的业务逻辑应通过组合多个简单的原子化 Usecase 或组件来实现，避免编写“万能类”或“上帝组件”。

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

```text
com.picme
├── core/                # 【核心层】不依赖业务，提供基础设施
│   ├── common/          # 扩展函数 (Context, Flow)、Result 包装类
│   ├── designsystem/    # UI 规范：Theme, Color, Typography, Icons
│   ├── image/           # 图片处理引擎 (ImageProcessor, Effects)
│   └── camera/          # 相机底层封装 (CameraManager, Analyzer)
├── data/                # 【数据层】负责数据持久化与原始转换
│   ├── local/           # Room (Database, Dao)
│   ├── preferences/     # DataStore (UserPreferences)
│   ├── model/           # 数据库 Entity (MediaEntity)
│   └── repository/      # Repository 的具体实现 (MediaRepositoryImpl)
├── domain/              # 【领域层】纯 Kotlin，定义业务逻辑契约
│   ├── model/           # 领域模型 (MediaAsset, BeautySettings)
│   ├── repository/      # Repository 接口定义
│   └── usecase/         # 独立业务逻辑 (GroupMedia, SavePhoto)
├── features/            # 【功能层】按业务模块划分 UI 与状态
│   ├── camera/          # 相机拍摄模块
│   │   ├── components/  # CameraOverlays, ShutterButton 等
│   │   ├── CameraScreen.kt
│   │   └── CameraViewModel.kt
│   ├── gallery/         # 相册模块
│   │   ├── components/  # MediaGrid, MediaGroupHeader
│   │   ├── GalleryScreen.kt
│   │   └── MediaViewModel.kt
│   └── editor/          # 图片编辑模块
└── navigation/          # 【导航层】
    ├── NavGraph.kt      # 路由图
    └── Screen.kt        # 路由定义
```

## 编程严谨性规则 (Strict Coding Rules)

### 1. 多国语言适配 (Mandatory Internationalization)
- **[MUST] 强制资源解耦**: 严禁在 UI 代码中硬编码任何面向用户的字符串。所有新增功能或重构现有功能时，必须同步更新 `res/values/strings.xml` (默认英文), `res/values-zh-rCN/strings.xml` (简体中文), 和 `res/values-zh-rTW/strings.xml` (繁体中文)。
- **[MUST] 预览兼容**: 在 `@Preview` 中应尽可能测试不同语言下的文本显示效果（如长文本截断）。

### 2. Kotlin Lambda 规范
- **[MUST] 显式命名 Lambda 参数**: 在编写所有 UI 事件回调（如 onClick, onValueChange）时，必须显式命名参数（如 `{ ratio -> ... }`），禁止使用隐式的 `it`，除非逻辑极其简单（单行）。这能避免在大块重构时发生 `Unresolved reference 'it'`。
- **[MUST] 参数解构**: 处理 `Offset`、`Size` 等对象时，必须显式解构或命名，例如 `onFocus = { offset -> ... }`。

### 3. Jetpack Compose 作用域保护
- **[NEVER] 跨作用域调用**: 严禁将 @Composable 函数调用放在非 @Composable 的 Lambda 或普通函数中。
- **[CHECK] 参数对齐**: 在调用大型 Composable 组件（如 `CameraPreviewContent`）时，必须逐一检查每个参数是否与定义匹配，禁止遗漏必填参数。

### 4. 错误自愈流程
- **[ACTION] 编译验证**: 在完成大规模代码修改后，Agent 必须尝试运行 `./gradlew classes` 或查看 IDE 的实时错误提示，并在回复前自行修复所有红色波浪线标记。

### 5. 架构纯净性 (Architectural Purity)
- **[MUST] 简洁的 Application**: `Application` 类应保持极简。复杂的初始化逻辑、第三方库配置（如 Coil 的 `ImageLoader`）应移至专门的初始化类或配置类中。
- **[MUST] 依赖倒置**: 核心组件的初始化应尽量通过专门的 `AppContainer` 或依赖注入框架管理，避免 `Application` 成为上帝类。

### 6. 交互动效 (Fluid Interactions)
- **[MUST] 共享元素效果**: 从缩略图打开全屏预览时，必须实现“从点击位置逐渐放大”的丝滑过渡动效，禁止使用简单的渐变或滑动切换。
- **[CHECK] 帧率优化**: 确保复杂交互动画在大图加载时依然保持满帧运行。

## 构建与运行
- **编译**: `./gradlew assembleDebug`
- **运行**: 标准 Android Studio 运行配置。
- **最低 SDK**: 24
- **目标 SDK**: 35
