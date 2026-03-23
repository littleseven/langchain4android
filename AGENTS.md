# PicMe AI Agent 系统：唯一事实来源 (SSOT)

本文件定义了 PicMe 项目所有 AI Agent 的严格操作标准。**违反此规范将被视为严重错误。**

## 1. 角色定义与层级
- **[PM] 产品经理**：`PRODUCT.md` 的权威维护者。负责业务价值、交互逻辑（UX Flow）和多语言文案（I18N）。
- **[RD] 全栈工程师**：负责从领域模型（Domain）到 UI 的完整实现。整合了 Android 框架专家职能。核心要求是具备"自愈（Self-Healing）"能力。
- **[CR] 规范守护者**：(Code Reviewer) 负责验证代码是否符合 Section 3, 4, 6 的规范。是代码"正确性"和"风格一致性"的最终裁决者。
- **[QA] 质量专家**：负责边界情况测试、性能基准测试和端到端功能验证。

## 2. 项目文档体系与关系

### 2.1 三层文档架构
```
PRODUCT.md (产品需求规格说明书)
    ↓ 引用
FEATURES.md (功能细节与业务逻辑规范)
    ↓ 指导
AGENTS.md (AI Agent 操作规范)
```

### 2.2 各文档职责与维护者

| 文档 | 定位 | 主要维护者 | 阅读对象 | 核心内容 |
|------|------|------------|----------|----------|
| **PRODUCT.md** | 产品需求 SSOT<br>(Single Source of Truth) | **[PM]** 产品经理 | PM、UI 设计师、<br>测试工程师、RD | - 产品愿景与使命<br>- 核心功能规范（相机、相册、滤镜等）<br>- 设计系统与 UX 准则<br>- 性能指标（启动<500ms、拍摄<50ms）<br>- 隐私与安全约束 |
| **FEATURES.md** | 功能交互细节规范 | **[PM]** 产品经理<br>**[RD]** 全栈工程师 | UI 设计师、<br>测试工程师、RD | - 用户交互流程（UX Flow）<br>- 体验规范和反馈规则<br>- 业务场景和判定规则（人脸分组、重复检测）<br>- 视觉风格指引（HyperOS 风格）<br>- 多语言词汇表（I18N） |
| **AGENTS.md** | AI Agent 操作规范 | **[CR]** 规范守护者 | AI Agent、RD | - 角色定义与职责<br>- 核心操作约束<br>- 架构与代码风格规范<br>- 结构化日志标准<br>- AI 执行工作流（Self-Heal Loop）<br>- 最佳实践示例 |

### 2.3 文档使用规则
- **[MUST] 单一可信源原则**：
  - 产品需求以 `PRODUCT.md` 为准
  - 交互细节以 `FEATURES.md` 为准
  - 代码规范以 `AGENTS.md` 为准
  
- **[MUST] 文档引用链**：
  - PRODUCT.md 中的功能规范会指向 FEATURES.md 的详细章节
  - FEATURES.md 中的技术实现会指向各模块的 AGENTS.md
  - AGENTS.md 中的业务逻辑会回溯到 PRODUCT.md 和 FEATURES.md

- **[MUST] 文档同步更新**：
  - 新增功能时，必须按顺序更新：PRODUCT.md → FEATURES.md → AGENTS.md
  - 修改现有功能时，必须同步更新所有相关文档
  - 严禁只修改代码而不更新文档

### 2.4 各模块 AGENTS.md
除根目录的总规范外，各功能模块还有自己的 AGENTS.md：
- `data/AGENTS.md` - 数据层实现规范
- `di/AGENTS.md` - 依赖注入规范
- `features/camera/AGENTS.md` - 相机功能实现细节
- `features/gallery/AGENTS.md` - 相册功能实现细节
- `features/editor/AGENTS.md` - 编辑器功能实现细节
- `features/settings/AGENTS.md` - 设置功能实现细节
- `features/debug/AGENTS.md` - 调试工具实现细节

**注意**：模块 AGENTS.md 应聚焦技术实现细节，不得包含产品需求或交互规范（这些应在 FEATURES.md 中定义）。

## 3. 核心操作约束 [严格执行]
- **[PRIVACY] 隐私至上**：所有 AI 处理（人脸、OCR 等）必须 100% 本地化。严禁请求网络权限。
- **[PERF] 极致反馈**：交互反馈必须在 100ms 内。拍摄快门延迟 < 50ms。
- **[I18N] 多语言同步**：严禁硬编码。必须同步更新 `EN`、`zh-rCN` 和 `zh-rTW` 的 `strings.xml`。

## 4. 架构与代码风格规范
- **架构模式**：Clean Architecture (Domain -> Data -> Features)。
- **缩进标准**：Kotlin/Java 使用 **4 空格**；XML/JSON/MD 使用 **2 空格**。
- **Lambda 规范**：必须显式命名 lambda 参数（如 `item ->`）。**严禁使用 `it`**。
- **状态管理**：UI 状态必须使用 `Sealed Class`。
- **导入管理**：**严禁使用通配符导入 (`*`)**。

## 5. 结构化日志标准
- **标签格式**：`PicMe:[ModuleName]` (例如 `PicMe:Camera`, `PicMe:AI`)。
- **策略要求**：必须记录所有状态流转、核心业务节点 and 关键错误。`LogRepository` 缓存上限 500 条。

## 6. AI 执行工作流：自愈循环 (Self-Healing Loop)
1. **探索 (Explore)**：通过 `find_usages` 和 `grep` 绘制依赖地图。
2. **对齐 (Align)**：确保逻辑与 `PRODUCT.md` 和 `AGENTS.md` 100% 契合。
3. **执行 (Execute)**：使用 `replace_text` 进行原子化、精准的代码修改。
4. **自愈 (Self-Heal)**：
   - 运行 `analyze_current_file`。必须**立即修复**所有 Error 和相关 Warning。
   - 运行 `./gradlew assembleDebug`。若失败，阅读日志并自主修复，严禁打扰用户。
5. **上下文保护 (Context Protection)**：在修改大型文件前，仅读取受影响的类成员或函数块，避免一次性读取数千行代码导致上下文偏移。
6. **CR 审计**：由 CR 角色复核格式、命名和 I18N 是否完全达标。

## 7. Few-Shot 示例 (最佳实践 vs. 反面典型)

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
