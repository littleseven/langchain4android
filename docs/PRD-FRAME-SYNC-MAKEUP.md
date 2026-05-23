# PRD：帧同步美妆系统（Frame-Sync Makeup System）— 解决妆容甩飞问题

**版本**：2.0  
**状态**：需求冻结，技术评审通过，待 RD 排期  
**优先级**：P0（阻塞妆容精细化与视频录制质量）  
**负责人**：PM / RD / QA  
**最后更新**：2026-05-11  
**关联文档**：`TECH-SPEC-FRAME-SYNC-MAKEUP.md`、`BIG_BEAUTY_TECH_SPEC.md`、`beauty-engine/AGENTS.md`

---

## 1. 背景（Why）

### 1.1 问题定义：妆容甩飞（Makeup Detachment / Flying Makeup）

"妆容甩飞"是 PicMe 美颜体验的首要痛点，用户侧表现为**妆容仿佛"粘"在屏幕上而非人脸上**，在人脸移动时产生明显的分离感、滞后感和跳变感。当前大美丽引擎虽已部署**双缓冲 + 顶点插值**缓解策略，但未能根治。

妆容甩飞的三种典型表现：

| 表现类型 | 用户感知 | 技术根因 |
|---------|---------|---------|
| **滞后甩飞 (Lag)** | 快速转头/移动时，唇色/腮红明显慢半拍，约 50~100ms 后才跟上人脸 | 人脸检测 ~10fps，渲染 30~60fps，渲染帧使用"过去某时刻"的人脸关键点 |
| **悬空残留 (Hover)** | 人脸离开画面后，妆容仍停留在最后一帧位置，持续数帧才消失 | 无严格"检测命中才渲染"机制，缺乏缺失帧的严格处理 |
| **录制跳变 (Jitter)** | 视频录制中妆容更新不连贯，出现 10fps 级别的顿挫跳变 | 检测帧率与录制帧率未绑定，每帧录制画面独立查询检测状态 |

**根本原因**：人脸检测流与相机预览/录制帧流在**时间维度上未对齐**，属于异步松散耦合架构的系统性缺陷。

| 维度 | 现状 | 问题 |
|------|------|------|
| **检测帧率** | InsightFace/MediaPipe ~10fps | 远低于渲染帧率 30~60fps |
| **时间对齐** | 无帧 ID / 无时间戳匹配 | 渲染帧使用的是"过去某时刻"的人脸关键点 |
| **空间补偿** | 线性插值（基于时间进度） | 仅缓解突变，无法消除系统性滞后 |
| **缺失感知** | 人脸离开画面后妆容仍残留 | 无严格"检测命中才渲染"机制 |

### 1.2 用户体验影响（按场景）

#### 预览场景（实时取景）
- **快速转头/移动**：唇色/腮红明显滞后于实际人脸位置（滞后 ~50~100ms），用户感觉"妆容粘屏幕不跟脸"
- **人脸出画入画**：人脸离开画面后，妆容仍停留在最后一帧位置，持续 3~5 帧（~50~80ms）才消失，产生"悬空妆容"的不自然感
- **回画瞬间**：人脸重新入画后，妆容不会立即出现，而是等下一次检测完成后才突兀出现

#### 录制场景（视频拍摄）— **此场景影响最严重**
- **帧级跳变**：录制帧率稳定 30fps，但妆容更新仅 ~10fps，视频中每 3 帧只有 1 帧更新妆容位置，产生肉眼可见的顿挫跳变
- **甩飞放大**：录制画面可被反复回放，甩飞问题被放大；预览时不易察觉的 8px 偏差在录播中极为明显
- **不可修复**：一旦写入视频文件，妆容甩飞成为永久性内容缺陷，无法后期修正

**影响评估**：
- 预览场景：影响实时体验，但不留存
- **录制场景：影响内容质量，且永久留存，对创作者用户打击最大**

### 1.3 竞品对标与目标

| App | 方案 | 预览效果 | 录制效果 |
|-----|------|---------|---------|
| 轻颜相机 | 硬件同步 + 预测补偿 | 几乎零滞后 | 平滑无跳变 |
| BeautyCam | 严格帧绑定 + 插值 | 轻微延迟 | 基本平滑 |
| **当前 PicMe** | **双缓冲 + 时间插值** | **可感知滞后，快移明显** | **10fps 跳变，甩飞严重** |

