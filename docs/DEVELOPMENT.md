# PicMe 开发工作流规范（Development Workflow）

**版本**：1.0  
**状态**：生效中  
**最后更新**：2026-05-14  
**维护者**：PM / CO  

---

## 1. 双螺旋演进工作流（Spec ↔ Code Co-Evolution）

### 1.1 核心原则

Spec 驱动开发（Spec-Driven Development, SDD）要求**文档与代码始终保持同步**，但在实践中允许"探索-固化"的双向演进：

```
Spec 文档（What & How）
    ↓ 驱动
代码实现（Implementation）
    ↓ 发现
实现中的新问题 / 优化点
    ↓ 反馈
Spec 文档更新（Consensus）
    ↓ 驱动下一轮...
```

### 1.2 探索-固化规则

| 阶段 | 规则 | 责任人 |
|------|------|--------|
| **探索期** | 当实现中发现 Spec 不明确时，允许先行探索代码实现，**最多 1 个 Commit** | RD |
| **固化期** | 探索完成后，必须在**同一 PR** 中更新对应 Spec 文档 | RD |
| **审查期** | CR 审查时同时审查代码 + 文档变更，文档缺失 = **一票否决** | CR |
| **验收期** | QA 验收时验证代码行为与 Spec 验收条件（AC）一致 | QA |

### 1.3 红线（Hard Limits）

- **[NEVER]** 禁止"先合并代码，后补文档"
- **[NEVER]** 禁止文档更新与代码实现分离提交（必须同一 PR）
- **[MUST]** 代码修改了 `egl/` 实现，必须同步修改 `AGENTS.md`（或 PR 描述中说明原因）
- **[MUST]** 代码修改了 `api/` 接口，必须同步修改 `AGENTS.md` + `BIG_BEAUTY_TECH_SPEC.md` + 通知 App 层适配
- **[MUST]** 新增功能必须在 `PRODUCT.md` / `PRD-*.md` / `FEATURES.md` 中有对应需求描述

---

## 2. 反向链接注释规范（Spec Traceability）

### 2.1 目的

在关键接口和实现代码中嵌入 Spec 引用，实现：
- 需求变更时快速定位受影响代码
- 代码审查时快速追溯设计意图
- 自动化工具生成 Traceability Matrix

### 2.2 注释格式

```kotlin
// Spec: <文档路径>#<章节/锚点>
// Implements: <AC-ID>（如 AC-P0-3）
// Related: <关联文档路径>#<章节>
// ChangeLog: <日期> <变更描述>（可选）
```

### 2.3 必须添加反向链接的位置

| 位置 | 示例 | 说明 |
|------|------|------|
| 公开 API 接口 | `BeautyPreviewProvider` | 关联 `AGENTS.md` 接口定义 |
| 核心算法实现 | `FrameSyncManager` | 关联 `PRD-*.md` 功能需求 |
| 架构边界类 | `api/` vs `egl/` 边界 | 关联架构约束 |
| 性能关键路径 | `CameraPreviewRenderer.render()` | 关联 `NFR_SPEC.md` 指标 |
| 降级/容灾逻辑 | `onGlWarmUpFallback()` | 关联 `BEAUTY_ENGINE_FALLBACK.md` |

### 2.4 完整示例

```kotlin
// Spec: beauty-engine/AGENTS.md#5-frame-sync
// Implements: AC-P0-3
// Related: docs/PRD-FRAME-SYNC-MAKEUP.md#2.2-FR-5
// ChangeLog: 2026-05-14 新增 missingThresholdFrames 字段
class FrameSyncManager(
    private val config: FrameSyncConfig = FrameSyncConfig.DEFAULT
) {
    // ...
}
```

### 2.5 Traceability Matrix

维护 `docs/TRACEABILITY_MATRIX.md`，记录需求 → 代码 → 测试的映射关系：

| 需求 ID | 需求描述 | 实现文件 | 测试文件 | 验收条件 |
|---------|---------|---------|---------|---------|
| FR-5 | 严格缺失处理 | `FrameSyncManager.kt` | `FrameSyncMissingTest.kt` | AC-P0-3 |
| FR-1 | FrameId 体系 | `FrameId.kt` | `FrameIdTest.kt` | AC-P0-1 |

---

## 3. CI 检查规则

### 3.1 文档同步检查（Doc Sync Check）

在 CI 流水线中加入轻量级脚本：

```yaml
# .github/workflows/doc-sync-check.yml
doc-sync-check:
  script:
    # 检查 PR 中修改了 egl/ 实现时是否同步修改了 AGENTS.md
    - python scripts/check_doc_sync.py \
        --code-path beauty-engine/src/main/java/com/picme/beauty/egl/ \
        --doc-path beauty-engine/AGENTS.md
    
    # 检查 PR 中修改了 api/ 接口时是否同步修改了技术文档
    - python scripts/check_doc_sync.py \
        --code-path beauty-engine/src/main/java/com/picme/beauty/api/ \
        --doc-path docs/BIG_BEAUTY_TECH_SPEC.md
    
    # 检查文档间内部链接有效性
    - python scripts/check_doc_links.py \
        --root docs/
    
    # 检查 AGENTS.md 中提到的模块在代码中是否存在
    - python scripts/check_spec_completeness.py \
        --spec beauty-engine/AGENTS.md \
        --src beauty-engine/src/
```

