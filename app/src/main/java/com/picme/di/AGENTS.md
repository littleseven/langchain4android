# DI (依赖注入) 模块指令 (Architecture Guard)

**模块定位**：确保组件间的依赖关系清晰、可测试且符合解耦原则。

**主要维护者**：[RD] 全栈工程师

**阅读对象**：RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[DECOUPLING] 彻底解耦**：所有 Feature 模块不应直接实例化 Data 层类，必须通过 DI 获取 Domain 层定义的接口实现。
- **[TESTABILITY] 易测性**：依赖注入的设计必须支持在测试时轻松替换为 Mock 实现。
- **[LIFECYCLE] 生命周期对齐**：确保 Singleton、ActivityRetained 和 ViewModelScope 的作用域被正确使用，严禁内存泄漏。

## 2. 技术实现规范 (Technical Implementation)

### 2.1 Hilt 模块定义
- **@Singleton 作用域**：Database、ImageLoader、OcrEngine 等全局单例必须使用 `@Singleton` 标注
- **@ViewModelScoped 作用域**：ViewModel 相关的依赖必须使用 `@HiltViewModel`和`@ViewModelScoped`
- **接口绑定**：在 `AppContainer` 或 DI 配置中，必须明确接口（Repository）与实现类（RepositoryImpl）的映射关系

### 2.2 依赖注入方式
- **构造函数注入优先**：优先使用构造函数注入，避免在类内部通过 `AppContainer` 手动获取依赖
- **字段注入限制**：仅在 Fragment 和 Activity 中使用 `@Inject` 字段注入
- **方法注入场景**：需要在 Module 中提供复杂依赖时使用 `@Provides` 方法注入

### 2.3 AppContainer 管理
- **职责单一**：`AppContainer.kt`仅负责注册全局单例，不包含业务逻辑
- **同步更新**：新增全局 Service 或 Repository 后，必须同步更新 `AppContainer.kt`
- **依赖顺序**：先注册 Database，再注册依赖 Database 的 Repository

## 3. Agent 执行规约 (Execution Rules)

- **构造函数注入**：优先使用构造函数注入，避免在类内部通过 `AppContainer` 手动获取依赖
- **接口绑定**：在 `AppContainer` 或 DI 配置中，必须明确接口（Repository）与实现类（RepositoryImpl）的映射关系
- **[MUST]** 新增全局 Service 或 Repository 后，必须同步更新 `AppContainer.kt`
- **[MUST]** 修改 Entity 或 Repository 后，检查是否需要更新 Database 版本号

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 是否在 ViewModel 中通过依赖注入获取了 Context？（警告：可能导致内存泄漏，应注入 ApplicationContext）
- [ ] 单例对象是否真的需要全局唯一？（过度使用单例会增状态管理的复杂度）
- [ ] 是否正确使用了 `@Singleton` 和 `@ViewModelScoped`？（避免作用域混淆）
- [ ] 新增的 Repository 是否已在 `AppContainer.kt` 中注册？
- [ ] 接口与实现的绑定关系是否清晰？（避免直接实例化实现类）
- [ ] 是否避免了循环依赖？（A 依赖 B，B 又依赖 A）

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ 架构清晰 → 通过 DI 实现彻底的模块解耦
- ✅ 易于测试 → 所有依赖都可轻松替换为 Mock 实现
- ✅ 无内存泄漏 → 生命周期严格对齐，避免不当引用

**技术决策记录**：
- 选择 Hilt 而非 Koin：编译期检查、更好的性能、官方推荐
- 构造函数注入优先：依赖显式声明，更易测试和维护
- 严格作用域管理：避免内存泄漏，确保组件生命周期正确
