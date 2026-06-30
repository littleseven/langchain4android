# ML Kit Image Labeler 独立 Tag Pass 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已集成的 ML Kit Image Labeler 迁移为独立的 Tag Pass，新增 `mlKitLabels` 字段，支持独立增量/全量扫描和搜索召回，且不影响现有 Qwen 3-Pass 系统。

**Architecture:** 在现有 `TagScanOrchestrator` 任务队列中新增 `ML_KIT_TAGGING` Pass，由 `MlKitTagExtractor` 封装 ML Kit 推理，结果写入 `MediaEntity.mlKitLabels`；`TagGenerationControlScreen` 增加独立控制入口；`MediaSearchEngine` 同时搜索 Qwen 标签和 ML Kit 英文标签。

**Tech Stack:** Kotlin, Android Room, ML Kit Image Labeling, Jetpack Compose, JUnit4

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt` | 修改 | 新增 `mlKitLabels` 字段 |
| `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt` | 修改 | Room 版本升级到 6，新增 `MIGRATION_5_6` |
| `app/src/main/java/com/mamba/picme/data/local/entity/TagScanTaskEntity.kt` | 修改 | `TagScanPass` 新增 `ML_KIT_TAGGING` |
| `app/src/main/java/com/mamba/picme/domain/tag/TagCategory.kt` | 修改 | `TagCategory` 新增 `ML_KIT_LABELS` |
| `app/src/main/java/com/mamba/picme/domain/tag/MlKitTagExtractor.kt` | 创建 | 封装 ML Kit Image Labeler 提取逻辑 |
| `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt` | 修改 | 新增 `extractMlKitLabels(uri)` |
| `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt` | 修改 | 新增 `executeMlKitTagging(mediaId)` |
| `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt` | 修改 | 新增 ML Kit 标签相关查询 |
| `app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt` | 修改 | 支持 `ML_KIT_TAGGING` 调度、统计、断点续扫 |
| `app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt` | 修改 | 新增 ML Kit Pass 的 Intent Action 与处理 |
| `app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt` | 修改 | 新增 ML Kit Pass 控制卡片 |
| `app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt` | 修改 | 搜索同时覆盖 `mlKitLabels` |
| `app/src/test/java/com/mamba/picme/domain/tag/MlKitTagExtractorTest.kt` | 创建 | 测试标签过滤与序列化逻辑 |

---

## Task 1: 数据模型与数据库迁移

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/entity/TagScanTaskEntity.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagCategory.kt`

- [ ] **Step 1.1: MediaEntity 新增 `mlKitLabels` 字段**

在 `MediaEntity` 中 `labels` 字段后新增：

```kotlin
/** ML Kit Image Labeler 输出的英文标签（JSON 数组），与 Qwen 的 labels 字段完全独立 */
val mlKitLabels: String? = null,
```

完整字段片段（替换旧 `labels` 附近）：

```kotlin
// 元数据索引字段（Phase 1 自然语言搜索）
val labels: String? = null,           // JSON 数组：["猫","户外","食物"]
/** ML Kit Image Labeler 输出的英文标签（JSON 数组），与 Qwen 的 labels 字段完全独立 */
val mlKitLabels: String? = null,      // JSON 数组：["Outdoor","Food"]
val ocrText: String? = null,          // OCR 提取的文字
```

- [ ] **Step 1.2: TagScanPass 新增 `ML_KIT_TAGGING`**

修改 `TagScanTaskEntity.kt` 中的枚举：

```kotlin
enum class TagScanPass {
    /** Pass 1: 人脸检测 + 人脸 Embedding + MobileCLIP 语义编码（语义编码已内联合并） */
    FACE_DETECTION,
    /** Pass 2: 全局 DBSCAN 聚类 */
    DBSCAN,
    /** Pass 3: Qwen 图像理解标签生成 */
    QWEN_TAGGING,
    /**
     * MobileCLIP 语义编码（保留以兼容历史任务/单独重编码场景）。
     * 常规扫描已将该阶段内联合并到 [FACE_DETECTION]。
     */
    MOBILE_CLIP_ENCODING,
    /** ML Kit Image Labeler 快速英文标签提取 */
    ML_KIT_TAGGING
}
```

