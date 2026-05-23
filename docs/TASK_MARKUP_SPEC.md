# PicMe 任务标记规范（Task Markup Spec）

**版本**：1.0  
**状态**：生效中  
**最后更新**：2026-05-14  
**维护者**：PM / CO  

---

## 1. 目的

本文档定义 `[kimi-task]` 结构化标记规范，用于在 Spec 文档（`PRODUCT.md`、`PRD-*.md`、`FEATURES.md`）中直接嵌入**可执行、可追踪、可验证**的任务描述。外层编排脚本可自动解析此类标记，生成标准化 Task JSON，直接驱动 RD/QA Agent 执行，实现"需求变更 → 开发任务"的自动转换。

---

## 2. 标记格式

### 2.1 基本语法

```markdown
### <功能标题> [kimi-task:<task_id>]
- **Assignee**: <RD | QA | CR | PM>
- **Scope**: `<文件路径1>`, `<文件路径2>`
- **Expected Change**:
  1. <具体变更描述>
  2. <具体变更描述>
- **DependsOn**: <task_id_1>, <task_id_2>
- **EstimatedEffort**: <Xd | Xh | Xw>
- **Priority**: <P0 | P1 | P2>
- **Acceptance**: <AC-P0-X | AC-P1-X>
```

### 2.2 字段说明

| 字段 | 必填 | 格式 | 说明 |
|------|------|------|------|
| `task_id` | ✅ | `[a-z0-9-]+` | 全局唯一任务标识，如 `fsm-001`、`beauty-042` |
| `Assignee` | ✅ | `RD` / `QA` / `CR` / `PM` | 负责执行的角色 |
| `Scope` | ✅ | 逗号分隔的文件路径 | 预期修改的代码文件或模块 |
| `Expected Change` | ✅ | 有序列表 | 具体的代码变更预期，RD Agent 据此执行 |
| `DependsOn` | ❌ | 逗号分隔的 task_id | 前置依赖任务 |
| `EstimatedEffort` | ❌ | `Xd` / `Xh` / `Xw` | 预估工作量（天/小时/周）|
| `Priority` | ✅ | `P0` / `P1` / `P2` | 与验收标准对齐 |
| `Acceptance` | ✅ | `AC-P0-X` / `AC-P1-X` | 关联的验收条件 ID |

### 2.3 完整示例

```markdown
### FR-5：严格缺失处理 [kimi-task:fsm-005]
- **Assignee**: RD
- **Scope**: `beauty-engine/src/main/java/com/picme/beauty/internal/framesync/FrameSyncManager.kt`, `beauty-engine/src/main/java/com/picme/beauty/egl/CameraPreviewRenderer.kt`
- **Expected Change**:
  1. 在 `FrameSyncConfig` 中新增 `missingThresholdFrames: Int = 3` 字段
  2. 修改 `FrameSyncManager.query()`，当 `syncMode = STRICT` 且帧差 > `missingThresholdFrames` 时返回 `SyncStatus.MISSING`
  3. 在 `CameraPreviewRenderer.applySyncResultToRenderer()` 中，当 `syncStatus = MISSING` 时设置 `uHasFace = 0f`
  4. 更新 `BeautyPerfStats`，增加 `framesSinceDetection` 字段
- **DependsOn**: fsm-001, fsm-002
- **EstimatedEffort**: 2d
- **Priority**: P0
- **Acceptance**: AC-P0-3
```

---

## 3. 使用位置

### 3.1 允许使用 `[kimi-task]` 的文档

| 文档 | 用途 |
|------|------|
| `PRODUCT.md` | 记录高层功能需求对应的开发任务 |
| `docs/PRD-*.md` | 记录具体功能需求的开发任务 |
| `docs/FEATURES.md` | 记录交互变更对应的 UI/UX 任务 |
| `docs/BIG_BEAUTY_TECH_SPEC.md` | 记录技术实现任务 |

### 3.2 禁止使用 `[kimi-task]` 的位置

- `AGENTS.md`（模块级实现规范，不应包含任务分配）
- `README.md`（对外文档）
- 代码注释（使用反向链接注释规范，见 `DEVELOPMENT.md`）

---

## 4. 自动化解析规则

### 4.1 解析脚本输入

