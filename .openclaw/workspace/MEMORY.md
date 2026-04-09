# MEMORY.md — PicMe 项目记忆

## 项目里程碑

### 2026-04 当前状态
- 项目已进入稳定开发阶段
- OpenClaw 工作区已配置完成

## 常用代码片段

### 创建新的 Feature 模块
```kotlin
// 在 features 包下创建新模块
package com.picme.features.[feature_name]

// 1. 创建 Screen Composable
@Composable
fun [Feature]Screen(
    viewModel: [Feature]ViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    // 实现 UI
}

// 2. 创建 ViewModel
@HiltViewModel
class [Feature]ViewModel @Inject constructor(
    private val repository: [Feature]Repository
) : ViewModel() {
    // 实现逻辑
}
```

### I18N 字符串添加
```xml
<!-- values/strings.xml (英文/默认) -->
<string name="[feature]_[action]">[English Text]</string>

<!-- values-zh-rCN/strings.xml (简体中文) -->
<string name="[feature]_[action]">[简体中文]</string>

<!-- values-zh-rTW/strings.xml (繁体中文) -->
<string name="[feature]_[action]">[繁體中文]</string>
```

### 结构化日志
```kotlin
private const val TAG = "PicMe:[ModuleName]"

Log.d(TAG, "Message: $data")
```

## 常见任务检查清单

### 新增功能
- [ ] 在 PRODUCT.md 中记录需求
- [ ] 在 docs/FEATURES.md 中更新交互规范
- [ ] 创建/更新模块 AGENTS.md
- [ ] 实现代码
- [ ] 添加三语言字符串
- [ ] 运行 `./gradlew assembleDebug` 通过
- [ ] 代码审查

### Bug 修复
- [ ] 定位问题根因
- [ ] 编写修复代码
- [ ] 验证修复
- [ ] 回归测试
- [ ] 更新相关文档

### 重构
- [ ] 分析影响范围
- [ ] 制定重构计划
- [ ] 分步执行（保守模式）
- [ ] 验证功能完整性
- [ ] 性能测试

## 技术债务追踪

<!-- 记录需要改进的代码区域 -->

| 区域 | 问题 | 优先级 | 计划处理时间 |
|------|------|--------|-------------|
| | | | |

## 学习记录

<!-- 记录开发过程中学到的新知识 -->

## 外部参考

- CameraX 官方文档: https://developer.android.com/training/camerax
- Jetpack Compose: https://developer.android.com/jetpack/compose
- Kotlin 协程: https://kotlinlang.org/docs/coroutines-guide.html