**目标**：
- **预览场景**：快速移动（转头速度 > 90°/s）下，妆容与人脸位移偏差 < 8px @1080p
- **录制场景**：视频输出中妆容更新平滑连贯，无肉眼可见的跳变或甩飞（偏差 < 8px @1080p，帧间位移过渡自然）
- **整体对标**：达到 BeautyCam 同级水平，录制场景对标轻颜相机

---

## 2. 方案（What & How）

### 2.1 核心策略：帧 ID 绑定 + 时序队列 + 预测补偿 + 严格缺失

将"异步松散耦合"升级为"准同步帧匹配"，从根本上解决检测-渲染时间差导致的甩飞问题：

```
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│  CameraX     │      │  FrameSync   │      │   Render     │
│  预览/录制帧流 │─────▶│  时序对齐     │◀─────│   管线       │
│  (30~60fps)  │      │  (帧ID绑定)   │      │  (30~60fps)  │
└──────────────┘      └──────┬───────┘      └──────────────┘
                             │
┌──────────────┐             │
│  FaceDetect  │─────────────┘
│  人脸检测流   │   检测结果携带帧ID入队
│   (~10fps)   │
└──────────────┘
```

**关键设计原则**：
1. **每帧必绑**：每一帧相机输出（无论是预览还是录制）必须携带唯一 `FrameId`
2. **检测随帧**：人脸检测的输入和输出都必须绑定到具体的 `FrameId`，而非"检测最新帧"
3. **渲染必查**：渲染每一帧前必须查询对应 `FrameId` 的检测结果（精确匹配 or 预测补偿）
4. **无检测即隐藏**：当某 `FrameId` 及历史 N 帧内均无检测结果时，强制隐藏妆容，杜绝悬空残留

### 2.2 功能需求列表（Spec 化）

#### FR-1：全局帧 ID 体系（P0）

| 字段 | 定义 |
|------|------|
| **FrameId** | 单调递增的 `Long`，从 1 开始，全局唯一 |
| **生成时机** | `SurfaceTexture.updateTexImage()` 时刻，与相机帧严格绑定 |
| **传递链路** | CameraX → SurfaceTexture → DetectionQueue → FrameSyncManager → Renderer |
| **生命周期** | 随单帧渲染结束而废弃，不持久化 |

**约束**：
- [ ] 所有与人脸相关的数据结构必须携带 `FrameId`
- [ ] 预览和录制共用同一套 `FrameId` 序列，确保行为一致
- [ ] `FrameId` 生成必须线程安全（使用 `AtomicLong`）

#### FR-2：人脸检测帧绑定（P0）

| 字段 | 定义 |
|------|------|
| **检测输入** | 必须是带 `FrameId` 的帧数据（`Bitmap` + `FrameId` + 旋转/镜像信息） |
| **检测输出** | 检测结果必须携带相同的 `FrameId` |
| **队列深度** | 默认 2 帧，防止检测线程积压 |
| **超时策略** | 任务入队后 > 200ms 未消费则丢弃，避免检测滞后太久 |

**约束**：
- [ ] 检测线程从"异步分析最新帧"改为"消费带 FrameId 的帧队列"
- [ ] 队列满时丢弃最旧任务（FIFO 淘汰）
- [ ] 超时丢弃的任务必须回收 `Bitmap`，避免内存泄漏

#### FR-3：时序对齐队列（P0）

渲染线程在渲染每帧前，根据当前 `FrameId` 查询对应的人脸检测结果，按优先级匹配：

| 优先级 | 匹配模式 | 条件 | 输出 |
|-------|---------|------|------|
| 1 | **精确匹配 (EXACT_MATCH)** | 当前 `FrameId` 正好有检测结果 | 直接使用检测结果 |
| 2 | **历史回退 (HISTORICAL_FALLBACK)** | 无精确匹配，取 ≤ 当前 `FrameId` 的最新结果，且帧差 ≤ 阈值 | 使用最近历史结果，无预测 |
| 3 | **预测补偿 (PREDICTED)** | 无精确匹配，帧差 > 阈值但 ≤ 最大预测帧数 | 基于运动轨迹预测补偿 |
| 4 | **缺失隐藏 (MISSING)** | 当前帧及历史 N 帧内均无结果 | 隐藏妆容（`uHasFace = 0`） |