### 3.2 架构合规检查（Architecture Compliance）

使用 ArchUnit / detekt 自动检查架构红线：

```kotlin
// ArchUnit 检查：api/ 包不依赖 egl/
@ArchTest
val `api package should not depend on egl package` = noClasses()
    .that().resideInAPackage("..api..")
    .should().dependOnClassesThat().resideInAPackage("..egl..")

// ArchUnit 检查：App 层不直接实例化 egl/ 内部类
@ArchTest
val `app should not instantiate egl classes directly` = noClasses()
    .that().resideInAPackage("..features..")
    .should().callConstructorWhere(
        target(owner(resideInAPackage("..egl..")))
    )

// detekt 规则：禁止在构造函数中启动渲染线程
class NoRenderThreadInConstructor : Rule() {
    override fun visitClass(klass: KtClass) {
        if (klass.hasAnnotation("GlThread") || klass.name?.contains("Renderer") == true) {
            // 检查构造函数中是否启动 Thread
        }
    }
}
```

### 3.3 性能基线检查（Performance Baseline）

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

---

## 4. 代码审查（CR）规范

### 4.1 CR 检查清单（Checklist）

审查者必须在 PR 中确认以下检查项：

```markdown
## CR Checklist

### 文档同步
- [ ] 代码变更是否同步更新了对应 Spec 文档？
- [ ] 新增接口是否补充了 `api/` 文档说明？
- [ ] 新增验收条件是否关联了 `[kimi-task]` 标记？

### 架构合规
- [ ] `api/` 包是否引入了 `egl/` 依赖？
- [ ] App 层是否直接实例化了 `egl/` 内部类？
- [ ] 新增公开 API 是否补充了默认值与向后兼容处理？

### 性能
- [ ] 单帧处理耗时是否 ≤ 16ms？
- [ ] 参数变化时是否仅更新 uniform 而未重新编译 Shader？
- [ ] 预测补偿耗时是否 < 0.5ms / 帧？

### 资源管理
- [ ] `release()` 是否完整释放了 EGL / GL / Surface / Thread 资源？
- [ ] 新增资源是否考虑了生命周期和异常路径？

### 测试
- [ ] 新增功能是否补充了单元测试？
- [ ] 边界条件是否覆盖？
- [ ] 性能退化是否通过基准测试验证？

### 日志与可观测性
- [ ] 是否使用了正确的日志标签（`PicMe:BeautyEngine`）？
- [ ] 关键路径是否输出了结构化日志？
```

### 4.2 CR 一票否决项

以下任一情况，CR 必须 **Request Changes**：

1. **文档不同步**：代码修改了实现但未更新对应 Spec
2. **架构越界**：`api/` 依赖 `egl/` / App 直接实例化 `egl/` 类
3. **性能退化**：单帧处理耗时 > 20ms / 帧率下降 > 5%
4. **资源泄漏**：`release()` 未覆盖新增资源
5. **无测试覆盖**：新增功能无单元测试或集成测试
6. **I18N 缺失**：新增文案未覆盖 EN / zh-CN / zh-TW

---

## 5. 术语词典（Glossary）

维护统一的术语定义，确保 Spec 语义一致性：

| 术语 | 英文 | 定义 | 禁用别名 |
|------|------|------|---------|
| 大美丽 | Big Beauty | PicMe 自研 OpenGL ES + EGL 美颜引擎 | 美颜引擎、自研引擎 |
| 帧同步 | Frame Sync | 人脸检测帧与渲染帧的时间对齐机制 | 同步系统、时序对齐 |
| 妆容甩飞 | Makeup Detachment | 妆容与人脸位置不同步的分离现象 | 妆容滞后、妆容漂移 |
| 悬空残留 | Hover | 人脸出画后妆容仍停留在屏幕上的现象 | 妆容残留、妆容残留 |
| 严格缺失 | Strict Missing | 无检测结果 N 帧后强制隐藏妆容的策略 | 缺失隐藏、严格模式 |
| 预测补偿 | Prediction Compensation | 基于运动轨迹预测人脸位置的补偿算法 | 运动预测、预测算法 |
| FaceId | FrameId | 全局单调递增帧标识符 | 帧ID、frame_id |
| 零拷贝 | Zero Copy | GPU 管线中禁止 CPU-GPU 数据传输 | 无拷贝、直通 |
| 降级 | Fallback | 引擎异常时自动回退到基础预览 | 回退、降级策略 |
| 库化 | Library-ization | 将引擎模块演进为独立发布库 | 模块化、独立库 |

---

## 6. 更新历史

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| 1.0 | 2026-05-14 | 初版，定义双螺旋演进工作流、反向链接规范、CI 检查规则 | PM |
