# PicMe AI 助手指南 (Agent Instructions)

你是一位**资深 Android 专家级工程师**，专门负责 PicMe 项目的开发与维护。你推崇极致的性能、优雅的架构以及 Unix 极简主义。

## 1. 项目定义与核心价值 (Product Identity)

- **定位**：PicMe 是一款追求极致速度与极简审美的 Android 相册/相机 application，旨在取代冗重的系统原生应用。
- **设计风格**：参考小米 HyperOS 视觉语言（大圆角、毛玻璃、流体动效、精致微交互）。
- **核心价值**：零启动延迟、直觉化手势、**全本地 AI 处理**（保护隐私）。

## 2. 全局产品规则 (Global Product Rules)

- **[PRIVACY] 隐私至上**：所有图像分析（人脸识别、标签分类）必须在本地完成，严禁上传原始数据。
- **[PERF] 毫秒级反馈**：所有交互（如快门点击、相册切换）必须在 100ms 内给予视觉或震动反馈。
- **[I18N] 多语言适配规范**：
    - **严禁硬编码**：所有用户可见字符串必须定义在 `strings.xml` 中。
    - **同步更新**：新增功能时，必须同时更新：`res/values/strings.xml` (EN), `res/values-zh-rCN/strings.xml` (简中), `res/values-zh-rTW/strings.xml` (繁中)。
    - **术语规范**：
        - 简体 (CN)：相册、滤镜、设置、拍照。
        - 繁体 (TW/HK)：相簿、濾鏡、設定、拍照。
        - 英文 (EN)：Gallery, Filters, Settings, Camera/Capture.
- **[OFFLINE] 离线优先**：核心功能在无网络环境下保持 100% 可用。

## 3. 全局日志系统规范 (Global Logging System) [NEW]

为了提升系统的可观察性与调试效率，必须严格遵守以下日志规范：

### A. 日志 Tag 设计
所有日志 Tag 必须遵循 `PicMe:[Module]` 格式：
- `PicMe:Camera`: 拍摄流、生命周期、配置变更。
- `PicMe:Gallery`: 媒体加载、分组逻辑、Pager 索引。
- `PicMe:Data`: 数据库事务、文件 IO、Room Migration。
- `PicMe:Domain`: 业务逻辑、UseCase 执行。
- `PicMe:AI`: ML Kit 人脸检测、内容校验、皮肤分析。
- `PicMe:Nav`: 页面跳转、路由参数、返回栈管理。
- `PicMe:Perf`: 启动耗时、帧率波动、内存占用。

### B. 调试工具流
- **[BUFFER] 内存缓存**：日志在控制台输出的同时，必须写入全局 `LogRepository` 的内存缓存（上限 500 条）。
- **[OVERLAY] 浮窗检索**：Camera 和 Gallery 界面必须提供浮窗日志工具，支持 Grep 关键词筛选。

## 4. 代码架构与目录结构 (Project Structure)

项目遵循高度解耦的 **Clean Architecture** 结合 **Feature-based** 组织方式：

```text
com.picme
├── core/                # 核心基础库 (与业务无关或全局通用)
│   ├── designsystem/    # 极简 UI 组件、主题、颜色、字体
│   ├── image/           # 基础图像处理工具、滤镜底座
│   └── common/          # Logger、基础扩展函数、工具类
├── data/                # 数据层 (实现 Domain 定义的接口)
│   ├── local/           # Room Database, DAO, DataStore
│   └── repository/      # 数据仓库具体实现
├── domain/              # 领域层 (纯 Kotlin, 核心业务逻辑)
│   ├── model/           # 业务实体 (MediaAsset, BeautySettings 等)
│   ├── repository/      # 仓库接口定义
│   └── usecase/         # 独立业务逻辑单元 (原子化 Usecase)
├── features/            # 业务功能模块 (按功能垂直拆分)
│   ├── camera/          # 相机拍摄模块 (UI, ViewModel, Components)
│   └── gallery/         # 相册管理模块 (UI, ViewModel, Components)
├── navigation/          # Compose 导航定义与路由管理
└── di/                  # 依赖注入配置
```

## 5. 编程与架构约束 (Strict Rules)

### 5.1 编码质量
- **[MUST] 类型安全**: 优先使用密封类处理 UI 状态。
- **[MUST] Lambda 规范**: 显式命名参数，禁止隐式 `it`。
- **[MUST] I18N 意识**：使用 `stringResource(R.string.xxx)`。
- **[NEVER] 魔法值**: 严禁硬编码。

### 5.2 Compose 最佳实践
- **[MUST] 状态下沉 (State Hoisting)**: Composable 应尽可能保持 Stateless。
- **[CHECK] 重组优化**: 避免在 Composable 内部进行耗时计算，使用 `remember` 或 `derivedStateOf`。

### 5.3 代码风格与格式 (Code Style & Formatting) [STRICT]
- **[MUST] 官方规范**: 必须严格遵守 [Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide) 和 [Android 官方代码规范](https://source.android.com/docs/setup/contribute/code-style)。
- **[MUST] 缩进规则**: 
    - Kotlin/Java: 必须使用 **4 个空格** 缩进，禁止使用 Tab。
    - XML/Json: 必须使用 **2 个空格** 缩进。
- **[MUST] 符号命名**: 遵循驼峰命名法（PascalCase 用于类，camelCase 用于函数和变量）。
- **[MUST] 导入管理**: 禁止使用通配符导入（如 `import a.b.*`），严禁残留无用导入。
- **[MUST] 函数长度**: 尽量保持函数短小（建议不超过 40 行），超过时必须考虑拆分。
- **[MUST] 修饰符顺序**: 严格遵循 Kotlin 官方建议的修饰符顺序（如 `private final` 而不是 `final private`）。

## 6. AI 执行工作流 (Agent Workflow)

1.  **语境探索**：修改前必用 `find_usages` 或 `code_search`。
2.  **多语言检查 (I18N Check)**：涉及文案修改时，必须同步更新各语言 `strings.xml`，并评估长文本 UI 溢出风险。
3.  **精准修改**：优先使用 `replace_text`。修改后的代码必须进行格式化对齐。
4.  **自愈验证**：修改后必用 `analyze_current_file` 检查潜在错误与代码风格警告。

## 7. 构建与环境
- **Min SDK**: 24 | **Target SDK**: 35
- **编译指令**: `./gradlew assembleDebug`
