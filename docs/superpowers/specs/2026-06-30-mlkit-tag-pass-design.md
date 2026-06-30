# ML Kit Image Labeler 作为独立 Tag Pass 设计方案

> 将已集成的 ML Kit Image Labeler 从旧 `MediaIndexingWorker` 路径迁移为新的独立 Pass，与 Qwen 3-Pass 标签系统解耦，支持独立增量/全量扫描和搜索召回。
>
> **关联文档**：
> - 相册自动 Tag 生成技术方案：`docs/03-TECHNICAL-SPECS/AUTO_TAG_GENERATION_SPEC.md`
> - TAG 扫描状态机：`docs/03-TECHNICAL-SPECS/TAG_SCAN_STATE_MACHINE.md`
> - 顶层 AGENTS.md：`/Users/guoshuai/AndroidStudioProjects/langchain4android/AGENTS.md`

---

## 1. 背景与问题

### 1.1 现状

项目已经在 `app/build.gradle.kts` 中引用了 ML Kit Image Labeler（`com.google.mlkit:image-labeling:17.0.9`），并通过 `MetadataExtractor` + `MediaIndexingWorker` 路径提取英文标签（如 `"Outdoor"`、`"Food"`），写入：

- `MediaEntity.labels`（JSON 数组字符串，例如 `["Outdoor","Food"]`）
- `tags` + `media_tag_cross_ref` 规范化索引表

与此同时，新的 3-Pass 标签系统 `TagGenerationScheduler` / `TagScanOrchestrator` 使用 Qwen3.5-2B 生成结构化中文标签，也写入同一个 `labels` 字段，格式为 JSON 对象：

```json
{
  "face": {"count": 2, "selfie": false, "groupPhoto": true, "personIds": [1, 2]},
  "scene": "户外",
  "activity": "聚会",
  "objects": ["食物", "桌子"],
  "tags": ["聚餐", "餐厅", "朋友"],
  "qwenSummary": "朋友们在餐厅聚餐的照片"
}
```

### 1.2 问题

两套系统共用 `labels` 字段，导致：

1. **格式冲突**：`labels` 一会儿是 JSON 数组，一会儿是 JSON 对象。
2. **互相覆盖**：旧的 `MediaIndexingWorker` 执行后会把 Qwen 的 JSON 对象覆盖为英文数组；Qwen Pass 执行后又会把 ML Kit 结果覆盖为中文对象。
3. **搜索不稳定**：`MediaSearchEngine` 对 `labels` 做 `LIKE` 匹配，命中结果取决于最近一次写入的是哪种格式。

### 1.3 目标

- 保留 ML Kit Image Labeler 作为标签来源，但写入**独立字段** `mlKitLabels`。
- 将 ML Kit 标签提取提升为新 3-Pass 系统中的**独立 Pass**：`TagScanPass.ML_KIT_TAGGING`。
- 提供**独立控制入口**，支持增量扫描和全量重扫。
- 搜索时同时召回 Qwen 中文标签和 ML Kit 英文标签。
- 不破坏现有 3-Pass（Face / DBSCAN / Qwen）行为。

---

## 2. 数据模型变更

### 2.1 MediaEntity

在 `app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt` 中新增字段：

```kotlin
@Entity(tableName = "media_assets")
data class MediaEntity(
    // ... 现有字段保持不变 ...

    /** ML Kit Image Labeler 输出的英文标签（JSON 数组），与 Qwen 的 labels 字段完全独立 */
    val mlKitLabels: String? = null,

    // ... 其他字段保持不变 ...
)
```

- 类型：`String?`
- 格式：`["Outdoor","Food","Plant"]`
- 保留 Qwen 的 `labels` 字段不变。

### 2.2 Room 迁移

在 `AppDatabase` 中升级数据库版本，并添加 migration：

```kotlin
val MIGRATION_X_X = object : Migration(X, X + 1) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE media_assets ADD COLUMN ml_kit_labels TEXT")
    }
}
```