**硬性约束**：
- [ ] **禁止借用未来帧的检测结果**（避免反向时间线，防止妆容超前）
- [ ] 匹配算法时间复杂度必须为 O(1) ~ O(N)，N ≤ 10，不得阻塞渲染线程

#### FR-4：运动预测补偿（P1）

| 字段 | 定义 |
|------|------|
| **触发条件** | 当前渲染帧无精确匹配，且最近检测结果在可预测时间窗口内 |
| **预测算法** | Phase 1：简单速度外推（Velocity Extrapolation）；Phase 2：Kalman Filter |
| **输入数据** | 最近 3 帧检测结果的 106 点 landmarks |
| **输出数据** | 预测后的 106 点 landmarks |

**约束**：
- [ ] 预测位移幅度不超过上一帧实际位移的 150%，避免过度外推算崩
- [ ] 预测计算耗时 < 0.5ms / 帧
- [ ] 预测失败时降级为历史回退或缺失隐藏
- [ ] 预测算法在 CPU 侧完成，不阻塞 GPU 渲染

#### FR-5：严格缺失处理（P0）— 解决悬空残留

| 字段 | 定义 |
|------|------|
| **缺失判定** | 当前帧及历史 N 帧内均无检测结果 |
| **默认行为** | **强制隐藏妆容**（`uHasFace = 0`），不渲染任何妆容 Pass |
| **阈值 N** | 默认 3 帧（~50ms@60fps），可配置 1~10 帧 |
| **降级行为** | 可选保留最后一帧位置（当前旧行为，用于 A/B 对比测试） |

**约束**：
- [ ] 人脸离开画面后，妆容必须在 N 帧内消失，杜绝"悬空妆容"
- [ ] 切换阈值必须可配置（开发者选项），便于调优
- [ ] 缺失隐藏到重新出现的过渡必须自然，不得突兀闪烁

#### FR-6：调试可观测性（P1）

调试浮层新增帧同步专属指标：

| 指标 | 说明 | 用途 |
|------|------|------|
| `detectionLatencyMs` | 检测滞后时间 = 当前帧时间 - 检测帧时间 | 评估检测链路延迟 |
| `syncStatus` | EXACT / HISTORICAL / PREDICTED / MISSING | 验证匹配策略分布 |
| `predictedOffsetPx` | 预测补偿的像素位移量 | 评估预测精度 |
| `framesSinceDetection` | 距上次检测到人脸的帧数 | 验证缺失隐藏触发时机 |

**约束**：
- [ ] 指标必须实时更新，每秒聚合一次
- [ ] 日志输出 `[FrameSync]` 前缀，便于 QA 抓取分析
- [ ] 录制场景下指标必须同步写入调试日志（不显示在 UI 上）

### 2.3 非功能性需求（NFR）

#### 性能指标

| 指标 | 目标值 | 最差容忍 | 测量方法 |
|------|--------|----------|---------|
| **检测-渲染对齐精度** | 偏差 < 8px @1080p | < 16px | 录制视频逐帧测量妆容中心与人脸中心距离 |
| **预测补偿耗时** | < 0.5ms / 帧 | < 1ms | Systrace / 自定义计时 |
| **帧 ID 队列内存占用** | < 50KB | < 200KB | Android Profiler Memory |
| **严格缺失处理切换延迟** | < 1 帧（16ms@60fps） | < 3 帧 | 高速摄像 / 帧计数 |
| **整体渲染帧率影响** | 无下降 | 下降 < 5% | `BeautyPerfStats.fps` 对比 |

#### 场景化指标（重点）

| 场景 | 指标 | 目标值 | 验收方法 |
|------|------|--------|---------|
| **预览-快转头** | 转头速度 > 90°/s 时妆容偏差 | < 16px @1080p | 人工体验 + 录屏逐帧测量 |
| **预览-人脸出画** | 妆容消失时间 | < 3 帧（~50ms@60fps） | 高速摄像 / 帧计数 |
| **录制-匀速移动** | 视频中妆容帧间位移平滑度 | 相邻帧妆容位移差 < 5px | 逐帧分析录制视频 |
| **录制-快转头** | 视频中妆容最大偏差 | < 8px @1080p | 逐帧分析录制视频 |
| **录制-人脸出画入画** | 视频中妆容消失/出现过渡 | 无突兀跳变，自然过渡 | 人工回放评估 |

---

## 3. 交互与体验规范

### 3.1 用户可见行为（按场景）

