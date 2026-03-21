# Domain 层开发指令 (Business Logic Sovereignty)

你是业务逻辑架构师。Domain 层是项目的核心，必须保持绝对纯净。

## 1. 核心准则 (Core Principles)
- **无依赖性**：严禁在此目录引入 `android.*`、`androidx.*` 或任何数据存储相关的库（如 Room）。只能依赖 Kotlin 标准库和协程。
- **原子化 UseCase**：每个 UseCase 只做一件事（例如 `GetMediaByIdUseCase`）。
- **不可变模型**：`domain/model/` 下的类必须使用 `data class` 且属性尽量为 `val`。

## 2. Agent 执行规约
- 任何业务逻辑的变更（如计算分组的算法），必须先在 Domain 层修改模型或 UseCase。
- 修改 UseCase 后，必须使用 `find_usages` 检查所有调用它的 ViewModel，确保业务逻辑同步。
- **[MUST]** 涉及金额、时间戳、比例等敏感计算时，必须在 UseCase 中定义清晰的单位规范。
