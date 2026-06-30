# 显式约束优先的分段联合检索设计方案

> 将自然语言搜索查询按语义切分为显式段（时间、地点、人物）和内容段（物体、场景、活动、OCR），显式段先过滤得到候选集，内容段在候选集内联合检索，提升类似"去年3月在室内小孩的照片"这类多条件查询的召回精准度。
>
> **关联文档**：
> - 当前搜索实现：`app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt`
> - 规则解析器：`app/src/main/java/com/mamba/picme/domain/search/QueryParser.kt`
> - 结构化过滤：`app/src/main/java/com/mamba/picme/domain/model/StructuredFilter.kt`
> - 中文翻译/分词：`app/src/main/java/com/mamba/picme/domain/tag/i18n/ChineseQueryTranslator.kt`
> - 顶层 AGENTS.md：`/Users/guoshuai/AndroidStudioProjects/langchain4android/AGENTS.md`

---

## 1. 背景与问题

### 1.1 当前搜索流程

当前 `MediaSearchEngine` 采用三层混合检索：

1. **Layer 1: QueryParser 规则匹配**
   - 识别时间词（去年、夏天、今天等）
   - 提取关键词（split + 停用词过滤）
   - 区分地点词（硬编码中国城市词典）
2. **Layer 2: LLM 解析**
   - 规则失败时调用 LLM 生成 `StructuredFilter`
3. **Layer 2.5: MobileCLIP 语义召回**
4. **Layer 3: 融合排序**

### 1.2 当前问题

以用户查询 **"去年3月在室内小孩的照片"** 为例：

| 语义要素 | 当前处理 | 问题 |
|----------|----------|------|
| "去年3月" | `QueryParser.extractTimeRange()` 识别到"去年"，返回**去年全年** | 无法精确到月份，召回大量非3月照片 |
| "在室内" | 作为普通关键词处理 | 地点/场景没有专门通道，无法精确过滤室内照片 |
| "小孩" | 作为关键词匹配标签/OCR | 可以命中，但会与全年所有含小孩的照片做 OR，不精确 |
| "照片" | 停用词被过滤 | 无影响 |

当前实现把所有条件做 OR/松散融合，导致：
- **时间范围过宽**："去年"全年而非"去年3月"
- **地点/场景无法精确过滤**："室内"没有专门的索引通道
- **多条件组合不精确**：各维度结果简单合并，没有"必须同时满足"的语义

### 1.3 设计目标

1. **分段**：把查询切分为带类型的语义段（时间、地点、人物、物体、场景、活动、OCR 等）
2. **显式约束优先**：时间、地点、人物等有明确索引的段，先取交集得到候选集
3. **候选集内联合检索**：内容段（物体、场景、OCR 等）在候选集内执行标签/OCR/语义检索
4. **可回退**：分段失败时自动回退到现有 `QueryParser` / LLM 路径
5. **可观测**：诊断页展示分段结果和各段命中数量

---

## 2. 数据模型

### 2.1 SegmentType 枚举

```kotlin
enum class SegmentType {
    /** 时间：去年3月、今年夏天、上周一等 */
    TIME,
    /** 地点：北京、室内、海边等 */
    LOCATION,
    /** 人物：小孩、我、宝宝、某个人名等 */
    PERSON,
    /** 物体：猫、车、食物等 */
    OBJECT,
    /** 场景：室内、户外、海滩、餐厅等 */
    SCENE,
    /** 活动：聚餐、运动会、婚礼等 */
    ACTIVITY,
    /** OCR 文字：发票、车牌、菜单等 */
    OCR,
    /** 未知/停用词：照片、的、了等 */
    UNKNOWN;

    /** 是否属于显式约束段 */
    fun isExplicit(): Boolean = this in setOf(TIME, LOCATION, PERSON)

    /** 是否属于内容检索段 */
    fun isContent(): Boolean = this in setOf(OBJECT, SCENE, ACTIVITY, OCR)
}
```

### 2.2 Segment 数据类

```kotlin
data class Segment(
    val type: SegmentType,
    val text: String,
    val confidence: Float = 1.0f
)
```

### 2.3 SegmentedQuery 数据类

```kotlin
data class SegmentedQuery(
    val original: String,
    val segments: List<Segment>
) {
    val explicitSegments: List<Segment>
        get() = segments.filter { it.type.isExplicit() }

    val contentSegments: List<Segment>
        get() = segments.filter { it.type.isContent() }

    val hasExplicit: Boolean
        get() = explicitSegments.isNotEmpty()

    val hasContent: Boolean
        get() = contentSegments.isNotEmpty()
}
```

### 2.4 ExplicitFilter 中间表示