### 2.3 TagScanPass

在 `app/src/main/java/com/mamba/picme/data/local/entity/TagScanTaskEntity.kt` 中扩展枚举：

```kotlin
enum class TagScanPass {
    FACE_DETECTION,
    DBSCAN,
    QWEN_TAGGING,
    MOBILE_CLIP_ENCODING,
    /** ML Kit Image Labeler 快速英文标签提取 */
    ML_KIT_TAGGING
}
```

### 2.4 TagCategory

在 `app/src/main/java/com/mamba/picme/domain/tag/TagCategory.kt` 中新增类别：

```kotlin
enum class TagCategory {
    FACE,
    SCENE,
    ACTIVITY,
    OBJECTS,
    TAGS,
    SUMMARY,
    /** ML Kit Image Labeler 输出的英文标签 */
    ML_KIT_LABELS;

    companion object {
        val ALL: Set<TagCategory> = entries.toSet()

        fun toPasses(categories: Set<TagCategory>): List<TagScanPass> {
            val passes = mutableListOf<TagScanPass>()
            if (categories.contains(FACE)) {
                passes += TagScanPass.FACE_DETECTION
                passes += TagScanPass.DBSCAN
            }
            if (categories.contains(ML_KIT_LABELS)) {
                passes += TagScanPass.ML_KIT_TAGGING
            }
            if (categories.any { it in setOf(SCENE, ACTIVITY, OBJECTS, TAGS, SUMMARY) }) {
                passes += TagScanPass.QWEN_TAGGING
            }
            return passes.distinct()
        }
    }
}
```

---

## 3. 架构设计

### 3.1 新增组件

#### MlKitTagExtractor

位置：`app/src/main/java/com/mamba/picme/domain/tag/MlKitTagExtractor.kt`

职责：
- 封装 ML Kit `ImageLabeling` 客户端生命周期（创建 / 关闭）
- 从 URI 加载 `InputImage`
- 执行标注并过滤/排序结果
- 错误处理（模型未下载、超时、异常）

签名：

```kotlin
class MlKitTagExtractor(
    private val context: Context,
    private val confidenceThreshold: Float = 0.5f,
    private val maxLabels: Int = 5
) {
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    /** 从 URI 提取 ML Kit 英文标签 */
    suspend fun extract(uri: String): List<String>

    /** 从 InputImage 提取 ML Kit 英文标签（供复用场景） */
    fun extract(inputImage: InputImage): List<String>

    fun close()
}
```

输出示例：`["Outdoor","Food","Plant","Sky","People"]`

### 3.2 扩展已有组件

#### TagGenerationPipeline

新增方法：

```kotlin
/** 提取 ML Kit Image Labeler 英文标签 */
suspend fun extractMlKitLabels(uri: String): List<String>
```

内部复用 `MlKitTagExtractor`。

#### TagGenerationScheduler

新增原子任务：

```kotlin
/** [原子任务] ML Kit 英文标签提取 */
suspend fun executeMlKitTagging(mediaId: Long)
```

逻辑：
1. 查询 `MediaEntity`
2. 调用 `pipeline.extractMlKitLabels(entity.uri)`
3. 将结果序列化为 JSON 数组
4. 调用 `dao.updateMlKitLabels(entity.id, labelsJson)`
5. 节流 `getThrottleMs()`

#### TagScanOrchestrator

扩展点：

