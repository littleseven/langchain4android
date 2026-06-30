# 显式约束优先的分段联合检索实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将自然语言搜索按语义切分为显式段（时间/地点/人物）和内容段，显式段先取交集得到候选集，内容段在候选集内联合检索，提升多条件查询精度。

**Architecture:** 新增 `QuerySegmenter` 对查询分词分段；新增 `ExplicitFirstSearchPipeline` 先执行显式约束过滤，再在候选集内执行标签/OCR/语义检索；`MediaSearchEngine` 将新路径作为优先入口，失败自动回退到原有规则/LLM/语义路径。

**Tech Stack:** Kotlin, Android Room, JUnit4

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/java/com/mamba/picme/domain/search/SegmentType.kt` | 创建 | 语义段类型枚举 |
| `app/src/main/java/com/mamba/picme/domain/search/Segment.kt` | 创建 | 单个语义段数据类 |
| `app/src/main/java/com/mamba/picme/domain/search/SegmentedQuery.kt` | 创建 | 分段查询结果数据类 |
| `app/src/main/java/com/mamba/picme/domain/search/ExplicitFilter.kt` | 创建 | 显式约束中间表示 |
| `app/src/main/java/com/mamba/picme/domain/search/ContentFilter.kt` | 创建 | 内容检索条件中间表示 |
| `app/src/main/java/com/mamba/picme/domain/search/QuerySegmenter.kt` | 创建 | 查询分词分段器 |
| `app/src/main/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipeline.kt` | 创建 | 显式约束优先检索管道 |
| `app/src/main/java/com/mamba/picme/domain/search/QueryParser.kt` | 修改 | 扩展细粒度时间解析 |
| `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt` | 修改 | 候选集内查询方法 |
| `app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt` | 修改 | 集成新路径 + 诊断 |
| `app/src/test/java/com/mamba/picme/domain/search/QuerySegmenterTest.kt` | 创建 | QuerySegmenter 单元测试 |
| `app/src/test/java/com/mamba/picme/domain/search/QueryParserTimeTest.kt` | 创建 | 时间解析扩展测试 |
| `app/src/test/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipelineTest.kt` | 创建 | Pipeline 单元测试 |
| `app/src/main/java/com/mamba/picme/features/gallery/components/SearchTopBar.kt` | 可选修改 | 若 UI 需要展示分段信息 |
| `app/AGENTS.md` | 修改 | 同步搜索架构说明 |

---

## Task 1: 数据模型

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/search/SegmentType.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/search/Segment.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/search/SegmentedQuery.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/search/ExplicitFilter.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/search/ContentFilter.kt`

- [ ] **Step 1.1: 创建 SegmentType 枚举**

创建 `app/src/main/java/com/mamba/picme/domain/search/SegmentType.kt`：

```kotlin
package com.mamba.picme.domain.search

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

- [ ] **Step 1.2: 创建 Segment 数据类**

创建 `app/src/main/java/com/mamba/picme/domain/search/Segment.kt`：

```kotlin
package com.mamba.picme.domain.search

/**
 * 查询中的单个语义段
 */
data class Segment(
    val type: SegmentType,
    val text: String,
    val confidence: Float = 1.0f
)
```

- [ ] **Step 1.3: 创建 SegmentedQuery 数据类**

创建 `app/src/main/java/com/mamba/picme/domain/search/SegmentedQuery.kt`：

```kotlin
package com.mamba.picme.domain.search

/**
 * 分段后的查询
 */
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

    val isEmpty: Boolean
        get() = segments.isEmpty() || segments.all { it.type == SegmentType.UNKNOWN }
}
```

- [ ] **Step 1.4: 创建 ExplicitFilter 数据类**

创建 `app/src/main/java/com/mamba/picme/domain/search/ExplicitFilter.kt`：

```kotlin
package com.mamba.picme.domain.search

import com.mamba.picme.domain.model.TimeRange

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

- [ ] **Step 1.5: 创建 ContentFilter 数据类**

创建 `app/src/main/java/com/mamba/picme/domain/search/ContentFilter.kt`：

```kotlin
package com.mamba.picme.domain.search

/**
 * 内容检索条件
 */
data class ContentFilter(
    val keywords: List<String> = emptyList(),
    val ocrKeywords: List<String> = emptyList(),
    val semanticQuery: String? = null
)
```

- [ ] **Step 1.6: 编译验证数据模型**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