- [ ] **Step 1.3: TagCategory 新增 `ML_KIT_LABELS` 并映射到 Pass**

修改 `TagCategory.kt`：

```kotlin
enum class TagCategory {
    /** 人脸相关信息：count / selfie / groupPhoto / personIds */
    FACE,
    /** 场景 */
    SCENE,
    /** 活动 */
    ACTIVITY,
    /** 物体列表 */
    OBJECTS,
    /** 受控词表标签 */
    TAGS,
    /** 一句话摘要 */
    SUMMARY,
    /** ML Kit Image Labeler 输出的英文标签 */
    ML_KIT_LABELS;

    companion object {
        /** 全部类别 */
        val ALL: Set<TagCategory> = entries.toSet()

        /**
         * 将类别集合映射为需要执行的 Pass 阶段
         */
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

- [ ] **Step 1.4: AppDatabase 升级到版本 6 并添加 Migration**

修改 `AppDatabase.kt`：

1. `version = 5` 改为 `version = 6`
2. `.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)` 改为 `.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)`
3. 新增 migration：

```kotlin
/**
 * Migration 5 → 6：新增 media_assets.ml_kit_labels 字段
 */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `media_assets` ADD COLUMN `ml_kit_labels` TEXT"
        )
    }
}
```

- [ ] **Step 1.5: 编译验证数据模型**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

## Task 2: 新增 MlKitTagExtractor

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/tag/MlKitTagExtractor.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/tag/MlKitTagExtractorTest.kt`

- [ ] **Step 2.1: 编写 MlKitTagExtractor 测试**

创建 `app/src/test/java/com/mamba/picme/domain/tag/MlKitTagExtractorTest.kt`：

```kotlin
package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitTagExtractorTest {

    @Test
    fun `filterLabels keeps only labels above confidence threshold and sorts by confidence`() {
        val raw = listOf(
            "Food" to 0.82f,
            "Plant" to 0.91f,
            "Outdoor" to 0.47f,
            "Sky" to 0.60f,
            "Person" to 0.55f,
            "Building" to 0.30f,
            "Car" to 0.78f
        )

        val result = MlKitTagExtractor.filterLabels(raw, confidenceThreshold = 0.5f, maxLabels = 5)

        assertEquals(listOf("Plant", "Food", "Car", "Sky", "Person"), result)
    }

    @Test
    fun `filterLabels returns empty list when all below threshold`() {
        val raw = listOf("A" to 0.1f, "B" to 0.2f)
        val result = MlKitTagExtractor.filterLabels(raw, confidenceThreshold = 0.5f, maxLabels = 5)
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `toJsonArray serializes labels to JSON array string`() {
        val labels = listOf("Outdoor", "Food", "Plant")
        assertEquals("[\"Outdoor\",\"Food\",\"Plant\"]", MlKitTagExtractor.toJsonArray(labels))
    }

    @Test
    fun `toJsonArray returns empty array for empty list`() {
        assertEquals("[]", MlKitTagExtractor.toJsonArray(emptyList()))
    }
}
```

- [ ] **Step 2.2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.MlKitTagExtractorTest"`
Expected: 4 tests FAIL with class/method not found

- [ ] **Step 2.3: 实现 MlKitTagExtractor**

创建 `app/src/main/java/com/mamba/picme/domain/tag/MlKitTagExtractor.kt`：