1. **`TagScanPass.toPassNumber()`**：新增 `ML_KIT_TAGGING → "5"`
2. **`createTasks()`**：当 passes 包含 `ML_KIT_TAGGING` 时，为每张媒体创建任务
3. **`createTasksForSinglePass()`**：处理 `ML_KIT_TAGGING` 单 Pass 任务
4. **`executeTask()`**：switch 分支增加 `TagScanPass.ML_KIT_TAGGING -> scheduler.executeMlKitTagging(task.mediaId)`
5. **`isPassMissing()`**：新增 `TagScanPass.ML_KIT_TAGGING -> entity.mlKitLabels.isNullOrEmpty()`
6. **`getDbStats()`**：新增 `remainingForMlKit` 统计
7. **`maybeUpdateMediaScanRecord()`**：新 Pass 完成后更新 `lastTagScanPasses`
8. **`schedulePass()`**：全量模式时调用 `db.mediaDao().resetAllMlKitLabels()`

#### MediaDao

新增/修改查询：

```kotlin
/** 更新 ML Kit 标签 */
@Query("UPDATE media_assets SET ml_kit_labels = :labels WHERE id = :mediaId")
suspend fun updateMlKitLabels(mediaId: Long, labels: String)

/** 重置所有 ML Kit 标签 */
@Query("UPDATE media_assets SET ml_kit_labels = NULL")
suspend fun resetAllMlKitLabels()

/** 按 ML Kit 标签搜索 */
@Query("SELECT * FROM media_assets WHERE ml_kit_labels LIKE '%' || :label || '%' ORDER BY captureDate DESC")
suspend fun searchByMlKitLabel(label: String): List<MediaEntity>

/** 未生成 ML Kit 标签的媒体数量 */
@Query("SELECT COUNT(*) FROM media_assets WHERE ml_kit_labels IS NULL OR ml_kit_labels = ''")
suspend fun getUnlabeledMlKitMediaCount(): Int
```

#### MediaSearchEngine

在 `searchByCandidate()` 中同时搜索 `mlKitLabels`：

```kotlin
private suspend fun searchByCandidate(
    candidate: String,
    resultMap: MutableMap<Long, MediaAsset>
) {
    // ... 现有 tagDao / ocrWordDao / labels / ocr / fileName 搜索 ...

    val mlKitResults = mediaDao.searchByMlKitLabel(candidate)
    mlKitResults.forEach { resultMap[it.id] = it.toDomain() }
}
```

### 3.3 UI 变更

#### TagGenerationControlScreen

在现有 Pass 控制区增加「ML Kit 标签」卡片/按钮：

- **增量扫描**：仅处理尚未生成 `mlKitLabels` 的照片
- **全量重扫**：清空 `mlKitLabels` 后重新扫描
- 复用现有进度显示和取消逻辑

调用方式：

```kotlin
orchestrator.schedulePass(
    pass = TagScanPass.ML_KIT_TAGGING,
    mode = ScanMode.INCREMENTAL // 或 ScanMode.FULL
)
```

---

## 4. 数据流

### 4.1 增量扫描

```
用户点击「ML Kit 标签 - 增量扫描」
    ↓
TagScanOrchestrator.schedulePass(ML_KIT_TAGGING, INCREMENTAL)
    ↓
过滤 mlKitLabels IS NULL OR mlKitLabels = '' 的媒体 ID
    ↓
创建 TagScanTaskEntity(pass=ML_KIT_TAGGING) 任务队列
    ↓
runSession() 轮询任务
    ↓
TagGenerationScheduler.executeMlKitTagging(mediaId)
    ↓
TagGenerationPipeline.extractMlKitLabels(uri)
    ↓
MlKitTagExtractor.extract(uri)
    ↓
ImageLabeling.process(InputImage)
    ↓
序列化为 JSON 数组 → MediaDao.updateMlKitLabels(mediaId, labelsJson)
    ↓
更新 lastTagScanPasses 中的 "5"
```

### 4.2 全量重扫

```
用户点击「ML Kit 标签 - 全量重扫」
    ↓
TagScanOrchestrator.schedulePass(ML_KIT_TAGGING, FULL)
    ↓
MediaDao.resetAllMlKitLabels()
    ↓
为所有媒体创建 ML_KIT_TAGGING 任务
    ↓
... 同增量扫描后续流程 ...
```