```bash
python scripts/parse_kimi_tasks.py \
  --input docs/PRD-FRAME-SYNC-MAKEUP.md \
  --output tasks/frame-sync-tasks.json
```

### 4.2 输出 JSON 格式

```json
{
  "source": "docs/PRD-FRAME-SYNC-MAKEUP.md",
  "extracted_at": "2026-05-14T10:00:00Z",
  "tasks": [
    {
      "task_id": "fsm-005",
      "title": "FR-5：严格缺失处理",
      "assignee": "RD",
      "scope": [
        "beauty-engine/src/main/java/com/picme/beauty/internal/framesync/FrameSyncManager.kt",
        "beauty-engine/src/main/java/com/picme/beauty/egl/CameraPreviewRenderer.kt"
      ],
      "expected_change": [
        "在 FrameSyncConfig 中新增 missingThresholdFrames: Int = 3 字段",
        "修改 FrameSyncManager.query()...",
        "在 CameraPreviewRenderer.applySyncResultToRenderer()...",
        "更新 BeautyPerfStats..."
      ],
      "depends_on": ["fsm-001", "fsm-002"],
      "estimated_effort": "2d",
      "priority": "P0",
      "acceptance": "AC-P0-3",
      "status": "pending"
    }
  ]
}
```

### 4.3 驱动执行流程

```
Spec 文档（含 [kimi-task]）
    ↓ parse_kimi_tasks.py
Task JSON（标准化任务描述）
    ↓ 编排脚本
├─→ RD Agent: 执行代码变更
├─→ QA Agent: 执行验收测试
├─→ CR Agent: 执行代码审查
└─→ PM Agent: 更新进度跟踪
```

---

## 5. 任务状态流转

任务状态由自动化系统维护，不存储在 Spec 文档中：

| 状态 | 说明 |
|------|------|
| `pending` | 待分配，未开始 |
| `in_progress` | RD Agent 已认领，执行中 |
| `review` | 代码已完成，待 CR 审查 |
| `testing` | CR 通过，待 QA 验收 |
| `done` | QA 验收通过，任务完成 |
| `blocked` | 被依赖任务阻塞 |
| `failed` | 执行失败，需人工介入 |

---

## 6. 约束与红线

- **[MUST]** 每个 `[kimi-task]` 必须关联至少一个 `Acceptance` ID（`AC-P0-X` 或 `AC-P1-X`）
- **[MUST]** `task_id` 全局唯一，格式为 `<模块缩写>-<三位数字>`
- **[MUST]** `Scope` 中的文件路径必须真实存在于代码库中
- **[NEVER]** 禁止在 `[kimi-task]` 中描述实现细节（如具体算法），实现细节应留在 `AGENTS.md`
- **[NEVER]** 禁止将 `[kimi-task]` 嵌入代码注释中

---

## 7. 示例：帧同步美妆系统任务集