## Task 2: QuerySegmenter

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/search/QuerySegmenter.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/search/QuerySegmenterTest.kt`

- [ ] **Step 2.1: 编写 QuerySegmenter 测试**

创建 `app/src/test/java/com/mamba/picme/domain/search/QuerySegmenterTest.kt`：

```kotlin
package com.mamba.picme.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuerySegmenterTest {

    @Test
    fun `segment splits last year march indoor child photo`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("去年3月在室内小孩的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.TIME, "去年3月"),
                Segment(SegmentType.LOCATION, "室内"),
                Segment(SegmentType.PERSON, "小孩"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits beijing park child`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("北京公园里的小孩")

        assertEquals(
            listOf(
                Segment(SegmentType.LOCATION, "北京"),
                Segment(SegmentType.SCENE, "公园"),
                Segment(SegmentType.PERSON, "小孩")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits cat photo`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("猫的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.OBJECT, "猫"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
    }

    @Test
    fun `segment returns empty for stop words only`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("的照片")
        assertTrue(result.isEmpty)
    }

    @Test
    fun `toFilters converts explicit and content segments`() {
        val segmenter = QuerySegmenter()
        val segmented = SegmentedQuery(
            original = "去年3月在室内小孩",
            segments = listOf(
                Segment(SegmentType.TIME, "去年3月"),
                Segment(SegmentType.LOCATION, "室内"),
                Segment(SegmentType.PERSON, "小孩")
            )
        )

        val (explicit, content) = segmenter.toFilters(segmented)

        assertTrue(explicit.timeRange != null)
        assertEquals(listOf("室内"), explicit.locationKeywords)
        assertEquals(true, explicit.hasFaces)
        assertEquals(listOf("小孩"), explicit.personKeywords)
        assertEquals(listOf("小孩"), content.keywords)
    }
}
```

- [ ] **Step 2.2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.QuerySegmenterTest"`
Expected: tests FAIL with class/method not found

- [ ] **Step 2.3: 实现 QuerySegmenter**

创建 `app/src/main/java/com/mamba/picme/domain/search/QuerySegmenter.kt`：

```kotlin
package com.mamba.picme.domain.search

import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.ExplicitFilter
import com.mamba.picme.domain.model.TimeRange

/**
 * 查询分词分段器
 *
 * 把自然语言搜索查询切分为带类型的语义段。
 * 基于规则 + 词典实现，不引入外部模型。
 */
class QuerySegmenter(
    private val locationVocab: Set<String> = DEFAULT_LOCATION_VOCAB,
    private val personVocab: Set<String> = DEFAULT_PERSON_VOCAB,
    private val sceneVocab: Set<String> = DEFAULT_SCENE_VOCAB,
    private val objectVocab: Set<String> = DEFAULT_OBJECT_VOCAB,
    private val ocrVocab: Set<String> = DEFAULT_OCR_VOCAB,
    private val activityVocab: Set<String> = DEFAULT_ACTIVITY_VOCAB,
    private val queryParser: QueryParser = QueryParser
) {

    companion object {
        val DEFAULT_LOCATION_VOCAB = setOf(
            "北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "重庆",
            "武汉", "西安", "苏州", "天津", "长沙", "郑州", "东莞", "青岛",
            "沈阳", "宁波", "昆明", "大连", "厦门", "合肥", "佛山", "福州",
            "哈尔滨", "济南", "温州", "长春", "石家庄", "常州", "泉州", "南宁",
            "贵阳", "南昌", "太原", "烟台", "嘉兴", "南通", "金华", "珠海",
            "惠州", "徐州", "海口", "乌鲁木齐", "兰州", "呼和浩特", "银川", "西宁",
            "三里屯", "国贸", "陆家嘴", "外滩", "西湖", "故宫", "长城", "天安门",
            "室内", "户外", "室外", "海边", "山上", "公园", "餐厅", "商场",
            "机场", "车站", "学校", "家里", "办公室", "卧室", "厨房"
        )

        val DEFAULT_PERSON_VOCAB = setOf(
            "人", "人物", "人脸", "我", "我们", "你", "你们",
            "小孩", "儿童", "婴儿", "宝宝", "孩子", "男孩", "女孩",
            "男人", "女人", "男生", "女生", "老人", "朋友", "家人",
            "合影", "合照", "自拍", "自己"
        )

        val DEFAULT_SCENE_VOCAB = setOf(
            "公园", "海边", "山上", "海滩", "日落", "夜景", "天空", "草地",
            "森林", "沙漠", "雪地", "雨天", "晴天", "阴天"
        )

        val DEFAULT_OBJECT_VOCAB = setOf(
            "猫", "狗", "车", "食物", "花", "树", "鸟", "鱼", "书", "电脑",
            "手机", "杯子", "椅子", "桌子", "房子", "桥", "路", "云", "山"
        )

        val DEFAULT_OCR_VOCAB = setOf(
            "发票", "车票", "机票", "菜单", "名片", "车牌", "快递单", "截图",
            "身份证", "驾照", "护照", "合同", "收据", "门票", "优惠券"
        )

        val DEFAULT_ACTIVITY_VOCAB = setOf(
            "聚餐", "吃饭", "婚礼", "生日", "运动会", "旅行", "旅游", "约会",
            "会议", "逛街", "购物", "跑步", "爬山", "游泳", "唱歌", "跳舞"
        )

        private val STOP_WORDS = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "都", "一",
            "把", "一个", "上面", "下面", "可以", "这个", "那个", "拍", "照片",
            "图片", "找", "搜索", "显示", "查看", "包含", "给我", "帮我", "在",
            "里", "中", "上", "下", "和", "与", "及", "还有"
        )
    }

    /**
     * 把查询切分为语义段
     */
    fun segment(query: String): SegmentedQuery {
        val normalized = query.trim()
        if (normalized.isEmpty()) return SegmentedQuery(normalized, emptyList())

        val segments = mutableListOf<Segment>()
        var remaining = normalized

        while (remaining.isNotEmpty()) {
            val segment = findNextSegment(remaining)
            if (segment != null) {
                if (segment.type != SegmentType.UNKNOWN) {
                    segments.add(segment)
                }
                remaining = remaining.substring(segment.text.length).trimStart()
            } else {
                // 未匹配任何词典，按字前进
                val firstChar = remaining[0].toString()
                if (firstChar !in STOP_WORDS) {
                    segments.add(Segment(SegmentType.UNKNOWN, firstChar))
                }
                remaining = remaining.substring(1).trimStart()
            }
        }

        return SegmentedQuery(normalized, mergeConsecutiveUnknown(segments))
    }

    /**
     * 将分段结果转换为显式约束和内容检索条件
     */
    fun toFilters(segmented: SegmentedQuery): Pair<ExplicitFilter, ContentFilter> {
        val explicit = ExplicitFilter(
            timeRange = parseTimeRange(segmented),
            locationKeywords = segmented.explicitSegments
                .filter { it.type == SegmentType.LOCATION }
                .map { it.text },
            hasFaces = if (hasPersonSegment(segmented)) true else null,
            personKeywords = segmented.explicitSegments
                .filter { it.type == SegmentType.PERSON }
                .map { it.text }
        )

        val contentKeywords = mutableListOf<String>()
        val ocrKeywords = mutableListOf<String>()

        for (segment in segmented.contentSegments) {
            when (segment.type) {
                SegmentType.OCR -> ocrKeywords.add(segment.text)
                else -> contentKeywords.add(segment.text)
            }
        }

        val content = ContentFilter(
            keywords = contentKeywords,
            ocrKeywords = ocrKeywords,
            semanticQuery = segmented.original
        )

        return explicit to content
    }

    private fun findNextSegment(query: String): Segment? {
        // 优先匹配最长词（最大 8 个字）
        val maxLen = minOf(query.length, 8)
        for (len in maxLen downTo 1) {
            val sub = query.substring(0, len)
            when {
                sub in STOP_WORDS -> return Segment(SegmentType.UNKNOWN, sub)
                sub in locationVocab -> return Segment(SegmentType.LOCATION, sub)
                sub in personVocab -> return Segment(SegmentType.PERSON, sub)
                sub in sceneVocab -> return Segment(SegmentType.SCENE, sub)
                sub in objectVocab -> return Segment(SegmentType.OBJECT, sub)
                sub in activityVocab -> return Segment(SegmentType.ACTIVITY, sub)
                sub in ocrVocab -> return Segment(SegmentType.OCR, sub)
            }
        }
        return null
    }

    private fun parseTimeRange(segmented: SegmentedQuery): TimeRange? {
        val timeText = segmented.segments
            .filter { it.type == SegmentType.TIME }
            .joinToString("") { it.text }
        return if (timeText.isNotEmpty()) {
            queryParser.parseTimeRange(timeText)
        } else null
    }

    private fun hasPersonSegment(segmented: SegmentedQuery): Boolean {
        return segmented.segments.any { it.type == SegmentType.PERSON }
    }

    private fun mergeConsecutiveUnknown(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty()) return segments
        val result = mutableListOf<Segment>()
        var current = segments[0]
        for (i in 1 until segments.size) {
            val next = segments[i]
            if (current.type == SegmentType.UNKNOWN && next.type == SegmentType.UNKNOWN) {
                current = current.copy(text = current.text + next.text)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }
}
```

- [ ] **Step 2.4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.QuerySegmenterTest"`
Expected: 5 tests PASS

- [ ] **Step 2.5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/SegmentType.kt
-git add app/src/main/java/com/mamba/picme/domain/search/Segment.kt
-git add app/src/main/java/com/mamba/picme/domain/search/SegmentedQuery.kt
-git add app/src/main/java/com/mamba/picme/domain/search/ExplicitFilter.kt
-git add app/src/main/java/com/mamba/picme/domain/search/ContentFilter.kt
-git add app/src/main/java/com/mamba/picme/domain/search/QuerySegmenter.kt
-git add app/src/test/java/com/mamba/picme/domain/search/QuerySegmenterTest.kt
-git commit -m "feat(search): add QuerySegmenter and segmented query models"
```

---

## Task 3: 扩展 QueryParser 细粒度时间解析

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/search/QueryParser.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/search/QueryParserTimeTest.kt`

- [ ] **Step 3.1: 编写时间解析测试**

创建 `app/src/test/java/com/mamba/picme/domain/search/QueryParserTimeTest.kt`：

```kotlin
package com.mamba.picme.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QueryParserTimeTest {

    @Test
    fun `parse last year march`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("去年3月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH)) // 0-based

        cal.timeInMillis = range.endMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH))
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `parse this year may`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("今年5月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse specific year and month`() {
        val range = QueryParser.parseTimeRange("2024年3月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse full year`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("去年")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(0, cal.get(Calendar.MONTH))

        cal.timeInMillis = range.endMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(11, cal.get(Calendar.MONTH))
    }
}
```

- [ ] **Step 3.2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.QueryParserTimeTest"`
Expected: tests FAIL with method not found

- [ ] **Step 3.3: 扩展 QueryParser**

修改 `app/src/main/java/com/mamba/picme/domain/search/QueryParser.kt`：

1. 把 `extractTimeRange(query: String)` 改为公开方法 `parseTimeRange(query: String)`：

```kotlin
    /**
     * 解析查询中的时间范围（公开给 QuerySegmenter 复用）
     */
    fun parseTimeRange(query: String): TimeRange? {
        // 去年3月 / 今年5月 / 2024年3月
        val monthMatch = Regex("(去年|今年|前年|\\d{4})年(\\d{1,2})月").find(query)
        if (monthMatch != null) {
            val yearPart = monthMatch.groupValues[1]
            val month = monthMatch.groupValues[2].toInt().coerceIn(1, 12)
            val year = when (yearPart) {
                "去年" -> currentYear - 1
                "今年" -> currentYear
                "前年" -> currentYear - 2
                else -> yearPart.toInt()
            }
            return TimeRange(
                startMs = monthStartMs(year, month - 1),
                endMs = monthEndMs(year, month - 1)
            )
        }

        // 去年 / 今年 / 前年
        if (query.contains("去年")) {
            return TimeRange(
                startMs = monthStartMs(currentYear - 1, 0),
                endMs = monthEndMs(currentYear - 1, 11)
            )
        }
        if (query.contains("今年")) {
            val startMonth = if (query.contains("夏天")) 5 else 0
            val endMonth = if (query.contains("夏天")) 7 else 11
            return TimeRange(
                startMs = monthStartMs(currentYear, startMonth),
                endMs = monthEndMs(currentYear, endMonth)
            )
        }
        if (query.contains("前年")) {
            return TimeRange(
                startMs = monthStartMs(currentYear - 2, 0),
                endMs = monthEndMs(currentYear - 2, 11)
            )
        }

        // 夏天 / 春天 / 秋天 / 冬天
        if (query.contains("夏天")) {
            return TimeRange(
                startMs = monthStartMs(currentYear, 5),
                endMs = monthEndMs(currentYear, 7)
            )
        }
        val seasonMap = mapOf("春天" to 2, "秋天" to 8, "冬天" to 11)
        for ((season, startMonth) in seasonMap) {
            if (query.contains(season)) {
                return TimeRange(
                    startMs = monthStartMs(currentYear, startMonth),
                    endMs = monthEndMs(currentYear, (startMonth + 2) % 12)
                )
            }
        }

        // 上个月
        if (query.contains("上个月")) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.MONTH, -1)
            val month = cal.get(Calendar.MONTH)
            val year = cal.get(Calendar.YEAR)
            return TimeRange(
                startMs = monthStartMs(year, month),
                endMs = monthEndMs(year, month)
            )
        }

        // 本周 / 上周
        if (query.contains("本周") || query.contains("上周")) {
            val cal = Calendar.getInstance()
            if (query.contains("上周")) cal.add(Calendar.WEEK_OF_YEAR, -1)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 6)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            val end = cal.timeInMillis
            return TimeRange(startMs = start, endMs = end)
        }

        // 昨天 / 今天 / 前天
        val relativeDayMap = mapOf("前天" to -2, "昨天" to -1, "今天" to 0)
        for ((word, offset) in relativeDayMap) {
            if (query.contains(word)) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, offset)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                return TimeRange(startMs = start, endMs = cal.timeInMillis)
            }
        }

        return null
    }
```

2. 修改 `parse()` 方法内部调用：
   - 把 `val timeRange = extractTimeRange(trimmed)` 改为 `val timeRange = parseTimeRange(trimmed)`
   - 删除旧的 private `extractTimeRange` 方法，或保留为 `private fun extractTimeRange(query: String) = parseTimeRange(query)` 兼容

- [ ] **Step 3.4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.QueryParserTimeTest"`
Expected: 4 tests PASS

- [ ] **Step 3.5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/QueryParser.kt
-git add app/src/test/java/com/mamba/picme/domain/search/QueryParserTimeTest.kt
-git commit -m "feat(search): extend QueryParser with fine-grained time parsing"
```

---

## Task 4: 扩展 MediaDao

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt`

- [ ] **Step 4.1: 新增候选集内查询方法**

在 `MediaDao` 中现有查询方法之后新增：

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

    /** 根据 ID 列表获取媒体（用于候选集回退） */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids)")
    suspend fun getMediaByIds(ids: List<Long>): List<MediaEntity>