### 4.3 搜索召回

```
用户搜索 "food"
    ↓
MediaSearchEngine.searchByCandidate("food")
    ↓
同时查询：
  - tagDao.searchByExactTag("food")
  - mediaDao.searchByLabel("food")      // Qwen labels
  - mediaDao.searchByMlKitLabel("food") // ML Kit labels
  - mediaDao.searchByOcrText("food")
  - mediaDao.searchByFileName("food")
    ↓
合并去重 → 融合排序 → 返回结果
```

---

## 5. 错误处理

| 错误场景 | 处理策略 |
|----------|----------|
| ML Kit 模型未下载 | 与 `MetadataExtractor` 一致：捕获 `MlKitException`，重试 3 次后标记该任务 FAILED，后台下载完成后下次扫描自动恢复 |
| 单张图片解码失败 | 跳过该张，记录 WARNING，不影响队列其余任务 |
| 标注返回空结果 | 写入空数组 `[]` 或保持 `null`，避免重复扫描（取决于增量判断逻辑） |
| 任务取消 | 复用 `currentCoroutineContext().ensureActive()`，丢弃当前结果 |

---

## 6. 性能预算

| 指标 | 预算 | 说明 |
|------|------|------|
| 单张 ML Kit 标注 | < 200ms | 纯端侧 TFLite 推理，无需 LLM 加载 |
| 100 张全量扫描 | < 30s | 串行执行，每张含 IO + 推理 + 节流 |
| 内存占用 | 可忽略 | 无需加载大模型，仅 InputImage 和 Bitmap |
| 首次模型下载 | 依赖 Play Services | 失败时自动重试，不影响其他 Pass |

---

## 7. 验收标准

- [ ] `MediaEntity` 新增 `mlKitLabels` 字段，Room 迁移成功
- [ ] `TagScanPass.ML_KIT_TAGGING` 和 `TagCategory.ML_KIT_LABELS` 定义完成
- [ ] `MlKitTagExtractor` 可正确从 URI 提取英文标签
- [ ] `TagScanOrchestrator` 支持独立调度 ML Kit Pass 的增量/全量扫描
- [ ] 全量扫描可清空 `mlKitLabels` 后重跑
- [ ] 增量扫描仅处理无 `mlKitLabels` 的媒体
- [ ] `TagGenerationControlScreen` 增加 ML Kit Pass 控制入口
- [ ] `MediaSearchEngine` 同时搜索 `labels` 和 `mlKitLabels`
- [ ] Qwen 的 `labels` 字段不会被 ML Kit Pass 覆盖
- [ ] 现有 Face / DBSCAN / Qwen Pass 行为不变
- [ ] 至少一个单元测试覆盖 `MlKitTagExtractor` 的标签过滤逻辑

---

## 8. 风险与 Mitigation

| 风险 | Mitigation |
|------|-----------|
| 新增 Room 列导致旧版本崩溃 | 提供 Migration 并在安装后验证 |
| `TagScanOrchestrator` 状态机变复杂 | 严格遵循已有 switch 分支模式，新增 case 不修改旧逻辑 |
| ML Kit 英文标签与中文搜索词不匹配 | 保留在 `mlKitLabels` 独立字段，`MediaSearchEngine` 通过英文查询召回；中文查询仍走 Qwen 标签 + TagTranslator |
| UI 按钮过多 | 将 ML Kit 控制折叠到「更多 Pass」或作为单独卡片 |

---

## 9. 后续可选优化

- 为 ML Kit 标签增加中文翻译层，使中文搜索也能召回英文标签（低优先级，Qwen 中文标签已覆盖主要场景）。
- 将 ML Kit 标签作为 Qwen Prompt 的候选输入，提升 Qwen 标签一致性（需评估是否破坏"独立字段"原则）。

---

> **维护者**：RD Agent
> **状态**：待实施
> **最后更新**：2026-06-30
