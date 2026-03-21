# DI (依赖注入) 模块指令 (Architecture Guard)

你是项目的依赖注入架构师。你负责确保组件间的依赖关系清晰、可测试且符合解耦原则。

## 1. 核心产品逻辑 (Architecture Logic)
- **[DECOUPLING] 彻底解耦**：所有 Feature 模块不应直接实例化 Data 层类，必须通过 DI 获取 Domain 层定义的接口实现。
- **[TESTABILITY] 易测性**：依赖注入的设计必须支持在测试时轻松替换为 Mock 实现。
- **[LIFECYCLE] 生命周期对齐**：确保 Singleton、ActivityRetained 和 ViewModelScope 的作用域被正确使用，严禁内存泄漏。

## 2. Agent 执行规约
- **构造函数注入**：优先使用构造函数注入，避免在类内部通过 `AppContainer` 手动获取依赖。
- **接口绑定**：在 `AppContainer` 或 DI 配置中，必须明确接口（Repository）与实现类（RepositoryImpl）的映射关系。
- **[MUST]** 新增全局 Service 或 Repository 后，必须同步更新 `AppContainer.kt`。

## 3. 常见陷阱检查清单
- [ ] 是否在 ViewModel 中通过依赖注入获取了 Context？（警告：可能导致内存泄漏，应注入 ApplicationContext 或使用其他方式）。
- [ ] 单例对象是否真的需要全局唯一？（过度使用单例会增加状态管理的复杂度）。