```kotlin
package com.mamba.picme.domain.tag

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.mamba.picme.core.common.Logger
import org.json.JSONArray

/**
 * ML Kit Image Labeler 标签提取器
 *
 * 封装 ML Kit Image Labeler 客户端生命周期，提供从 URI 提取英文标签的能力。
 * 输出结果按置信度降序排列，支持置信度阈值和最大数量限制。
 */
class MlKitTagExtractor(
    private val context: Context,
    private val confidenceThreshold: Float = 0.5f,
    private val maxLabels: Int = 5
) {

    companion object {
        private const val TAG = "PicMe:MlKitTagExtractor"

        /**
         * 纯函数：过滤并排序标签
         */
        fun filterLabels(
            labels: List<Pair<String, Float>>,
            confidenceThreshold: Float,
            maxLabels: Int
        ): List<String> {
            return labels
                .filter { it.second >= confidenceThreshold }
                .sortedByDescending { it.second }
                .take(maxLabels)
                .map { it.first }
        }

        /**
         * 纯函数：将标签列表序列化为 JSON 数组字符串
         */
        fun toJsonArray(labels: List<String>): String {
            return JSONArray(labels).toString()
        }
    }

    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    /**
     * 从 URI 提取 ML Kit 英文标签
     *
     * @param uri 媒体 Content URI
     * @return 英文标签列表（已过滤/排序），失败返回空列表
     */
    fun extract(uri: String): List<String> {
        return try {
            val inputImage = InputImage.fromFilePath(context, Uri.parse(uri))
            extract(inputImage)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load InputImage from $uri: ${e.message}")
            emptyList()
        }
    }

    /**
     * 从 InputImage 提取 ML Kit 英文标签
     *
     * @param inputImage ML Kit 输入图像
     * @return 英文标签列表（已过滤/排序），失败返回空列表
     */
    fun extract(inputImage: InputImage): List<String> {
        return try {
            val result = Tasks.await(labeler.process(inputImage))
            val labels = result.map { it.text to it.confidence }
            filterLabels(labels, confidenceThreshold, maxLabels)
                .also { Logger.d(TAG, "ML Kit labels extracted: $it") }
        } catch (e: com.google.mlkit.common.MlKitException) {
            if (e.message?.contains("download") == true || e.message?.contains("optional module") == true) {
                Logger.w(TAG, "ML Kit label model not ready yet, skipping")
            } else {
                Logger.e(TAG, "ML Kit label error", e)
            }
            emptyList()
        } catch (e: Exception) {
            Logger.e(TAG, "Label extraction failed", e)
            emptyList()
        }
    }

    /**
     * 预热 ML Kit 模型（首次使用可能触发 Play Services 下载）
     */
    fun warmup(inputImage: InputImage): Boolean {
        return try {
            Tasks.await(labeler.process(inputImage))
            true
        } catch (e: Exception) {
            Logger.w(TAG, "ML Kit warmup failed: ${e.message}")
            false
        }
    }

    fun close() {
        try {
            labeler.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing labeler", e)
        }
    }
}
```

- [ ] **Step 2.4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.MlKitTagExtractorTest"`
Expected: 4 tests PASS

- [ ] **Step 2.5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/MlKitTagExtractor.kt
-git add app/src/test/java/com/mamba/picme/domain/tag/MlKitTagExtractorTest.kt
-git commit -m "feat(tag): add MlKitTagExtractor with confidence filtering and JSON serialization"
```

---

## Task 3: 扩展 TagGenerationPipeline

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt`

- [ ] **Step 3.1: 构造函数注入 MlKitTagExtractor**

在 `TagGenerationPipeline` 主构造函数中新增可选参数：

```kotlin
class TagGenerationPipeline(
    private val context: Context,
    private val faceDetector: FaceDetector,
    private val llmEngine: LocalLlmEngine,
    private val faceClusterEngine: FaceClusterEngine,
    private val normalizer: TagNormalizer,
    private val openClGuardian: OpenClGuardian? = null,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val promptProvider: TagPromptProvider = DefaultTagPromptProvider(),
    private val mobileClipEngine: MobileClipEngine? = null,
    private val mlKitTagExtractor: MlKitTagExtractor? = null
) {
```

- [ ] **Step 3.2: 新增 extractMlKitLabels 方法**

在类中新增方法（可放在 `releaseMobileClip()` 之后）：