```

- [ ] **Step 4.2: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/local/MediaDao.kt
-git commit -m "feat(search): add candidate-set queries to MediaDao"
```

---

## Task 5: ExplicitFirstSearchPipeline

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipeline.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipelineTest.kt`

- [ ] **Step 5.1: 编写 Pipeline 测试**

由于 Pipeline 依赖 `MediaDao`（Room 接口），单元测试中使用 Mockito 或 fake DAO。本项目未引入 Mockito，因此先用最小 fake 对象测试纯逻辑函数（候选集交集、分批）。

创建 `app/src/test/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipelineTest.kt`：

```kotlin
package com.mamba.picme.domain.search

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplicitFirstSearchPipelineTest {

    @Test
    fun `intersectCandidateSets returns intersection of multiple sets`() {
        val sets = listOf(
            setOf(1L, 2L, 3L, 4L),
            setOf(2L, 3L, 5L),
            setOf(2L, 3L, 6L)
        )
        val result = ExplicitFirstSearchPipeline.intersectCandidateSets(sets)
        assertEquals(setOf(2L, 3L), result)
    }

    @Test
    fun `intersectCandidateSets returns empty when no common ids`() {
        val sets = listOf(setOf(1L, 2L), setOf(3L, 4L))
        val result = ExplicitFirstSearchPipeline.intersectCandidateSets(sets)
        assertEquals(emptySet<Long>(), result)
    }

    @Test
    fun `intersectCandidateSets returns single set unchanged`() {
        val sets = listOf(setOf(1L, 2L, 3L))
        val result = ExplicitFirstSearchPipeline.intersectCandidateSets(sets)
        assertEquals(setOf(1L, 2L, 3L), result)
    }

    @Test
    fun `chunkIds splits list into batches`() {
        val ids = (1L..2500L).toList()
        val chunks = ExplicitFirstSearchPipeline.chunkIds(ids, 900)
        assertEquals(3, chunks.size)
        assertEquals(900, chunks[0].size)
        assertEquals(900, chunks[1].size)
        assertEquals(700, chunks[2].size)
    }
}
```

- [ ] **Step 5.2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.ExplicitFirstSearchPipelineTest"`
Expected: tests FAIL with class/method not found

