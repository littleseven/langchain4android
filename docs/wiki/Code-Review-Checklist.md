# PicMe 代码审查检查清单 (Code Review Checklist)

> **边界声明（Boundary Statement）**
> - 本文档定义 CR 检查项与一票否决项。
> - 工作流规范以 [`DEVELOPMENT.md`](./DEVELOPMENT.md) 为准。
> - 技术规范以各模块 `*_TECH_SPEC.md` 为准。

**模块定位**: 代码审查标准与红线  
**主要维护者**: [CR] 规范守护者  
**阅读对象**: CR、RD  
**版本**: 1.0  
**最后更新**: 2026-05-29  

---

## 📋 目录

1. [CR 检查清单](#1-cr-检查清单)
2. [一票否决项](#2-一票否决项)
3. [架构合规检查](#3-架构合规检查)
4. [性能基线检查](#4-性能基线检查)
5. [文档同步检查](#5-文档同步检查)

---

## 1. CR 检查清单

### 1.1 文档同步

- [ ] 代码变更是否同步更新了对应 Spec 文档？
- [ ] 新增接口是否补充了 `api/` 文档说明？
- [ ] 新增验收条件是否关联了 `[kimi-task]` 标记？
- [ ] 反向链接注释是否正确（`// Spec: ...`）？

### 1.2 架构合规

- [ ] `api/` 包是否引入了 `egl/` 依赖？
- [ ] App 层是否直接实例化了 `egl/` 内部类？
- [ ] 新增公开 API 是否补充了默认值与向后兼容处理？
- [ ] Domain 层是否纯净（无 UI 依赖）？

### 1.3 性能

- [ ] 单帧处理耗时是否 ≤ 16ms？
- [ ] 参数变化时是否仅更新 uniform 而未重新编译 Shader？
- [ ] 预测补偿耗时是否 < 0.5ms / 帧？
- [ ] 内存占用是否符合 NFR 要求？

### 1.4 资源管理

- [ ] `release()` 是否完整释放了 EGL / GL / Surface / Thread 资源？
- [ ] 新增资源是否考虑了生命周期和异常路径？
- [ ] 是否存在资源泄漏风险（未关闭的 Stream/Connection）？

### 1.5 测试覆盖

- [ ] 新增功能是否补充了单元测试？
- [ ] 边界条件是否覆盖？
- [ ] 性能退化是否通过基准测试验证？
- [ ] 核心模块覆盖率 ≥ 70%？

### 1.6 日志与可观测性

- [ ] 是否使用了正确的日志标签（`PicMe:ModuleName`）？
- [ ] 关键路径是否输出了结构化日志？
- [ ] 敏感信息（用户数据、模型路径）是否脱敏？

### 1.7 国际化 (I18N)

- [ ] 新增文案是否覆盖 EN / zh-CN / zh-TW？
- [ ] 禁止硬编码用户可见字符串？
- [ ] 复数/性别等语言特性是否考虑？

---

## 2. 一票否决项

以下任一情况，CR 必须 **Request Changes**：

### 2.1 文档不同步

- 代码修改了实现但未更新对应 Spec
- 新增 API 无文档说明
- `[kimi-task]` 标记缺失或错误

### 2.2 架构越界

- `api/` 依赖 `egl/`
- App 直接实例化 `egl/` 类
- Domain 层引入 UI 依赖

### 2.3 性能退化

- 单帧处理耗时 > 20ms
- 帧率下降 > 5%
- 内存占用超出 NFR 红线

### 2.4 资源泄漏

- `release()` 未覆盖新增资源
- 未关闭的 Stream/Connection
- 静态集合无限增长（无清理机制）

### 2.5 无测试覆盖

- 新增功能无单元测试
- 核心逻辑无集成测试
- 边界条件未覆盖

### 2.6 I18N 缺失

- 新增文案未覆盖三语
- 硬编码用户可见字符串

---

## 3. 架构合规检查

### 3.1 ArchUnit 规则

```kotlin
// api/ 包不依赖 egl/
@ArchTest
val `api package should not depend on egl package` = noClasses()
    .that().resideInAPackage("..api..")
    .should().dependOnClassesThat().resideInAPackage("..egl..")

// App 层不直接实例化 egl/ 内部类
@ArchTest
val `app should not instantiate egl classes directly` = noClasses()
    .that().resideInAPackage("..features..")
    .should().callConstructorWhere(
        target(owner(resideInAPackage("..egl..")))
    )
```

### 3.2 detekt 规则

```kotlin
// 禁止在构造函数中启动渲染线程
class NoRenderThreadInConstructor : Rule() {
    override fun visitClass(klass: KtClass) {
        if (klass.hasAnnotation("GlThread") || klass.name?.contains("Renderer") == true) {
            // 检查构造函数中是否启动 Thread
        }
    }
}
```

### 3.3 手动检查项

- [ ] 查看 import 列表，确认无违规依赖
- [ ] 检查构造函数，确认无线程启动
- [ ] 验证包结构符合 Clean Architecture

---

## 4. 性能基线检查

### 4.1 CI 检查脚本

```yaml
perf-baseline-check:
  script:
    # 启动时间基准测试
    - ./gradlew benchmark:coldStartupBenchmark
    
    # 单帧处理耗时基准测试
    - ./gradlew benchmark:frameProcessingBenchmark
    
    # 与基线对比，退化 > 5% 则失败
    - python scripts/compare_perf_baseline.py \
        --current benchmark-results.json \
        --baseline perf-baseline.json \
        --threshold 5%
```

### 4.2 关键指标

| 指标 | 红线 | 目标 | 测量方法 |
|------|------|------|----------|
| 冷启动 → 首帧预览 | ≤ 500ms | ≤ 400ms | `adb shell am start -W` |
| 预览帧率（高端机） | ≥ 30fps | ≥ 55fps | `BeautyPerfStats.fps` |
| 单帧处理耗时 | ≤ 16ms | ≤ 12ms | Systrace / 自定义计时 |
| 参数响应延迟 | ≤ 100ms | ≤ 50ms | 人工体感 + 高速摄像 |
| 拍照后处理（1080p） | ≤ 300ms | ≤ 200ms | `PhotoProcessorImpl` 耗时 |

### 4.3 调试工具

- **Systrace**: 分析主线程耗时
- **Android Profiler**: 监控 CPU/内存
- **自定义计时器**: `PerformanceTracker.start("tag")` / `.end()`
- **调试浮层**: 实时显示 FPS/耗时

---

## 5. 文档同步检查

### 5.1 CI 检查脚本

```yaml
doc-sync-check:
  script:
    # 检查 PR 中修改了 egl/ 实现时是否同步修改了 AGENTS.md
    - python scripts/check_doc_sync.py \
        --code-path beauty-engine/src/main/java/com/picme/beauty/egl/ \
        --doc-path beauty-engine/AGENTS.md
    
    # 检查 PR 中修改了 api/ 接口时是否同步修改了技术文档
    - python scripts/check_doc_sync.py \
        --code-path beauty-engine/src/main/java/com/picme/beauty/api/ \
        --doc-path docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md
    
    # 检查文档间内部链接有效性
    - python scripts/check_doc_links.py \
        --root docs/
    
    # 检查 AGENTS.md 中提到的模块在代码中是否存在
    - python scripts/check_spec_completeness.py \
        --spec beauty-engine/AGENTS.md \
        --src beauty-engine/src/
```

### 5.2 文档更新检查项

- [ ] `FEATURES.md` 已更新（如有交互变更）
- [ ] `*_TECH_SPEC.md` 已更新（如实现细节变更）
- [ ] `CAPABILITY_REGISTRY.md` 已更新（如新增 Capability）
- [ ] `COMMAND_REFERENCE.md` 已更新（如新增命令）
- [ ] 反向链接注释已添加（`// Spec: ...`）

---

## 附录：CR 评论模板

### ✅ 通过

```markdown
## CR 结果：✅ Approved

### 亮点
- 代码结构清晰，命名规范
- 单元测试覆盖完善
- 性能优化到位

### 建议（非阻塞）
- 可考虑提取公共逻辑为扩展函数
- 日志标签可更统一
```

### ❌ 需要修改

```markdown
## CR 结果：❌ Request Changes

### 阻塞问题

#### 1. 文档不同步
- **位置**: `beauty-engine/src/main/java/com/picme/beauty/egl/CameraPreviewRenderer.kt`
- **问题**: 修改了 `render()` 实现但未更新 `AGENTS.md`
- **修复**: 同步更新 `beauty-engine/AGENTS.md#render-pipeline` 章节

#### 2. 性能退化
- **位置**: `FrameSyncManager.query()`
- **问题**: 新增锁导致单帧耗时从 12ms → 18ms
- **修复**: 改用 `ConcurrentHashMap` 替代 synchronized

#### 3. I18N 缺失
- **位置**: `strings.xml`
- **问题**: 新增文案 "美颜总开关" 未覆盖英文和繁体
- **修复**: 补充三语翻译
```

---

> **参考文档**:
> - [DEVELOPMENT.md](./DEVELOPMENT.md) — 开发工作流规范
> - [NFR_SPEC.md](../01-PRODUCT/NFR_SPEC.md) — 非功能性需求规格
> - [TASK_MARKUP_SPEC.md](./TASK_MARKUP_SPEC.md) — 任务标记规范