```kotlin
/**
 * 提取 ML Kit Image Labeler 英文标签
 *
 * @param uri 照片 Content URI
 * @return 英文标签列表，失败返回空列表
 */
fun extractMlKitLabels(uri: String): List<String> {
    val extractor = mlKitTagExtractor ?: run {
        Log.w(TAG, "[ML Kit] MlKitTagExtractor not available")
        return emptyList()
    }
    return extractor.extract(uri)
}
```

- [ ] **Step 3.3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt
git commit -m "feat(tag): add extractMlKitLabels to TagGenerationPipeline"
```

---

## Task 4: 扩展 MediaDao

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt`

- [ ] **Step 4.1: 新增 ML Kit 标签查询方法**

在 `MediaDao` 中 `updateLabels` 附近新增：

```kotlin
/** 更新媒体的 ML Kit 英文标签 */
@Query("UPDATE media_assets SET ml_kit_labels = :labels WHERE id = :mediaId")
suspend fun updateMlKitLabels(mediaId: Long, labels: String)

/** 重置所有 ML Kit 标签（用于强制重新标记） */
@Query("UPDATE media_assets SET ml_kit_labels = NULL")
suspend fun resetAllMlKitLabels()

/** 按 ML Kit 标签搜索 */
@Query("SELECT * FROM media_assets WHERE ml_kit_labels LIKE '%' || :label || '%' ORDER BY captureDate DESC")
suspend fun searchByMlKitLabel(label: String): List<MediaEntity>

/** 未生成 ML Kit 标签的媒体 */
@Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getUnlabeledMlKitMediaIds() / getUnlabeledMlKitMediaCount()")
@Query("SELECT * FROM media_assets WHERE ml_kit_labels IS NULL OR ml_kit_labels = '' ORDER BY captureDate DESC")
suspend fun getUnlabeledMlKitMedia(): List<MediaEntity>

/** 仅获取未生成 ML Kit 标签的媒体 ID（内存友好） */
@Query("SELECT id FROM media_assets WHERE ml_kit_labels IS NULL OR ml_kit_labels = '' ORDER BY captureDate DESC")
suspend fun getUnlabeledMlKitMediaIds(): List<Long>

/** 未生成 ML Kit 标签的媒体数量 */
@Query("SELECT COUNT(*) FROM media_assets WHERE ml_kit_labels IS NULL OR ml_kit_labels = ''")
suspend fun getUnlabeledMlKitMediaCount(): Int

/** 已有 ML Kit 标签的媒体数量 */
@Query("SELECT COUNT(*) FROM media_assets WHERE ml_kit_labels IS NOT NULL AND ml_kit_labels != ''")
suspend fun getMlKitLabeledCount(): Int
```

- [ ] **Step 4.2: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/local/MediaDao.kt
git commit -m "feat(tag): add MediaDao queries for mlKitLabels"
```

---

## Task 5: 扩展 TagGenerationScheduler

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt`

- [ ] **Step 5.1: pipeline 注入 MlKitTagExtractor**

修改 `TagGenerationScheduler` 中 `pipeline` 的 lazy 初始化，新增 `mlKitTagExtractor`：

```kotlin
private val pipeline: TagGenerationPipeline by lazy {
    val faceDetector = FaceDetectorFactory.create(context)
    faceDetector.updatePipelineConfig(DetectionPipelineConfig(
        roiEngine = InferenceBackendType.MNN,
        landmarkEngine = InferenceBackendType.MNN
    ))
    val llmEngine = AgentOrchestrator.getInstance(context).getLlmEngine()
    val mobileClip = MobileClipEngine(context)
    TagGenerationPipeline(
        context = context,
        faceDetector = faceDetector,
        llmEngine = llmEngine,
        faceClusterEngine = faceClusterEngine,
        normalizer = normalizer,
        openClGuardian = openClGuardian,
        userSettingsRepository = userSettingsRepository,
        mobileClipEngine = mobileClip,
        mlKitTagExtractor = MlKitTagExtractor(context)
    )
}
```

- [ ] **Step 5.2: 新增 executeMlKitTagging 原子任务**