- [ ] **Step 5.3: 实现 ExplicitFirstSearchPipeline**

创建 `app/src/main/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipeline.kt`：

```kotlin
package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.TagTranslator
import com.mamba.picme.core.common.Logger

/**
 * 显式约束优先的分段联合检索管道
 *
 * 1. 对显式约束段（时间/地点/人物）分别查询，取交集得到候选集
 * 2. 在候选集内对内容段（物体/场景/活动/OCR）执行标签/OCR/文件名检索
 * 3. 返回带命中维度信息的结果
 */
class ExplicitFirstSearchPipeline(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao? = null,
    private val ocrWordDao: OcrWordDao? = null,
    private val locationDao: LocationDao? = null,
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty()),
    private val semanticSearchEngine: SemanticSearchEngine? = null
) {

    companion object {
        private const val TAG = "PicMe:ExplicitFirstSearchPipeline"

        /** SQLite IN 子句安全批量大小 */
        private const val ID_BATCH_SIZE = 800

        /** 候选集大小上限，超过则只保留最新的前 N 张 */
        private const val MAX_CANDIDATE_SIZE = 5000

        /**
         * 多个候选集取交集
         */
        fun intersectCandidateSets(sets: List<Set<Long>>): Set<Long> {
            if (sets.isEmpty()) return emptySet()
            if (sets.size == 1) return sets[0]

            val sorted = sets.sortedBy { it.size }
            var result = sorted[0]
            for (i in 1 until sorted.size) {
                result = result.intersect(sorted[i])
                if (result.isEmpty()) break
            }
            return result
        }

        /**
         * 将 ID 列表分批
         */
        fun chunkIds(ids: List<Long>, batchSize: Int = ID_BATCH_SIZE): List<List<Long>> {
            if (ids.isEmpty()) return emptyList()
            return ids.chunked(batchSize)
        }
    }

    /**
     * 执行显式约束优先检索
     *
     * @param segmented 分段后的查询
     * @param uiLang 用户界面语言
     * @return 命中的媒体列表（按时间倒序，未做融合排序）
     */
    suspend fun search(
        segmented: SegmentedQuery,
        uiLang: AppLanguage
    ): List<MediaAsset> {
        val candidateIds = applyExplicitFilters(segmented)

        // 显式约束交集为空 → 返回空结果
        if (segmented.hasExplicit && candidateIds.isEmpty()) {
            Logger.d(TAG, "Explicit constraints yield empty candidate set")
            return emptyList()
        }

        return if (candidateIds.isNotEmpty()) {
            searchContentInCandidates(segmented, candidateIds, uiLang)
        } else {
            // 无显式约束，在所有媒体中检索内容段
            searchContentGlobally(segmented, uiLang)
        }
    }

    /**
     * 执行检索并返回诊断信息
     */
    suspend fun searchWithDiagnostics(
        segmented: SegmentedQuery,
        uiLang: AppLanguage
    ): ExplicitFirstDiagnostics {
        val startTime = System.currentTimeMillis()

        val explicitStart = System.currentTimeMillis()
        val candidateIds = applyExplicitFilters(segmented)
        val explicitTimeMs = System.currentTimeMillis() - explicitStart

        val explicitBreakdown = mutableListOf<ExplicitDimensionResult>()
        // TODO: 后续可细化每个显式段的命中数量

        val contentStart = System.currentTimeMillis()
        val results = if (segmented.hasExplicit && candidateIds.isEmpty()) {
            emptyList()
        } else if (candidateIds.isNotEmpty()) {
            searchContentInCandidates(segmented, candidateIds, uiLang)
        } else {
            searchContentGlobally(segmented, uiLang)
        }
        val contentTimeMs = System.currentTimeMillis() - contentStart

        return ExplicitFirstDiagnostics(
            originalQuery = segmented.original,
            segments = segmented.segments,
            candidateCount = candidateIds.size,
            resultCount = results.size,
            explicitTimeMs = explicitTimeMs,
            contentTimeMs = contentTimeMs,
            totalTimeMs = System.currentTimeMillis() - startTime,
            explicitBreakdown = explicitBreakdown
        )
    }

    private suspend fun applyExplicitFilters(segmented: SegmentedQuery): Set<Long> {
        val (explicitFilter, _) = QuerySegmenter().toFilters(segmented)
        val candidateSets = mutableListOf<Set<Long>>()

        explicitFilter.timeRange?.let { range ->
            val ids = mediaDao.getMediaIdsByTimeRange(range.startMs, range.endMs).toSet()
            Logger.d(TAG, "Time filter [${range.startMs}, ${range.endMs}] -> ${ids.size} ids")
            candidateSets.add(ids)
        }

        for (keyword in explicitFilter.locationKeywords) {
            val ids = mediaDao.getMediaIdsByLocationKeyword(keyword).toSet()
            Logger.d(TAG, "Location filter [$keyword] -> ${ids.size} ids")
            candidateSets.add(ids)
        }

        if (explicitFilter.hasFaces == true) {
            val ids = mediaDao.getMediaIdsByHasFace().toSet()
            Logger.d(TAG, "Face filter -> ${ids.size} ids")
            candidateSets.add(ids)
        }

        // personKeywords 当前作为内容关键词处理（标签/OCR 中命中"小孩"等）
        // 若有 personId 索引，可扩展为 hasPersonId = true 的过滤

        return if (candidateSets.isEmpty()) {
            emptySet()
        } else {
            intersectCandidateSets(candidateSets)
                .also { Logger.d(TAG, "Candidate intersection -> ${it.size} ids") }
        }
    }

    private suspend fun searchContentInCandidates(
        segmented: SegmentedQuery,
        candidateIds: Set<Long>,
        uiLang: AppLanguage
    ): List<MediaAsset> {
        val limitedIds = candidateIds
            .sortedByDescending { it } // 假设 ID 越大越新（与自增主键一致）
            .take(MAX_CANDIDATE_SIZE)

        val resultMap = mutableMapOf<Long, MediaAsset>()

        for (segment in segmented.contentSegments) {
            val candidates = tagTranslator.expandForSearch(segment.text, uiLang)
            for (candidate in candidates) {
                searchCandidateInIds(candidate, limitedIds, resultMap)
            }
        }

        // 人物关键词也作为内容关键词在候选集内检索
        val (_, contentFilter) = QuerySegmenter().toFilters(segmented)
        for (keyword in contentFilter.keywords) {
            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                searchCandidateInIds(candidate, limitedIds, resultMap)
            }
        }

        return resultMap.values.sortedByDescending { it.captureDate }
    }

    private suspend fun searchContentGlobally(
        segmented: SegmentedQuery,
        uiLang: AppLanguage
    ): List<MediaAsset> {
        val resultMap = mutableMapOf<Long, MediaAsset>()

        val allContentSegments = segmented.contentSegments.map { it.text } +
            QuerySegmenter().toFilters(segmented).second.keywords

        for (text in allContentSegments.distinct()) {
            val candidates = tagTranslator.expandForSearch(text, uiLang)
            for (candidate in candidates) {
                searchCandidateGlobally(candidate, resultMap)
            }
        }

        return resultMap.values.sortedByDescending { it.captureDate }
    }

    private suspend fun searchCandidateInIds(
        candidate: String,
        ids: List<Long>,
        resultMap: MutableMap<Long, MediaAsset>
    ) {
        if (ids.isEmpty()) return
        val batches = chunkIds(ids)
        for (batch in batches) {
            if (tagDao != null) {
                tagDao.searchByExactTag(candidate).forEach { resultMap[it.id] = it.toDomain() }
            }
            if (ocrWordDao != null) {
                ocrWordDao.searchByWordPrefix(candidate.lowercase()).forEach { resultMap[it.id] = it.toDomain() }
            }
            mediaDao.searchLabelsInIds(batch, candidate).forEach { resultMap[it.id] = it.toDomain() }
            mediaDao.searchMlKitLabelsInIds(batch, candidate).forEach { resultMap[it.id] = it.toDomain() }
            mediaDao.searchOcrInIds(batch, candidate).forEach { resultMap[it.id] = it.toDomain() }
            mediaDao.searchFileNameInIds(batch, candidate).forEach { resultMap[it.id] = it.toDomain() }
        }
    }

    private suspend fun searchCandidateGlobally(
        candidate: String,
        resultMap: MutableMap<Long, MediaAsset>
    ) {
        if (tagDao != null) {
            tagDao.searchByExactTag(candidate).forEach { resultMap[it.id] = it.toDomain() }
        }
        if (ocrWordDao != null) {
            ocrWordDao.searchByWordPrefix(candidate.lowercase()).forEach { resultMap[it.id] = it.toDomain() }
        }
        mediaDao.searchByLabel(candidate).forEach { resultMap[it.id] = it.toDomain() }
        mediaDao.searchByMlKitLabel(candidate).forEach { resultMap[it.id] = it.toDomain() }
        mediaDao.searchByOcrText(candidate).forEach { resultMap[it.id] = it.toDomain() }
        mediaDao.searchByFileName(candidate).forEach { resultMap[it.id] = it.toDomain() }
    }
}

/**
 * 显式约束优先检索的诊断结果
 */
data class ExplicitFirstDiagnostics(
    val originalQuery: String,
    val segments: List<Segment>,
    val candidateCount: Int,
    val resultCount: Int,
    val explicitTimeMs: Long,
    val contentTimeMs: Long,
    val totalTimeMs: Long,
    val explicitBreakdown: List<ExplicitDimensionResult>
)

/**
 * 单个显式维度的命中统计
 */
data class ExplicitDimensionResult(
    val type: SegmentType,
    val text: String,
    val matchedCount: Int
)
```

