# PicMe 全局开发规范 (Single Source of Truth)


本文件是 PicMe 项目在架构、编码风格、日志记录等跨领域约束上的唯一权威来源。所有团队成员必须遵循此规范进行开发。

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

## 3. 全局日志与调试规范 (Global Logging & Debug) [STRICT]

### A. 结构化 Tag 设计
所有日志必须遵循 `PicMe:[Module]` 格式：
- `PicMe:Camera`: 拍摄流、生命周期、配置变更。
- `PicMe:Gallery`: 媒体加载、分组逻辑、Pager 索引。
- `PicMe:Scraping`: **[DEBUG 核心]** 记录搜索链接数、重试详情、Referer 伪装细节。
- `PicMe:Data`: 数据库事务、文件 IO、Room Migration。
- `PicMe:Domain`: 业务逻辑、UseCase 执行。
- `PicMe:AI`: ML Kit 人脸检测坐标、内容校验、皮肤分析。
- `PicMe:Nav`: 页面跳转、路由参数管理。
- `PicMe:Perf`: 启动耗时、帧率波动、内存占用。

### B. 调试工具流
- **[BUFFER] 内存缓存**：日志必须写入全局 `LogRepository` 的内存缓存（上限 500 条），遵循 FIFO。
- **[OVERLAY] 浮窗检索**：UI 层必须提供基于 `Grep` 的实时过滤浮窗，方便定位 Scraping 失败的 URL 或系统异常。

## 4. 数据抓取与生成策略 (Scraping & Data Generation)

### A. 抓取策略
- **渠道优先级**：专业源 (`xiuren.org`, `tuchong.com`, `500px.com`) > 社交平台 (`xiaohongshu.com`, `huaban.com`) > 兜底 (`weibo.com`, `baidu.com`)。
- **反反爬机制**：必须使用随机 UA 池、指数退避重试（`currentDelay *= 2`）、`Semaphore(2)` 并发控制及随机请求延时 (1s-3s)。

### B. 实验数据质量 (Data Quality)
- **[MUST] 人脸检测**：写真类数据必须包含至少一张人脸。
- **[STRICT] 构图约束**：人脸高度占比必须 **< 40%**，拦截大头贴。
- **[VALIDITY] 物理校验**：亮度 > 20 且 方差 > 5.0，彻底拦截全黑或损坏图。

### C. 重复数据管理
- **[DUPLICATE_DETECTION]** 系统必须提供基于 MD5（精确重复）和感知哈希（相似图片）的双重检测机制。
- **[AUTO_CLEANUP]** 用户应能通过一键操作清理所有重复图片，保留每组的第一张。

## 5. 代码架构与目录结构 (Project Structure)


项目严格遵循高度解耦的 **Clean Architecture**（清洁架构）模式，并结合 **Feature-based**（特性驱动）的方式进行组织，以保证模块间的低耦合和高内聚。具体结构如下：

```text
com.picme
├── core/                # 核心基础库，提供跨模块复用的基础组件和工具。
│   ├── designsystem/    # UI 组件、主题、颜色、字体等设计系统资源。
│   ├── image/           # 图像处理逻辑、滤镜实现、图片加载框架（如Coil）配置。
│   └── common/          # 全局通用工具类、扩展函数、Logger 及其他公共基类。
├── data/                # 数据层，负责数据的持久化和访问，与外部世界交互。
│   ├── local/           # 本地数据库（Room）、DAOs 和文件 I/O 操作。
│   ├── model/           # 数据实体（Data Entity），对应数据库表结构或网络模型。
│   ├── preferences/     # 应用偏好设置（使用 DataStore 存储）。
│   └── repository/      # Repository 的具体实现，整合多个数据源。
├── domain/              # 领域层，包含纯粹的业务逻辑和规则，不依赖任何 Android SDK。
│   ├── model/           # 领域模型（Domain Model），定义业务概念和状态（如 MediaAsset）。
│   ├── repository/      # Repository 接口，定义领域层所需的数据契约。
│   └── usecase/         # 原子化的 UseCase，封装单一业务操作，是连接领域层和表现层的桥梁。
├── features/            # 业务功能模块，每个模块是一个独立的功能单元。
│   ├── camera/          # 相机功能，包含其 UI、ViewModel 和相关组件。
│   ├── gallery/         # 相册功能，包含其 UI、ViewModel 和相关组件。
│   ├── debug/           # 调试工具，用于实验数据生成、日志监控等功能。
│   └── settings/          # 设置中心，管理应用的所有用户可配置选项。
└── navigation/          # 负责整个应用的 Compose 页面导航逻辑。
```