```markdown
## 6. 版本规划（含 [kimi-task]）

### Phase 1：基础设施（1~2 周）

#### FrameId 体系 [kimi-task:fsm-001]
- **Assignee**: RD
- **Scope**: `beauty-engine/src/main/java/com/picme/beauty/api/FrameId.kt`
- **Expected Change**:
  1. 创建 `@JvmInline value class FrameId(val value: Long)`
  2. 实现 `AtomicLong` 计数器，`next()` 方法
  3. 定义 `INVALID = FrameId(0L)`
- **EstimatedEffort**: 4h
- **Priority**: P0
- **Acceptance**: AC-P0-1

#### FrameSyncManager 骨架 [kimi-task:fsm-002]
- **Assignee**: RD
- **Scope**: `beauty-engine/src/main/java/com/picme/beauty/internal/framesync/FrameSyncManager.kt`
- **Expected Change**:
  1. 创建 `FrameSyncManager` 单例类
  2. 实现 `ResultStore`（`ConcurrentHashMap<FrameId, DetectionResult>`）
  3. 实现 `MatchEngine`：精确匹配 → 历史回退 → 预测补偿 → 缺失隐藏
  4. 实现 `query(currentFrameId)` 公共 API
- **DependsOn**: fsm-001
- **EstimatedEffort**: 2d
- **Priority**: P0
- **Acceptance**: AC-P0-1, AC-P0-2

#### DetectionQueue 改造 [kimi-task:fsm-003]
- **Assignee**: RD
- **Scope**: `beauty-engine/src/main/java/com/picme/beauty/internal/framesync/DetectionQueue.kt`
- **Expected Change**:
  1. 创建 `DetectionQueue` 类，深度限制 2，超时 200ms
  2. 改造人脸检测线程为消费队列模式
  3. 检测结果携带 `FrameId` 存入 `FrameSyncManager`
- **DependsOn**: fsm-002
- **EstimatedEffort**: 1d
- **Priority**: P0
- **Acceptance**: AC-P0-1

### Phase 2：时序对齐与严格缺失（1~2 周）

#### 渲染管线集成 [kimi-task:fsm-004]
- **Assignee**: RD
- **Scope**: `beauty-engine/src/main/java/com/picme/beauty/egl/CameraPreviewRenderer.kt`, `beauty-engine/src/main/java/com/picme/beauty/egl/BeautyRenderer.kt`
- **Expected Change**:
  1. `CameraPreviewRenderer` 渲染循环中调用 `FrameSyncManager.query(currentFrameId)`
  2. `BeautyRenderer` 新增 `updateSyncedFacePoints106()` + `setHasFace()`
  3. `FaceMakeupPass` 新增 `updateFaceLandmarksSynced()` 入口
- **DependsOn**: fsm-002, fsm-003
- **EstimatedEffort**: 2d
- **Priority**: P0
- **Acceptance**: AC-P0-2, AC-P0-3

#### 严格缺失处理 [kimi-task:fsm-005]
- **Assignee**: RD
- **Scope**: `beauty-engine/src/main/java/com/picme/beauty/internal/framesync/FrameSyncManager.kt`, `beauty-engine/src/main/java/com/picme/beauty/egl/CameraPreviewRenderer.kt`
- **Expected Change**:
  1. `FrameSyncConfig` 新增 `missingThresholdFrames: Int = 3`
  2. `query()` 严格模式：帧差 > 阈值时返回 `MISSING`
  3. `applySyncResultToRenderer()`：`MISSING` 时 `uHasFace = 0`
- **DependsOn**: fsm-004
- **EstimatedEffort**: 1d
- **Priority**: P0
- **Acceptance**: AC-P0-3

#### 调试指标接入 [kimi-task:fsm-006]
- **Assignee**: RD
- **Scope**: `beauty-engine/src/main/java/com/picme/beauty/api/BeautyPerfStats.kt`, `app/src/main/java/com/picme/features/camera/debug/PerfOverlay.kt`
- **Expected Change**:
  1. `BeautyPerfStats` 增加 `detectionLatencyMs` / `syncStatus` / `predictedOffsetPx` / `framesSinceDetection`
  2. 调试浮层展示新增指标
- **DependsOn**: fsm-004
- **EstimatedEffort**: 1d
- **Priority**: P1
- **Acceptance**: AC-P1-2

### Phase 3：预测补偿与录制专项（1~2 周）

#### MotionTracker 速度外推 [kimi-task:fsm-007]
- **Assignee**: RD
- **Scope**: `beauty-engine/src/main/java/com/picme/beauty/internal/framesync/MotionTracker.kt`
- **Expected Change**:
  1. 创建 `MotionTracker` 类，保留最近 3 帧历史
  2. 实现 `predict(fromFrameId, toFrameId, maxRatio)` 速度外推
  3. 位移约束：不超过上一帧位移的 150%
- **DependsOn**: fsm-002
- **EstimatedEffort**: 2d
- **Priority**: P1
- **Acceptance**: AC-P1-1

#### 录制场景帧同步验证 [kimi-task:fsm-008]
- **Assignee**: QA
- **Scope**: `app/src/androidTest/java/com/picme/camera/VideoRecordingSyncTest.kt`
- **Expected Change**:
  1. 编写录制快转头测试用例
  2. 编写录制人脸出画入画测试用例
  3. 逐帧分析录制视频，验证妆容偏差
- **DependsOn**: fsm-004, fsm-005
- **EstimatedEffort**: 2d
- **Priority**: P0
- **Acceptance**: AC-P0-5
```

---

## 8. 更新历史

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| 1.0 | 2026-05-14 | 初版，定义 [kimi-task] 标记规范 | PM |