#### 预览场景
- **正常拍摄**：用户无感知，妆容自然贴合人脸
- **快速移动**：转头或左右移动时，妆容平滑跟随，无甩飞或滞后；用户不应感知到"妆容粘在屏幕上"
- **人脸出画**：人脸离开画面后，妆容在 1~2 帧内（< 33ms@60fps）消失，绝不出现"悬空妆容"
- **人脸入画**：检测到人脸的第 1 帧立即出现妆容，无淡入延迟或突兀闪现

#### 录制场景（重点）
- **录制全程**：视频中妆容更新平滑连贯，无 10fps 级别的跳变感
- **快转头录制**：转头过程中妆容始终贴合人脸，录制回放时不应出现明显的甩飞或滞后
- **人脸出画录制**：人脸离开画面后妆容迅速消失，不会出现"妆容留在空画面"的尴尬片段
- **人脸入画录制**：人脸重新入画后妆容立即恢复，无"先露脸后化妆"的延迟感

### 3.2 设置项（开发者模式）

在设置页"开发者选项"中新增：

| 设置项 | 选项 | 默认值 | 说明 |
|--------|------|--------|------|
| **帧同步模式** | 严格对齐 / 平滑优先 / 关闭 | 严格对齐 | 严格对齐=精确匹配+缺失隐藏；平滑优先=允许预测补偿；关闭=旧行为（用于A/B对比） |
| **预测算法** | 速度外推 / Kalman Filter | 速度外推 | Phase 1 仅实现速度外推；Kalman Filter 为 Phase 2 实验性 |
| **缺失阈值** | 1~10 帧 | 3 帧 | 人脸缺失 N 帧后强制隐藏妆容 |
| **录制帧同步** | 开启 / 关闭 | 开启 | 录制场景是否应用帧同步（独立开关用于问题定位） |

---

## 4. 验收标准（Acceptance Criteria）

所有验收条件采用 **Gherkin 风格**（Given-When-Then）书写，便于 QA Agent 直接转化为自动化测试用例。

---

### 4.1 P0 必达标（阻塞发布）

#### AC-P0-1：FrameId 全链路绑定
```gherkin
Feature: 全局帧 ID 体系
  Scenario: 每一帧渲染携带唯一 FrameId
    Given 相机预览流处于活跃状态
    When SurfaceTexture.updateTexImage() 被调用
    Then 该帧必须分配单调递增的 FrameId
    And FrameId 通过 FrameSyncBridge 共享给渲染线程
    And 人脸检测结果必须携带相同的 FrameId
    And 预览与录制共用同一 FrameId 序列
```
- **测试类型**：单元测试 + 集成测试
- **自动化入口**：`FrameSyncManagerTest`, `FrameIdTest`

#### AC-P0-2：时序对齐队列无阻塞
```gherkin
Feature: 时序对齐队列
  Scenario: 渲染线程查询人脸检测结果
    Given FrameSyncManager 已存储最近 10 帧检测结果
    When 渲染线程调用 query(currentFrameId)
    Then 返回时间复杂度为 O(1) ~ O(N)，N ≤ 10
    And 渲染线程不被阻塞
    And 无内存泄漏（结果存储自动 Trim）
```
- **测试类型**：单元测试 + 性能测试
- **自动化入口**：`FrameSyncManagerPerformanceTest`

#### AC-P0-3：严格缺失处理 — 解决悬空残留
```gherkin
Feature: 人脸出画后妆容隐藏
  Scenario: 人脸离开画面
    Given 用户开启美颜且人脸在画面中（uHasFace = 1）
    When 人脸离开画面且连续 N 帧（默认 3 帧）无检测结果
    Then uHasFace 在 ≤3 帧内置 0
    And FaceMakeupPass 跳过妆容渲染
    And 调试浮层 syncStatus 显示 MISSING
    And 不出现悬空妆容
  Scenario: 人脸重新入画
    Given 人脸已离开画面（uHasFace = 0）
    When 人脸重新进入画面且检测到人脸
    Then 第 1 帧立即显示妆容（uHasFace = 1）
    And 无淡入延迟或突兀闪烁
```
- **测试类型**：集成测试 + 人工体验测试
- **自动化入口**：`FrameSyncMissingTest`, `FaceMakeupPassTest`
- **Priority**: P0-1（阻塞发布）