```kotlin
/**
 * 显式约束过滤条件
 */
data class ExplicitFilter(
    val timeRange: TimeRange? = null,
    val locationKeywords: List<String> = emptyList(),
    val hasFaces: Boolean? = null,
    val personKeywords: List<String> = emptyList()
)
```

### 2.5 ContentFilter 中间表示

```kotlin
/**
 * 内容检索条件
 */
data class ContentFilter(
    val keywords: List<String> = emptyList(),
    val ocrKeywords: List<String> = emptyList(),
    val semanticQuery: String? = null
)
```

---

## 3. 架构设计

### 3.1 新增组件

#### QuerySegmenter

位置：`app/src/main/java/com/mamba/picme/domain/search/QuerySegmenter.kt`

职责：
- 把原始查询切分为带类型的 `Segment` 列表
- 基于规则 + 词典实现，不引入外部模型
- 复用 `QueryParser` 的时间解析能力
- 支持中文停用词过滤

核心方法：

```kotlin
class QuerySegmenter(
    private val locationVocab: Set<String> = DEFAULT_LOCATION_VOCAB,
    private val personVocab: Set<String> = DEFAULT_PERSON_VOCAB,
    private val sceneVocab: Map<String, SegmentType> = DEFAULT_SCENE_VOCAB,
    private val objectVocab: Map<String, SegmentType> = DEFAULT_OBJECT_VOCAB
) {
    /**
     * 把查询切分为语义段
     */
    fun segment(query: String): SegmentedQuery

    /**
     * 将 SegmentedQuery 转换为显式约束 + 内容检索条件
     */
    fun toFilters(segmented: SegmentedQuery): Pair<ExplicitFilter, ContentFilter>
}
```

#### ExplicitFirstSearchPipeline

位置：`app/src/main/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipeline.kt`

职责：
- 执行显式约束优先的搜索流程
- 先应用显式过滤得到候选集
- 在候选集内执行内容检索
- 返回带命中维度的结果

核心方法：

```kotlin
class ExplicitFirstSearchPipeline(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao? = null,
    private val ocrWordDao: OcrWordDao? = null,
    private val locationDao: LocationDao? = null,
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty()),
    private val semanticSearchEngine: SemanticSearchEngine? = null
) {
    /**
     * 执行显式约束优先的分段联合检索
     */
    suspend fun search(
        segmented: SegmentedQuery,
        uiLang: AppLanguage
    ): List<MediaAsset>

    /**
     * 执行检索并返回诊断信息
     */
    suspend fun searchWithDiagnostics(
        segmented: SegmentedQuery,
        uiLang: AppLanguage
    ): ExplicitFirstDiagnostics
}
```

### 3.2 扩展已有组件

#### MediaDao

新增候选集内检索方法：

```kotlin
/** 按时间范围获取媒体 ID */
@Query("SELECT id FROM media_assets WHERE captureDate BETWEEN :startMs AND :endMs")
suspend fun getMediaIdsByTimeRange(startMs: Long, endMs: Long): List<Long>

/** 按地点关键词获取媒体 ID */
@Query("SELECT id FROM media_assets WHERE locationName LIKE '%' || :keyword || '%'")
suspend fun getMediaIdsByLocationKeyword(keyword: String): List<Long>

/** 按人脸标记获取媒体 ID */
@Query("SELECT id FROM media_assets WHERE hasFace = 1")
suspend fun getMediaIdsByHasFace(): List<Long>

/** 在指定 ID 列表中搜索标签 */
@Query("SELECT * FROM media_assets WHERE id IN (:ids) AND labels LIKE '%' || :keyword || '%'")
suspend fun searchLabelsInIds(ids: List<Long>, keyword: String): List<MediaEntity>

/** 在指定 ID 列表中搜索 ML Kit 标签 */
@Query("SELECT * FROM media_assets WHERE id IN (:ids) AND mlKitLabels LIKE '%' || :keyword || '%'")
suspend fun searchMlKitLabelsInIds(ids: List<Long>, keyword: String): List<MediaEntity>

/** 在指定 ID 列表中搜索 OCR */
@Query("SELECT * FROM media_assets WHERE id IN (:ids) AND ocrText LIKE '%' || :keyword || '%'")
suspend fun searchOcrInIds(ids: List<Long>, keyword: String): List<MediaEntity>

/** 在指定 ID 列表中搜索文件名 */
@Query("SELECT * FROM media_assets WHERE id IN (:ids) AND fileName LIKE '%' || :keyword || '%'")
suspend fun searchFileNameInIds(ids: List<Long>, keyword: String): List<MediaEntity>
```

> 注意：当 `ids` 过大时（> 900），需要分批执行以避免 SQLite `IN` 子句参数限制。

#### QueryParser

扩展更细粒度的时间解析：