在 `executeMobileClipEncoding` 方法之后新增：

```kotlin
/**
 * [原子任务] ML Kit Image Labeler 英文标签提取
 */
suspend fun executeMlKitTagging(mediaId: Long) {
    val dao = db.mediaDao()
    val entity = dao.getMediaById(mediaId) ?: return

    val labels = pipeline.extractMlKitLabels(entity.uri)

    // 若任务已被取消，丢弃本次结果
    currentCoroutineContext().ensureActive()

    val labelsJson = MlKitTagExtractor.toJsonArray(labels)
    dao.updateMlKitLabels(entity.id, labelsJson)

    delay(getThrottleMs())
}
```

- [ ] **Step 5.3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
git commit -m "feat(tag): add executeMlKitTagging atomic task"
```

---

## Task 6: 扩展 TagScanOrchestrator

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt`

- [ ] **Step 6.1: TagScanPass 转数字编号**

修改伴生对象中的 `toPassNumber()`：

```kotlin
fun TagScanPass.toPassNumber(): String = when (this) {
    TagScanPass.FACE_DETECTION -> "1"
    TagScanPass.DBSCAN -> "2"
    TagScanPass.QWEN_TAGGING -> "3"
    TagScanPass.MOBILE_CLIP_ENCODING -> "4"
    TagScanPass.ML_KIT_TAGGING -> "5"
}
```

- [ ] **Step 6.2: 数据库统计新增 ML Kit 相关字段**

修改 `TagScanDbStats` data class（在文件底部）：

```kotlin
data class TagScanDbStats(
    val totalMedia: Int,
    val withFace: Int,
    val withLabels: Int,
    val withSemantic: Int,
    val personCount: Int,
    val faceEmbeddingCount: Int,
    val remainingForPass1: Int,
    val remainingForPass3: Int,
    val withMlKitLabels: Int = 0,
    val remainingForMlKit: Int = 0
)
```

修改 `getDbStats()` 伴生方法中的统计查询：

```kotlin
val withMlKitLabels = db.mediaDao().getMlKitLabeledCount()
val remainingForMlKit = db.mediaDao().getUnlabeledMlKitMediaCount()

TagScanDbStats(
    totalMedia = totalMedia,
    withFace = withFace,
    withLabels = withLabels,
    withSemantic = withSemantic,
    personCount = personCount,
    faceEmbeddingCount = faceEmbeddingCount,
    remainingForPass1 = remainingForPass1,
    remainingForPass3 = remainingForPass3,
    withMlKitLabels = withMlKitLabels,
    remainingForMlKit = remainingForMlKit
)
```

- [ ] **Step 6.3: createTasks 处理 ML_KIT_TAGGING**

在 `createTasks()` 方法中，Pass 2 之后、Pass 3 之前新增 ML Kit 任务创建逻辑：

```kotlin
// Pass 2.5: 每张媒体一个 ML Kit 标签提取任务
if (passes.contains(TagScanPass.ML_KIT_TAGGING)) {
    tasks += mediaIds.map { mediaId ->
        TagScanTaskEntity(
            sessionId = sessionId,
            mediaId = mediaId,
            pass = TagScanPass.ML_KIT_TAGGING,
            tagCategories = categoriesJson,
            status = TagScanTaskStatus.PENDING,
            priority = 1,
            createdAt = System.currentTimeMillis()
        )
    }
}
```

注意：ML_KIT_TAGGING 优先级设为 1，使其在 FACE_DETECTION（0）之后、DBSCAN（1）和 QWEN_TAGGING（2）之前或并行；若需严格在 Pass 1 之后，DBSCAN 优先级应调整为 2。此处保持简单并列。

- [ ] **Step 6.4: createTasksForSinglePass 处理 ML_KIT_TAGGING**

在 `createTasksForSinglePass()` 的 `when` 中新增分支：