- [ ] **Step 5.4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.ExplicitFirstSearchPipelineTest"`
Expected: 4 tests PASS

- [ ] **Step 5.5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipeline.kt
-git add app/src/test/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipelineTest.kt
-git commit -m "feat(search): add ExplicitFirstSearchPipeline"
```

---

## Task 6: 集成到 MediaSearchEngine

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt`

- [ ] **Step 6.1: 构造函数注入 QuerySegmenter 和 Pipeline**

修改 `MediaSearchEngine` 主构造函数：

```kotlin
class MediaSearchEngine(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao? = null,
    private val ocrWordDao: OcrWordDao? = null,
    private val locationDao: LocationDao? = null,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty()),
    private val semanticSearchEngine: SemanticSearchEngine? = null,
    private val querySegmenter: QuerySegmenter = QuerySegmenter(),
    private val explicitPipeline: ExplicitFirstSearchPipeline = ExplicitFirstSearchPipeline(
        mediaDao = mediaDao,
        tagDao = tagDao,
        ocrWordDao = ocrWordDao,
        locationDao = locationDao,
        tagTranslator = tagTranslator,
        semanticSearchEngine = semanticSearchEngine
    )
) {
```

- [ ] **Step 6.2: 修改 search() 方法**

替换 `search()` 方法体：

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
        if (!segmented.isEmpty) {
            val explicitResults = explicitPipeline.search(segmented, uiLang)
            if (explicitResults.isNotEmpty() || segmented.hasExplicit) {
                val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
                    searchSemantic(query, null)
                } else emptyList()
                val merged = mergeAndRank(explicitResults, semanticResults)
                return SearchResult(merged, query)
            }
        }

        // 回退：原有 Layer 1 / Layer 2 / 兜底模糊搜索
        return legacySearch(query, llmSearch, enableSemanticSearch, uiLang)
    }