#### AC-P0-4：预览快转头偏差
```gherkin
Feature: 预览场景妆容跟随
  Scenario: 快速转头（> 90°/s）
    Given 用户开启唇色/腮红且人脸在画面中
    When 用户以 > 90°/s 速度转头
    Then 妆容中心与人脸中心偏差 < 16px @1080p
    And 帧率差异（对比关闭帧同步）< 5%
```
- **测试类型**：性能测试 + 人工体验测试
- **测量方法**：高速摄像 / 帧计数 / `BeautyPerfStats.fps` 对比
- **Priority**: P0-2

#### AC-P0-5：录制场景帧同步强制启用
```gherkin
Feature: 录制场景妆容同步
  Scenario: 录制视频（30fps）
    Given 用户开启美颜并启动视频录制
    When 录制过程中人脸移动
    Then 录制链路复用同一 FrameSyncManager 实例
    And 录制帧与预览帧使用相同的 FrameSyncResult
    And 视频中妆容更新连贯，肉眼无 10fps 跳变感
  Scenario: 录制快转头
    Given 录制进行中且人脸在画面中
    When 用户以 > 90°/s 速度转头
    Then 视频中妆容最大偏差 < 16px @1080p
  Scenario: 录制人脸出画入画
    Given 录制进行中
    When 人脸离开画面后重新入画
    Then 出画后妆容在 3 帧内消失
    And 入画后第 1 帧立即显示妆容
```
- **测试类型**：集成测试 + 视频逐帧分析 + 人工回放评估
- **自动化入口**：`VideoRecordingSyncTest`
- **Priority**: P0-1（阻塞发布，录制场景影响最大）

---

### 4.2 P1 期望达标（体验优化）

#### AC-P1-1：预览 + 录制快转头偏差优化
```gherkin
Feature: 高精度妆容跟随
  Scenario: 快转头偏差 < 8px
    Given 同 AC-P0-4 / AC-P0-5 场景
    When 帧同步系统启用预测补偿（SMOOTH 模式）
    Then 预览 + 录制快转头偏差 < 8px @1080p
    And 预测补偿位移平滑无抖动
```
- **Priority**: P1-1

#### AC-P1-2：调试可观测性
```gherkin
Feature: 帧同步调试指标
  Scenario: 调试浮层展示帧同步指标
    Given 开发者选项中开启调试浮层
    When 相机预览活跃
    Then 浮层实时展示 detectionLatencyMs / syncStatus / predictedOffsetPx / framesSinceDetection
    And 指标每秒聚合更新一次
    And 日志输出 [FrameSync] 前缀便于 QA 抓取
```
- **Priority**: P1-2

#### AC-P1-3：录制匀速移动平滑度
```gherkin
Feature: 录制匀速移动妆容平滑
  Scenario: 相邻帧妆容位移差 < 5px
    Given 录制进行中且人脸匀速移动
    When 逐帧分析录制视频
    Then 相邻帧妆容中心位移差 < 5px @1080p
```
- **Priority**: P1-3

---

### 4.3 P2 优化项（长期）

| ID | 场景 | 目标 | 状态 |
|---|---|---|---|
| AC-P2-1 | Kalman Filter 预测 | 替换速度外推，提升预测精度 | 规划中 |
| AC-P2-2 | 检测线程解耦 | 检测帧率自适应提升 | 规划中 |
| AC-P2-3 | 录制检测频率提升 | 录制时动态提升检测频率至 ~15fps | 规划中 |

---

## 5. 风险与边界

### 5.1 技术风险

| 风险 | 等级 | 影响 | 缓解措施 |
|------|------|------|----------|
| 帧 ID 引入增加链路复杂度 | 中 | 开发周期延长，调试难度增加 | 封装为 `FrameSyncManager`，对上层透明；完善调试指标 |
| 检测线程队列积压导致延迟 | 中 | 检测滞后增加，甩飞加剧 | 队列深度限制 2 + 超时丢弃 200ms + 降采样 |
| 预测算法不稳定导致妆容抖动 | 低 | 快速移动时妆容抖动比甩飞更难接受 | 位移约束（≤150%）+ 可配置关闭预测 |
| 低端机性能开销 | 中 | 帧率下降 | 预测计算在 CPU 完成，量极轻；支持关闭帧同步（SyncMode.OFF） |
| 录制场景回退到旧行为 | 高 | 视频甩飞问题未解决 | **录制场景必须强制启用帧同步，不可关闭** |