```kotlin
// 新增支持
- "去年3月" → 去年3月1日 ~ 去年3月31日
- "今年5月" → 今年5月1日 ~ 今年5月31日
- "上个月" → 已支持，保持不变
- "3个月前" / "三个月前" → 3个月前的整月
- "2024年" → 2024年全年
- "2024年3月" → 2024年3月
- "上周" / "本周" → 对应周
```

#### MediaSearchEngine

修改 `search()` 方法：

```kotlin
suspend fun search(
    query: String,
    llmSearch: (suspend (String) -> StructuredFilter?)? = null,
    enableSemanticSearch: Boolean = true
): SearchResult {
    if (query.isBlank()) return SearchResult(emptyList(), query)

    val uiLang = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

    // 新路径：显式约束优先的分段联合检索
    val segmented = querySegmenter.segment(query)
    if (segmented.hasExplicit || segmented.hasContent) {
        val results = explicitPipeline.search(segmented, uiLang)
        if (results.isNotEmpty()) {
            val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
                searchSemantic(query, null)
            } else emptyList()
            val merged = mergeAndRank(results, semanticResults)
            return SearchResult(merged, query)
        }
    }

    // 回退：原 Layer 1 / Layer 2 / 兜底模糊搜索
    return legacySearch(query, llmSearch, enableSemanticSearch, uiLang)
}
```

### 3.3 分段规则示例

| 查询 | 分段结果 |
|------|----------|
| 去年3月在室内小孩的照片 | `[TIME:"去年3月", LOCATION:"室内", PERSON:"小孩", UNKNOWN:"照片"]` |
| 北京公园里的小孩 | `[LOCATION:"北京", SCENE:"公园", PERSON:"小孩"]` |
| 海边日落的照片 | `[SCENE:"海边", SCENE:"日落", UNKNOWN:"照片"]` |
| 上周发票截图 | `[TIME:"上周", OCR:"发票", UNKNOWN:"截图"]` |
| 找猫的照片 | `[OBJECT:"猫", UNKNOWN:"照片", UNKNOWN:"找"]` |

---

## 4. 数据流

### 4.1 标准查询

```
用户输入："去年3月在室内小孩的照片"
    ↓
QuerySegmenter.segment()
    ↓
[TIME:"去年3月", LOCATION:"室内", PERSON:"小孩", UNKNOWN:"照片"]
    ↓
QuerySegmenter.toFilters()
    ↓
ExplicitFilter(timeRange=..., locationKeywords=["室内"], hasFaces=true, personKeywords=["小孩"])
ContentFilter(keywords=["小孩"], semanticQuery="去年3月在室内小孩")
    ↓
ExplicitFirstSearchPipeline.search()
    ↓
Step 1: 显式过滤
  - 时间：getMediaIdsByTimeRange(2024-03-01, 2024-03-31) → ids_a
  - 地点：getMediaIdsByLocationKeyword("室内") → ids_b
  - 人物：getMediaIdsByHasFace() → ids_c
  - 交集：candidateIds = ids_a ∩ ids_b ∩ ids_c (假设 120 张)
    ↓
Step 2: 内容检索（在 candidateIds 内）
  - 标签：searchLabelsInIds(candidateIds, "小孩")
  - ML Kit：searchMlKitLabelsInIds(candidateIds, "child")
  - OCR：searchOcrInIds(candidateIds, "小孩")
  - 文件名：searchFileNameInIds(candidateIds, "小孩")
    ↓
Step 3: 融合排序
  - 合并结果，按 SQL 匹配位置 + 语义相似度 + 时间衰减排序
    ↓
返回结果
```

### 4.2 无显式约束查询

```
用户输入："猫的照片"
    ↓
[OBJECT:"猫", UNKNOWN:"照片", UNKNOWN:"的"]
    ↓
显式段为空
    ↓
直接在所有媒体中检索内容段（标签/OCR/语义）
```

### 4.3 分段失败回退

```
用户输入："那种很温馨的照片"
    ↓
QuerySegmenter 无法识别有效段（只有 UNKNOWN）
    ↓
回退到原有 QueryParser / LLM / 兜底模糊搜索路径
```

---

## 5. 候选集交集算法

```kotlin
private fun intersectCandidateSets(sets: List<Set<Long>>): Set<Long> {
    if (sets.isEmpty()) return emptySet()
    if (sets.size == 1) return sets[0]

    // 按大小排序，先从小到大取交集，减少计算量
    val sorted = sets.sortedBy { it.size }
    var result = sorted[0]
    for (i in 1 until sorted.size) {
        result = result.intersect(sorted[i])
        if (result.isEmpty()) break
    }
    return result
}
```