```kotlin
TagScanPass.ML_KIT_TAGGING -> mediaIds.map { mediaId ->
    TagScanTaskEntity(
        sessionId = sessionId,
        mediaId = mediaId,
        pass = TagScanPass.ML_KIT_TAGGING,
        status = TagScanTaskStatus.PENDING,
        priority = 0,
        createdAt = System.currentTimeMillis()
    )
}
```

- [ ] **Step 6.5: executeTask 处理 ML_KIT_TAGGING**

修改 `executeTask()` 中的 `when`：

```kotlin
when (task.pass) {
    TagScanPass.FACE_DETECTION -> scheduler.executeFaceDetection(task.mediaId)
    TagScanPass.DBSCAN -> scheduler.executeDbscan()
    TagScanPass.QWEN_TAGGING -> scheduler.executeQwenTagging(task.mediaId)
    TagScanPass.MOBILE_CLIP_ENCODING -> scheduler.executeMobileClipEncoding(task.mediaId)
    TagScanPass.ML_KIT_TAGGING -> scheduler.executeMlKitTagging(task.mediaId)
}
```

- [ ] **Step 6.6: isPassMissing 处理 ML_KIT_TAGGING**

修改 `isPassMissing()`：

```kotlin
private suspend fun isPassMissing(mediaId: Long, pass: TagScanPass): Boolean {
    val entity = db.mediaDao().getMediaById(mediaId) ?: return true
    return when (pass) {
        TagScanPass.FACE_DETECTION -> entity.faceRoiResult.isNullOrEmpty()
        TagScanPass.QWEN_TAGGING -> entity.labels.isNullOrEmpty()
        TagScanPass.MOBILE_CLIP_ENCODING -> entity.semanticEmbedding.isNullOrEmpty()
        TagScanPass.ML_KIT_TAGGING -> entity.mlKitLabels.isNullOrEmpty()
        TagScanPass.DBSCAN -> false
    }
}
```

- [ ] **Step 6.7: schedulePass 全量模式清空 ML Kit 标签**

在 `schedulePass()` 中，Qwen 全量清空之后新增：

```kotlin
if (pass == TagScanPass.ML_KIT_TAGGING && mode == ScanMode.FULL) {
    db.mediaDao().resetAllMlKitLabels()
}
```

- [ ] **Step 6.8: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt
git commit -m "feat(tag): integrate ML_KIT_TAGGING into TagScanOrchestrator"
```

---

## Task 7: 扩展 TagGenerationService

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt`

- [ ] **Step 7.1: 新增 ML Kit Pass 的 Intent Action 常量与构建方法**

在伴生对象中，ACTION_SCAN_PASS_4_FULL 之后新增：

```kotlin
/** 单独执行 ML Kit 英文标签提取（增量） */
const val ACTION_SCAN_PASS_ML_KIT = "com.mamba.picme.tag.SCAN_PASS_ML_KIT"
/** 单独全量重新生成 ML Kit 英文标签 */
const val ACTION_SCAN_PASS_ML_KIT_FULL = "com.mamba.picme.tag.SCAN_PASS_ML_KIT_FULL"
```

在 `intentScanPass4Full` 之后新增：

```kotlin
fun intentScanPassMlKit(context: Context) = intent(context, ACTION_SCAN_PASS_ML_KIT)
fun intentScanPassMlKitFull(context: Context) = intent(context, ACTION_SCAN_PASS_ML_KIT_FULL)
```

- [ ] **Step 7.2: onStartCommand 处理新 Action**

在 `ACTION_SCAN_PASS_4_FULL` 分支之后新增：

```kotlin
ACTION_SCAN_PASS_ML_KIT -> orch.schedulePass(
    com.mamba.picme.data.local.entity.TagScanPass.ML_KIT_TAGGING,
    com.mamba.picme.domain.tag.scan.TagScanQuery(),
    com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
)
ACTION_SCAN_PASS_ML_KIT_FULL -> orch.schedulePass(
    com.mamba.picme.data.local.entity.TagScanPass.ML_KIT_TAGGING,
    com.mamba.picme.domain.tag.scan.TagScanQuery(),
    com.mamba.picme.domain.tag.scan.ScanMode.FULL
)
```