```

- [ ] **Step 6.3: 提取 legacySearch 方法**

把原有 `search()` 方法体提取为 `legacySearch()`：

```kotlin
    private suspend fun legacySearch(
        query: String,
        llmSearch: (suspend (String) -> StructuredFilter?)? = null,
        enableSemanticSearch: Boolean = true,
        uiLang: AppLanguage
    ): SearchResult {
        // Layer 1: 规则匹配
        val filter = QueryParser.parse(query, uiLang)
        if (filter != null && !filter.needsLlm) {
            val results = executeFilter(filter)

            // Layer 2.5: 语义召回增强
            val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
                searchSemantic(query, filter)
            } else emptyList()

            // Layer 3: 融合排序
            val merged = mergeAndRank(results, semanticResults)
            return SearchResult(merged, query)
        }

        // Layer 2: LLM 解析
        if (llmSearch != null) {
            val llmFilter = llmSearch(query)
            if (llmFilter != null) {
                val results = executeFilter(llmFilter)

                val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
                    searchSemantic(query, llmFilter)
                } else emptyList()

                val merged = mergeAndRank(results, semanticResults)
                return SearchResult(merged, query)
            }
        }

        // 回退：全字段模糊搜索 + 语义召回
        val queryCandidates = tagTranslator.expandForSearch(query, uiLang)
        val sqlResults = queryCandidates
            .flatMap { mediaDao.searchAll(it) }
            .map { it.toDomain() }
            .distinct()

        val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
            searchSemantic(query, null)
        } else emptyList()

        val merged = mergeAndRank(sqlResults, semanticResults)
        return SearchResult(merged, query)
    }