当显式约束产生空候选集时，策略：
- **宽松模式**：移除置信度最低的显式约束，重新计算候选集
- **严格模式**（默认）：返回空结果，并在诊断信息中提示约束冲突

---

## 6. 词典设计

### 6.1 地点词典

```kotlin
val DEFAULT_LOCATION_VOCAB = setOf(
    // 城市
    "北京", "上海", "广州", "深圳", ..., "三里屯", "国贸", "陆家嘴",
    // 场景地点
    "室内", "户外", "海边", "山上", "公园", "餐厅", "商场", "机场", "车站", "学校"
)
```

### 6.2 人物词典

```kotlin
val DEFAULT_PERSON_VOCAB = setOf(
    "人", "人物", "人脸", "我", "我们", "小孩", "儿童", "婴儿", "宝宝", "孩子",
    "男孩", "女孩", "男人", "女人", "男生", "女生", "老人", "朋友", "家人", "合影"
)
```

### 6.3 场景/物体词典

```kotlin
val DEFAULT_SCENE_VOCAB = mapOf(
    "室内" to SegmentType.SCENE,
    "户外" to SegmentType.SCENE,
    "海边" to SegmentType.SCENE,
    "山上" to SegmentType.SCENE,
    "公园" to SegmentType.SCENE,
    "餐厅" to SegmentType.SCENE,
    "日落" to SegmentType.SCENE,
    "夜景" to SegmentType.SCENE
)

val DEFAULT_OBJECT_VOCAB = mapOf(
    "猫" to SegmentType.OBJECT,
    "狗" to SegmentType.OBJECT,
    "车" to SegmentType.OBJECT,
    "食物" to SegmentType.OBJECT,
    "花" to SegmentType.OBJECT
)
```

---

## 7. 错误处理

| 错误场景 | 处理策略 |
|----------|----------|
| 时间词解析失败 | 忽略该段，继续其他段；若其他段无显式约束，回退到原路径 |
| 地点词无索引命中 | 该维度候选集为空 → 严格模式下返回空，宽松模式下移除该维度 |
| `IN` 子句 IDs 超过 900 | 分批查询，每批 800 个 ID |
| 所有显式约束交集为空 | 返回空结果并记录诊断；UI 可提示"没有同时满足这些条件的照片" |
| 候选集过大（> 5000） | 限制只取前 5000 个按时间排序的 ID，避免内容检索过慢 |

---

## 8. 性能预算

| 指标 | 预算 | 说明 |
|------|------|------|
| 分段解析 | < 5ms | 纯规则 + 词典匹配 |
| 单个显式段 SQL | < 50ms | 索引字段查询 |
| 候选集交集 | < 10ms | 内存中 Set 操作 |
| 候选集内内容检索 | < 100ms | 在限定 ID 集内 LIKE 查询 |
| 总端到端 | < 300ms | 不含 MobileCLIP 语义搜索 |

---

## 9. 验收标准

- [ ] `QuerySegmenter` 能正确切分"去年3月在室内小孩的照片"
- [ ] "去年3月"被解析为精确到月的时间范围
- [ ] 时间/地点/人物段先取交集得到候选集
- [ ] 内容段在候选集内检索标签/OCR/文件名
- [ ] 无显式约束时在所有媒体中检索内容段
- [ ] 分段失败时回退到原有搜索路径
- [ ] `MediaSearchEngine.searchWithDiagnostics()` 显示分段结果和各段命中数
- [ ] 单元测试覆盖 `QuerySegmenter.segment()` 至少 10 个典型查询
- [ ] 单元测试覆盖候选集交集算法
- [ ] 不破坏现有搜索路径的单元测试

---

## 10. 风险与 Mitigation

| 风险 | Mitigation |
|------|-----------|
| 词典覆盖不全导致分段错误 | 提供可扩展的词典接口，默认覆盖高频词，后续可热更新 |
| 显式约束交集为空导致零结果 | 默认严格模式，UI 可提示用户放宽条件；诊断页显示各段命中数 |
| 候选集内 `IN` 查询性能差 | IDs 分批 + 限制最大候选集大小 |
| 与现有 LLM 路径冲突 | 新路径作为优先尝试，失败自动回退 |
| "室内"等词同时是地点和场景 | 同时加入 `LOCATION_VOCAB` 和 `SCENE_VOCAB`，按 `LOCATION` 处理 |

---

## 11. 后续可选优化

- 引入轻量 NER 模型（如基于 transformers 的 tiny 中文 NER）提升分段准确率
- 为 "室内" / "户外" 等场景训练专门分类器或利用 EXIF/光线特征
- 人物段支持具体人名（依赖人脸聚类命名）

---

> **维护者**：RD Agent
> **状态**：待实施
> **最后更新**：2026-06-30