- [ ] **Step 7.3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt
git commit -m "feat(tag): add ML Kit Pass intent actions to TagGenerationService"
```

---

## Task 8: 扩展 TagGenerationControlScreen UI

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt`

- [ ] **Step 8.1: 数据库统计状态新增 ML Kit 字段**

在 `var remainingPass3 by remember { mutableIntStateOf(0) }` 后新增：

```kotlin
var withMlKitLabels by remember { mutableIntStateOf(0) }
var remainingMlKit by remember { mutableIntStateOf(0) }
```

- [ ] **Step 8.2: refreshStats 中更新 ML Kit 统计**

在 `refreshStats()` 中赋值：

```kotlin
remainingPass1 = stats.remainingForPass1
remainingPass3 = stats.remainingForPass3
withMlKitLabels = stats.withMlKitLabels
remainingMlKit = stats.remainingForMlKit
```

- [ ] **Step 8.3: StatsCard 显示 ML Kit 统计**

调用 `StatsCard` 时新增参数：

```kotlin
StatsCard(
    totalMedia = totalMedia,
    withFace = withFace,
    withLabels = withLabels,
    withSemantic = withSemantic,
    personCount = personCount,
    embeddingCount = embeddingCount,
    remainingPass1 = remainingPass1,
    remainingPass3 = remainingPass3,
    withMlKitLabels = withMlKitLabels,
    remainingMlKit = remainingMlKit
)
```

修改 `StatsCard` 的函数签名和内部实现：

```kotlin
@Composable
private fun StatsCard(
    totalMedia: Int,
    withFace: Int,
    withLabels: Int,
    withSemantic: Int,
    personCount: Int,
    embeddingCount: Int,
    remainingPass1: Int,
    remainingPass3: Int,
    withMlKitLabels: Int,
    remainingMlKit: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "数据库累计统计",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("总照片", totalMedia.toString())
                StatItem("含人脸", withFace.toString())
                StatItem("有标签", withLabels.toString())
                StatItem("有语义", withSemantic.toString())
                StatItem("人物簇", personCount.toString())
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Embedding", embeddingCount.toString())
                StatItem("Pass1剩余", remainingPass1.toString())
                StatItem("Pass3剩余", remainingPass3.toString())
                StatItem("ML Kit", withMlKitLabels.toString())
                StatItem("MLKit剩余", remainingMlKit.toString())
            }
        }
    }
}
```

- [ ] **Step 8.4: passDisplayName 处理 ML_KIT_TAGGING**

修改 `passDisplayName()`：

```kotlin
private fun passDisplayName(pass: TagScanPass?): String = when (pass) {
    TagScanPass.FACE_DETECTION -> "Pass 1: 人脸检测 + MobileCLIP"
    TagScanPass.DBSCAN -> "Pass 2: DBSCAN 聚类"
    TagScanPass.QWEN_TAGGING -> "Pass 3: Qwen 标签"
    TagScanPass.MOBILE_CLIP_ENCODING -> "MobileCLIP 语义编码（单独）"
    TagScanPass.ML_KIT_TAGGING -> "Pass 5: ML Kit 英文标签"
    null -> "准备中"
}
```

- [ ] **Step 8.5: 新增 ML Kit Pass 控制卡片**

在 MobileCLIP Pass 控制卡片之后新增：

```kotlin
Spacer(Modifier.height(8.dp))

PassControlCard(
    title = "ML Kit 英文标签",
    subtitle = "ML Kit Image Labeler 快速英文标签：$withMlKitLabels / $totalMedia 张 · 剩余 $remainingMlKit 张",
    onIncremental = {
        refreshStats()
        context.startForegroundService(TagGenerationService.intentScanPassMlKit(context))
    },
    onFull = {
        refreshStats()
        context.startForegroundService(TagGenerationService.intentScanPassMlKitFull(context))
    }
)
```

- [ ] **Step 8.6: 精细控制类别新增 ML_KIT_LABELS Chip**

在类别选择 FlowRow 中新增：