```

- [ ] **Step 6.4: 扩展 searchWithDiagnostics**

修改 `searchWithDiagnostics()`，新增分段路径的诊断信息：

```kotlin
    suspend fun searchWithDiagnostics(
        query: String,
        enableSemanticSearch: Boolean = true
    ): SearchDiagnosticsResult {
        val totalStart = System.currentTimeMillis()
        val uiLang = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

        // 尝试新路径
        val segmented = querySegmenter.segment(query)
        if (!segmented.isEmpty) {
            val explicitDiagnostics = explicitPipeline.searchWithDiagnostics(segmented, uiLang)
            val explicitResults = if (segmented.hasExplicit && explicitDiagnostics.candidateCount == 0) {
                emptyList()
            } else {
                explicitPipeline.search(segmented, uiLang)
            }

            val semanticStart = System.currentTimeMillis()
            val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    semanticSearchEngine.searchByText(query, null, topK = 50)
                        .map { DiagnosticSemanticItem(media = it.media, score = it.score) }
                } catch (e: Exception) {
                    Logger.w(TAG, "Diagnostic semantic search failed", e)
                    emptyList()
                }
            } else emptyList()
            val semanticRecallTimeMs = System.currentTimeMillis() - semanticStart

            val mergeStart = System.currentTimeMillis()
            val scoredMerged = mergeAndRankWithScores(
                explicitResults,
                semanticResults.map { SemanticScoredMedia(it.media, it.score) }
            )
            val mergeTimeMs = System.currentTimeMillis() - mergeStart

            val sqlItems = explicitResults.map { DiagnosticMediaItem(it, 0f, emptyList()) }
            val mergedItems = scoredMerged.map { scored ->
                DiagnosticMediaItem(
                    media = scored.media,
                    score = scored.score,
                    matchDimensions = listOf("explicit_first")
                )
            }

            return buildDiagnosticsResult(
                query = query,
                parsedFilter = null,
                usedLlm = false,
                llmFilter = null,
                parseTimeMs = explicitDiagnostics.explicitTimeMs + explicitDiagnostics.contentTimeMs,
                sqlRecallTimeMs = explicitDiagnostics.totalTimeMs,
                sqlResults = sqlItems,
                semanticResults = semanticResults,
                mergedResults = mergedItems,
                recallBreakdown = listOf(
                    RecallDimension("Explicit", explicitDiagnostics.candidateCount, explicitDiagnostics.explicitTimeMs),
                    RecallDimension("Content", explicitDiagnostics.resultCount, explicitDiagnostics.contentTimeMs)
                ) + listOf(
                    RecallDimension("Semantic", semanticResults.size, semanticRecallTimeMs)
                ),
                totalStart = totalStart,
                enableSemanticSearch = enableSemanticSearch,
                semanticEngineReady = semanticSearchEngine?.isReady ?: false,
                semanticCandidateCount = -1,
                mergeTimeMs = mergeTimeMs
            )
        }

        // 原有诊断路径保持不变
        val parseStart = System.currentTimeMillis()
        val filter = QueryParser.parse(query, uiLang)
        val parseTimeMs = System.currentTimeMillis() - parseStart

        if (filter != null && !filter.needsLlm) {
            return executeDiagnosticsSearch(
                query = query,
                filter = filter,
                usedLlm = false,
                llmFilter = null,
                parseTimeMs = parseTimeMs,
                totalStart = totalStart,
                enableSemanticSearch = enableSemanticSearch,
                uiLang = uiLang
            )
        }

        // 兜底诊断路径保持不变
        return executeDiagnosticsFallback(query, parseTimeMs, totalStart, uiLang)
    }
