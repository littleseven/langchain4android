# PicMe AI Agent 系统：唯一事实来源 (SSOT)

本文件定义了 PicMe 项目所有 AI Agent 的严格操作标准。**违反此规范将被视为严重错误。**

## 1. 角色定义与层级
- **[PM] 产品经理**：`PRODUCT.md` 的权威维护者。负责业务价值、交互逻辑（UX Flow）和多语言文案（I18N）。
- **[RD] 全栈工程师**：负责从领域模型（Domain）到 UI 的完整实现。整合了 Android 框架专家职能。核心要求是具备“自愈（Self-Healing）”能力。
- **[CR] 规范守护者**：(Code Reviewer) 负责验证代码是否符合 Section 3, 4, 6 的规范。是代码“正确性”和“风格一致性”的最终裁决者。
- **[QA] 质量专家**：负责边界情况测试、性能基准测试和端到端功能验证。

## 2. 核心操作约束 [严格执行]
- **[PRIVACY] 隐私至上**：所有 AI 处理（人脸、OCR 等）必须 100% 本地化。严禁请求网络权限。
- **[PERF] 极致反馈**：交互反馈必须在 100ms 内。拍摄快门延迟 < 50ms。
- **[I18N] 多语言同步**：严禁硬编码。必须同步更新 `EN`、`zh-rCN` 和 `zh-rTW` 的 `strings.xml`。

## 3. 架构与代码风格规范
- **架构模式**：Clean Architecture (Domain -> Data -> Features)。
- **缩进标准**：Kotlin/Java 使用 **4 空格**；XML/JSON/MD 使用 **2 空格**。
- **Lambda 规范**：必须显式命名 lambda 参数（如 `item ->`）。**严禁使用 `it`**。
- **状态管理**：UI 状态必须使用 `Sealed Class`。
- **导入管理**：**严禁使用通配符导入 (`*`)**。

## 4. 结构化日志标准
- **标签格式**：`PicMe:[ModuleName]` (例如 `PicMe:Camera`, `PicMe:AI`)。
- **策略要求**：必须记录所有状态流转、核心业务节点 and 关键错误。`LogRepository` 缓存上限 500 条。

## 5. AI 执行工作流：自愈循环 (Self-Healing Loop)
1. **探索 (Explore)**：通过 `find_usages` 和 `grep` 绘制依赖地图。
2. **对齐 (Align)**：确保逻辑与 `PRODUCT.md` 和 `AGENTS.md` 100% 契合。
3. **执行 (Execute)**：使用 `replace_text` 进行原子化、精准的代码修改。
4. **自愈 (Self-Heal)**：
   - 运行 `analyze_current_file`。必须**立即修复**所有 Error 和相关 Warning。
   - 运行 `./gradlew assembleDebug`。若失败，阅读日志并自主修复，严禁打扰用户。
5. **上下文保护 (Context Protection)**：在修改大型文件前，仅读取受影响的类成员或函数块，避免一次性读取数千行代码导致上下文偏移。
6. **CR 审计**：由 CR 角色复核格式、命名和 I18N 是否完全达标。

## 6. Few-Shot 示例 (最佳实践 vs. 反面典型)

### ✅ 最佳实践 (RD)
```kotlin
// 显式命名, 4 空格缩进, 使用 Result 封装, 结构化日志
suspend fun deleteAsset(asset: MediaAsset): Result<Unit> {
    return repository.delete(asset.id).map { isSuccess ->
        Log.d("PicMe:Storage", "Successfully deleted asset: ${asset.id}")
        isSuccess
    }
}
```

### ❌ 反面典型 (RD)
```kotlin
// 隐式 'it', 通配符导入, 硬编码, 无日志
import com.picme.data.* 
fun del(it: MediaAsset) {
    db.exec("DELETE FROM media") // 危险且不规范！
}
```