```kotlin
CategoryChip(
    label = "ML Kit",
    selected = TagCategory.ML_KIT_LABELS in selectedCategories,
    onClick = {
        selectedCategories = selectedCategories.toggle(TagCategory.ML_KIT_LABELS)
    }
)
```

- [ ] **Step 8.7: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt
git commit -m "feat(ui): add ML Kit Pass control and stats to TagGenerationControlScreen"
```

---

## Task 9: 扩展 MediaSearchEngine

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt`

- [ ] **Step 9.1: searchByCandidate 覆盖 mlKitLabels**

在 `searchByCandidate()` 中，现有 `mediaDao.searchByFileName(candidate)` 之后新增：

```kotlin
val mlKitResults = mediaDao.searchByMlKitLabel(candidate)
mlKitResults.forEach { resultMap[it.id] = it.toDomain() }
```

- [ ] **Step 9.2: searchByCandidateWithDiagnostics 覆盖 mlKitLabels**

在 `searchByCandidateWithDiagnostics()` 中，现有 `mediaDao.searchByFileName(candidate)` 之后新增：

```kotlin
mediaDao.searchByMlKitLabel(candidate).forEach { entity ->
    val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
    dims.add("mlkit_label")
    resultMap[entity.id] = media to dims
}
```

- [ ] **Step 9.3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt
git commit -m "feat(search): include mlKitLabels in MediaSearchEngine recall"
```

---

## Task 10: 编译与单元测试验证

- [ ] **Step 10.1: 运行单元测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.MlKitTagExtractorTest"`
Expected: BUILD SUCCESSFUL, 4 tests PASS

- [ ] **Step 10.2: 编译整个 app 模块**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10.3: 运行 Lint**

Run: `./gradlew :app:lintDebug`
Expected: 无新增 Critical/Warning（允许既有的 lint 问题）

- [ ] **Step 10.4: Commit**

```bash
git commit -m "test(tag): verify ML Kit tag pass compiles and tests pass"
```

---

## Task 11: 文档同步

- [ ] **Step 11.1: 更新相关 AGENTS.md 或设计文档**

如果 `app/src/main/java/com/mamba/picme/features/settings/AGENTS.md` 或 `app/src/main/java/com/mamba/picme/features/gallery/AGENTS.md` 中提到 Tag Pass 相关实现，追加一行：

```markdown
- ML Kit Image Labeler 已作为独立 Pass `ML_KIT_TAGGING` 接入，结果写入 `mlKitLabels` 字段。
```

- [ ] **Step 11.2: Commit**

```bash
git add -A
git commit -m "docs: sync AGENTS.md for ML Kit tag pass"
```

---

## 自审清单

| Spec 要求 | 对应任务 |
|-----------|----------|
| `MediaEntity` 新增 `mlKitLabels` 字段 | Task 1.1 |
| Room 迁移成功 | Task 1.4 |
| `TagScanPass.ML_KIT_TAGGING` | Task 1.2 |
| `TagCategory.ML_KIT_LABELS` | Task 1.3 |
| `MlKitTagExtractor` 可正确提取标签 | Task 2 |
| `TagScanOrchestrator` 支持独立增量/全量调度 | Task 6 |
| 全量扫描可清空 `mlKitLabels` | Task 6.7 |
| 增量扫描仅处理无 `mlKitLabels` 的媒体 | Task 6.6 |
| `TagGenerationControlScreen` 增加控制入口 | Task 8 |
| `MediaSearchEngine` 同时搜索 `labels` 和 `mlKitLabels` | Task 9 |
| Qwen `labels` 不被覆盖 | 通过独立字段保证，Task 1.1 |
| 现有 3-Pass 行为不变 | 所有修改均为新增分支，未修改旧逻辑 |
| 单元测试覆盖 | Task 2 + Task 10 |

---

> **维护者**：RD Agent
> **状态**：待执行
> **依赖设计文档**：`docs/superpowers/specs/2026-06-30-mlkit-tag-pass-design.md`