```

- [ ] **Step 6.5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt
-git commit -m "feat(search): integrate explicit-first segmented search into MediaSearchEngine"
```

---

## Task 7: 修复编译问题

- [ ] **Step 7.1: 运行编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7.2: 运行所有相关单元测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.*"`
Expected: BUILD SUCCESSFUL, all tests PASS

- [ ] **Step 7.3: Commit**

```bash
git add -A
git commit -m "fix(search): resolve compile issues after MediaSearchEngine integration"
```

---

## Task 8: 文档同步

- [ ] **Step 8.1: 更新 app/AGENTS.md**

在 `app/AGENTS.md` 中搜索架构相关段落追加：

```markdown
- 自然语言搜索新增显式约束优先的分段联合检索：`QuerySegmenter` 切分时间/地点/人物/物体/场景/OCR 等语义段，`ExplicitFirstSearchPipeline` 先对显式段取交集得到候选集，再在候选集内检索内容段。
```

- [ ] **Step 8.2: Commit**

```bash
git add app/AGENTS.md
git commit -m "docs(app): update AGENTS.md for explicit-first segmented search"
```

---

## 自审清单

| Spec 要求 | 对应任务 |
|-----------|----------|
| `SegmentType` / `Segment` / `SegmentedQuery` 数据模型 | Task 1 |
| `QuerySegmenter` 分词分段 | Task 2 |
| "去年3月"等细粒度时间解析 | Task 3 |
| 候选集内 `MediaDao` 查询 | Task 4 |
| `ExplicitFirstSearchPipeline` 显式约束优先 | Task 5 |
| `MediaSearchEngine` 集成新路径 | Task 6 |
| 失败回退到原有路径 | Task 6 |
| 诊断信息展示 | Task 6 |
| 单元测试覆盖 | Task 2, 3, 5 |
| 文档同步 | Task 8 |

---

> **维护者**：RD Agent
> **状态**：待执行
> **依赖设计文档**：`docs/superpowers/specs/2026-06-30-explicit-first-segmented-search-design.md`