## 6. 编程与代码风格约束 (Strict Rules) [CORE]

### 6.1 代码质量
- **[MUST] 类型安全**: 在处理 UI 状态（如 Loading, Success, Error）时，必须优先使用密封类 (Sealed Class)，以利用编译器检查来杜绝空指针异常，提高代码健壮性。
- **[MUST] Lambda 规范**: 在编写 lambda 表达式时，必须显式地为参数命名，禁止在复杂逻辑中使用隐式的 `it` 关键字，以增强代码的可读性和可维护性。仅在极简的单行转换中可例外。
- **[NEVER] 魔法值**: 严禁在代码中出现“魔法值”（Magic Values）。所有用户可见的字符串必须提取到 `res/values/strings.xml` 中，所有魔法数字必须定义为具有明确语义的常量（const val 或 object 内部的 val）。
- **[MUST] 单一职责**: 每个类、函数和模块都应只负责一项明确的职责。UseCase 必须是原子化的，函数长度建议不超过40行，过长的函数必须拆分为更小的逻辑单元。

### 6.2 代码风格与格式 (Code Style & Formatting) [STRICT]
- **[MUST] 官方规范**: 所有代码必须严格遵守 [Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)，以保证团队协作的一致性。
- **[MUST] 缩进规则**: 
  - Kotlin/Java 源文件：必须使用 **4 个空格** 进行缩进，绝对禁止使用 Tab 字符。
  - XML、JSON 等标记语言文件：必须使用 **2 个空格** 进行缩进。
- **[MUST] 导入管理**: 禁止使用任何形式的通配符导入（例如 `import com.example.*`）。所有导入语句必须精确指定类名，并在每次提交前清理IDE产生的无用导入（Unused Imports）。
- **[MUST] 函数长度**: 倡导编写短小精悍的函数。单一函数的长度应尽量控制在 40 行以内。如果函数过长或职责不单一，则必须将其重构为更小的、功能明确的私有函数。
- **[MUST] 修饰符顺序**: 类和成员的修饰符必须遵循 Kotlin 官方推荐的顺序，例如 `private final override fun`。具体的顺序请参考官方文档。

### 6.3 Agent 执行规约
- **网络监控**：任何对网络请求逻辑的修改（包括新增、删除或变更URL），都必须确保在 `PicMe:Scraping` 日志标签下记录原始请求的完整URL以及返回的HTTP状态码，以便于问题追踪和调试。
- **统计汇总**：每一个完整的数据抓取流程（从开始到结束）执行完毕后，Agent 必须自动生成并输出一份 `Round Statistics` 报告，其中包含各渠道的请求成功率、下载转换率等关键性能指标的汇总。
- **UI 入口保护**：在进行 UI 相关的修改时，必须优先考虑系统的可调试性。严禁通过布局调整或动画等方式遮挡或移除用于唤起调试工具（如日志浮窗）的“Debug 按钮”或其他系统入口。

## 7. AI 执行工作流 (Agent Workflow)

1.  **语境探索**：修改前必用 `find_usages` 或 `code_search`。
2.  **多语言检查**：涉及文案修改时，必须同步更新各语言 `strings.xml`。
3.  **精准修改与对齐**：优先使用 `replace_text`。修改后必须确保代码格式对齐（符合 4 空格缩进）。
4.  **自愈验证**：修改后必用 `analyze_current_file` 检查潜在错误与代码风格警告，并运行 `./gradlew assembleDebug` 验证。

## 8. 构建与环境
- **Min SDK**: 24 | **Target SDK**: 36
- **编译指令**: `./gradlew assembleDebug`