### 5.2 场景兼容性

| 场景 | 要求 | 验证方式 |
|------|------|---------|
| **前置摄像头** | 帧同步 + 镜像处理正确 | 自拍快转头 + 录制测试 |
| **后置摄像头** | 帧同步正常工作 | 他拍移动 + 录制测试 |
| **视频录制** | **强制启用帧同步**，确保录制帧与预览帧行为一致 | 录制多场景视频逐帧分析 |
| **拍照后处理** | `PhotoProcessorImpl` 复用同一套帧同步逻辑（单帧退化为有无人脸判断） | 拍照效果对比测试 |
| **静态图编辑** | 复用帧同步逻辑（单帧场景） | 静态图美颜效果测试 |

### 5.3 对库化的影响

- 帧同步属于**横切能力**，应沉淀在 `beauty-engine` 公共层（`internal/framesync/`），不侵入 `egl/` 具体 Pass 实现
- `FaceMakeupPass` 只接收"已同步好"的顶点数据，不关心同步逻辑
- `FrameSyncManager` 对外提供 `FrameSyncConfig`，允许 App 层配置同步模式与阈值
- 为未来其他依赖人脸关键点的功能（如美体、背景虚化）提供统一同步基础设施

---

## 6. 版本规划（含 [kimi-task] 标记）

> 所有任务标记遵循 `docs/TASK_MARKUP_SPEC.md` 规范。自动化脚本可解析生成 Task JSON 直接驱动 Agent 执行。

---

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

---

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

---

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

#### A/B 测试：开启 vs 关闭帧同步 [kimi-task:fsm-009]
- **Assignee**: QA
- **Scope**: `docs/AB_TEST_FRAME_SYNC.md`（新建）
- **Expected Change**:
  1. 设计 A/B 测试方案（开启/关闭帧同步的录制视频对比）
  2. 招募内测用户收集主观评价
  3. 输出测试报告
- **DependsOn**: fsm-008
- **EstimatedEffort**: 3d
- **Priority**: P1
- **Acceptance**: AC-P0-5, AC-P1-1

---

### Phase 4：验收与文档同步（1 周）

#### 多机型真机测试 [kimi-task:fsm-010]
- **Assignee**: QA
- **Scope**: `docs/QA_REPORT_FRAME_SYNC.md`（新建）
- **Expected Change**:
  1. 覆盖低端机（骁龙 660）、中端机（骁龙 778G）、高端机（骁龙 8 Gen2）
  2. 录制场景专项验收（快转头 / 人脸出画入画 / 匀速移动）
  3. 输出 QA 验收报告
- **DependsOn**: fsm-008
- **EstimatedEffort**: 3d
- **Priority**: P0
- **Acceptance**: AC-P0-3, AC-P0-4, AC-P0-5, AC-P1-1, AC-P1-3

#### 文档同步 [kimi-task:fsm-011]
- **Assignee**: PM
- **Scope**: `PRODUCT.md`, `docs/FEATURES.md`, `beauty-engine/AGENTS.md`, `docs/BIG_BEAUTY_TECH_SPEC.md`
- **Expected Change**:
  1. 确认所有文档与实现一致
  2. 更新 `docs/TRACEABILITY_MATRIX.md`
  3. 关闭 [kimi-task] 标记，更新状态为 done
- **DependsOn**: fsm-010
- **EstimatedEffort**: 1d
- **Priority**: P0
- **Acceptance**: —（流程任务）

---

## 7. 相关文档

| 文档 | 说明 |
|------|------|
| `docs/TECH-SPEC-FRAME-SYNC-MAKEUP.md` | 帧同步美妆系统技术实现文档 |
| `docs/BIG_BEAUTY_TECH_SPEC.md` | 大美丽引擎渲染链路、容灾回退与观测指标 |
| `docs/FEATURES.md` Section 1.3 | 产品交互规范（美颜系统体验规则）|
| `beauty-engine/AGENTS.md` | beauty-engine 模块实现约束与检查清单 |
| **`docs/NFR_SPEC.md`** | **非功能性需求量化指标（性能/稳定性/隐私）** |
| **`docs/TASK_MARKUP_SPEC.md`** | **`[kimi-task]` 标记规范与自动化解析规则** |
| **`docs/DEVELOPMENT.md`** | **双螺旋演进工作流、反向链接规范、CI 检查规则** |
