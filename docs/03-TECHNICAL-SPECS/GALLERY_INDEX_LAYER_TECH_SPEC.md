# 智能相册多维度索引层技术方案（2026-06）

## 1. 概述

PicMe 的 Agent Runtime 已搭好"上层建筑"（`AgentOrchestrator` + `CapabilityRegistry` + 端侧/远程 LLM Engine），但支撑语义搜索的"经济基础"——本地多维度索引层——此前只有单表 `media_assets` 的 LIKE 模糊搜索。用户说出"找出去年夏天在北京拍的猫的照片"，系统无法将其映射为跨时间/地点/标签/OCR 的结构化查询。

本次建设围绕 `docs/Gallery.md` 列出的 8 个缺失维度，按 P0→P1→P2 优先级分三阶段落地，核心目标：

- **P0**：建立关系化持久化层 + 增量索引监听，替代单表 LIKE 扫描
- **P1**：补齐人脸聚类/OCR 倒排/场景标签/层级地理四维语义索引
- **P2**：优化万级图库缩略图性能 + 构建 Agent 搜索桥接（NL → StructuredFilter → 跨维度 DAO 并发查询 → 排序）

全部处理 100% 端侧执行，零云端依赖，符合项目 `[PRIVACY]` 红线。

## 2. 架构总览

### 2.1 分层架构

```
┌─────────────────────────────────────────────────┐
│  features/gallery/                               │
│  GalleryScreen · MediaViewModel · GalleryCapability │
├─────────────────────────────────────────────────┤
│  domain/                                         │
│  ├─ search/   QueryBuilder · SearchRanker        │
│  │            MediaSearchEngine · QueryParser    │
│  ├─ model/    StructuredFilter · GroupingMode    │
│  └─ repository/  MediaRepository (接口)           │
├─────────────────────────────────────────────────┤
│  data/                                           │
│  ├─ local/    AppDatabase v7 · 实体 · DAO · FTS5 │
│  ├─ indexing/ MediaStoreObserver · TaskQueue     │
│  │            IndexUpdaters · FaceClusterer      │
│  └─ repository/ MediaRepositoryImpl              │
├─────────────────────────────────────────────────┤
│  di/  AppContainer (手动 DI 装配)                 │
└─────────────────────────────────────────────────┘
```

### 2.2 数据流全景

```
MediaStore 变化
    │
    ▼
MediaStoreObserver (ContentObserver, 2s 去抖)
    │
    ▼
IndexingTaskQueue (去重批处理, 20条/批)
    │
    ├──▶ MediaIndexingWorker (增量 / 全量)
    │       │
    │       ├── ML Kit ImageLabeler ──▶ TagIndexUpdater ──▶ tags + media_tag_cross_ref
    │       ├── ML Kit OCR ──────────▶ OcrIndexUpdater ──▶ ocr_words + ocr_word_occurrences
    │       └── EXIF GPS + Geocoder ─▶ LocationIndexUpdater ──▶ location_hierarchy + media_locations
    │
    └──▶ FaceClusteringWorker
            │
            ├── ML Kit Face Detection (ROI)
            ├── MNN MobileFaceNet (512-dim embedding)
            └── IncrementalFaceClusterer (余弦距离匹配 / DBSCAN)
                    │
                    ▼
              persons + face_embeddings
```

**搜索数据流**：

```
用户输入: "去年夏天在北京拍的猫的照片"
    │
    ▼
QueryParser (规则引擎, 本地毫秒级)
    │  时间词: "去年夏天" → TimeRange(startMs, endMs)
    │  地点词: "北京" → locationKeywords=["北京"]
    │  内容词: "猫" → keywords=["猫"]
    │
    ▼
StructuredFilter { timeRange, keywords, locationKeywords, ... }
    │
    ▼
QueryBuilder.search(filter)
    │  并发查询 (coroutineScope async):
    │  ├── tagDao.searchByExactTag("猫")
    │  ├── tagDao.searchByTagName("猫")
    │  ├── ocrWordDao.searchByExactWord("猫")
    │  ├── locationDao.searchByPlace("北京")
    │  ├── mediaDao.searchByTimeRange(startMs, endMs)
    │  └── ... (legacy LIKE 兼容)
    │
    ▼
SearchRanker (加权评分 + 多维度 Boost + 时间衰减)
    │
    ▼
List<ScoredMedia> (按 score DESC, captureDate DESC)
    │
    ▼
GalleryCapability → AgentAction.TextReply("找到 12 张匹配照片")
```

## 3. 数据库 Schema 设计

### 3.1 迁移策略

从 v6 到 v7，使用 `CREATE TABLE IF NOT EXISTS` 增量迁移，**不修改**现有 `media_assets` 表结构。旧数据（`faceId` 字符串、`labels` JSON 数组、`ocrText` 全文）继续可用，后台重索引逐步迁移到规范化表。

AppDatabase v7 共注册 11 个实体：

```
MediaEntity, ChatMessageEntity, ChatSessionEntity,    // 已有
PersonEntity, FaceEmbeddingEntity,                     // 人脸聚类
TagEntity, MediaTagCrossRef,                           // 场景标签
OcrWordEntity, OcrWordOccurrence,                      // OCR 倒排
LocationHierarchyEntity, MediaLocationEntity            // 层级地理
```

### 3.2 表结构详解

#### 3.2.1 persons — 人物去重表

| 列 | 类型 | 说明 |
|---|---|---|
| `personId` | INTEGER PK AUTO | 人物唯一 ID |
| `name` | TEXT | 人物名称（可空，后续由用户命名或自动生成） |
| `coverMediaId` | INTEGER | 封面照片 ID |
| `faceCount` | INTEGER | 关联人脸数（冗余计数器，加速排序） |
| `createdAt` / `updatedAt` | INTEGER | 时间戳 |

#### 3.2.2 face_embeddings — 人脸向量表

| 列 | 类型 | 说明 |
|---|---|---|
| `embeddingId` | INTEGER PK AUTO | embedding 唯一 ID |
| `mediaId` | INTEGER FK→media_assets.id | 所属媒体 |
| `personId` | INTEGER FK→persons.personId | 归属人物（可为 NULL = 未聚类） |
| `embedding` | BLOB | MobileFaceNet 512 维 float32 向量（小端序，2048 字节） |
| `createdAt` | INTEGER | 创建时间戳 |

索引：`idx_face_embeddings_person`, `idx_face_embeddings_media`

**设计要点**：
- embedding 按 BLOB 存储（非 JSON），节省 70%+ 空间且无序列化开销
- `personId` 可为 NULL，支持"先存 embedding 后聚类"的异步流程
- 外键 `ON DELETE SET NULL`：删除人物时 embedding 不丢失，可重聚类

#### 3.2.3 tags + media_tag_cross_ref — 标签 M:N

**tags**:

| 列 | 类型 | 说明 |
|---|---|---|
| `tagId` | INTEGER PK AUTO | 标签 ID |
| `name` | TEXT UNIQUE | 标签名（如"猫"、"户外"） |
| `category` | TEXT DEFAULT 'scene' | 分类：scene/animal/object/food/person/other |

**media_tag_cross_ref**:

| 列 | 类型 | 说明 |
|---|---|---|
| `mediaId` + `tagId` | COMPOUND PK | M:N 关联 |
| `confidence` | REAL | ML Kit 置信度（0~1） |

分类由 `TagIndexUpdater` 根据内置映射表自动推断（约 50 个常见标签覆盖 scene/animal/food/person/object 五类）。

#### 3.2.4 ocr_words + ocr_word_occurrences — OCR 倒排索引

**ocr_words**:

| 列 | 类型 | 说明 |
|---|---|---|
| `wordId` | INTEGER PK AUTO | 词汇 ID |
| `word` | TEXT | 原始词汇 |
| `normalizedWord` | TEXT | 归一化词汇（NFC 标准化 + 小写） |

**ocr_word_occurrences**:

| 列 | 类型 | 说明 |
|---|---|---|
| `wordId` + `mediaId` | COMPOUND PK | 倒排命中 |
| `confidence` | REAL | OCR 置信度 |
| `boundingBox` | TEXT | 文字区域坐标（JSON） |

索引：`idx_ocr_occurrence_media`（加速"某张图有哪些文字"的查询）

**分词策略**：中文按 bigram + 单字双重索引，英文按空格/标点分割，归一化做 NFC + lowercase。例如 "你好世界" → `["你","你好","好","好世","世","世界","界"]`。bigram 兼顾召回率和精确度，单字保证单字符查询也能命中。

**去孤立词**：`cleanupOrphanWords()` 定期清理无引用的词汇，防止无限膨胀。

#### 3.2.5 location_hierarchy + media_locations — 层级地理索引

**location_hierarchy**:

| 列 | 类型 | 说明 |
|---|---|---|
| `locationId` | INTEGER PK AUTO | 位置 ID |
| `country` | TEXT | 国家 |
| `province` | TEXT | 省/州 |
| `city` | TEXT | 城市 |
| `district` | TEXT | 区/县 |
| `poi` | TEXT | POI 名称 |
| `latitude` / `longitude` | REAL | 坐标（4 位小数精度） |

索引：`idx_location_hierarchy_city`, `idx_location_hierarchy_province`

**去重策略**：坐标舍入到 4 位小数（约 11 米精度），相同坐标复用同一 locationId，避免存储爆炸。

#### 3.2.6 media_fts — FTS5 全文搜索虚拟表

```sql
CREATE VIRTUAL TABLE media_fts USING fts5(
    uri, fileName, labels, ocrText, locationName,
    content='media_assets', content_rowid='id',
    tokenize='unicode61'
);
```

通过三个触发器与 `media_assets` 保持同步：
- `media_fts_ai` (AFTER INSERT)：自动写入新记录
- `media_fts_ad` (AFTER DELETE)：自动删除
- `media_fts_au` (AFTER UPDATE)：delete + insert 重建

FTS5 的 `unicode61` tokenizer 支持中文 Unicode 字符，配合应用层 bigram 分词，比 `LIKE '%keyword%'` 快 10x–100x。

**注意**：部分厂商 ROM 的 SQLite 未编译 FTS5 模块。Migration 中 FTS5 创建和触发器包含在 try-catch 中，创建失败时静默跳过，搜索自动回退到 DAO 层 LIKE 查询，功能无损。

## 4. 增量索引与变更监听

### 4.1 MediaStoreObserver

封装 `ContentObserver`，注册到 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 和 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`。

**去抖机制**：每次 `onChange` 累积 URI → 2 秒 debounce → 批量 flush 到 `IndexingTaskQueue`。

**变更检测**：收到 URI 后通过 `ContentResolver.query()` 检查记录是否存在，区分 `ADDED_OR_UPDATED` 和 `DELETED`。

生命周期由 GalleryScreen 管理（进入注册，离开注销）。

### 4.2 IndexingTaskQueue

`ConcurrentLinkedQueue` 实现的无锁任务队列：

- **去重**：同一 `mediaStoreId` 只处理一次（以最新事件类型为准）
- **批量**：每 5 秒或每 20 条触发一次处理
- **异步**：处理在 `Dispatchers.IO` 协程中执行，不阻塞主线程

### 4.3 MediaIndexingWorker 增量模式

在原 `start()` 全量扫描基础上新增 `indexIncremental(uris: List<Uri>)` 方法：

1. 逐 URI 调用 `MetadataExtractor.extract()`
2. 写入 `media_assets` 表（新增或更新）
3. 同步调用 `OcrIndexUpdater` / `TagIndexUpdater` / `LocationIndexUpdater` 更新规范化表

### 4.4 人脸聚类增量模式

`IncrementalFaceClusterer` 支持两种聚类模式：

**增量模式**（常规路径）：
1. ML Kit 检测人脸 ROI → MNN MobileFaceNet 提取 512 维 embedding
2. 存储到 `face_embeddings` 表
3. 与已有 person 的质心（均值向量）做余弦距离匹配（eps=0.55）
4. 匹配成功 → 归入已有 person；无匹配 → 创建新 person

**全量重聚**（校准路径）：
- 每累计 50 个增量 embedding 触发一次完整 DBSCAN 重聚
- 保证增量模式不因质心漂移而逐渐劣化

**质心计算**：person 下所有 embedding 的算术平均 → L2 归一化。新 person 的初始质心即其首个 embedding。

**embedding 序列化**：FloatArray → ByteArray 小端序（512 floats × 4 bytes = 2048 bytes），存为 Room BLOB。反序列化时逐 4 字节读回 float。

## 5. Agent 搜索桥接

### 5.1 整体链路

```
用户自然语言
    │
    ▼
┌──────────────────┐
│ Layer 1: QueryParser (规则引擎, ~1ms)      │
│  时间词: 去年/夏天/上个月/昨天...          │
│  地点词: 城市名/POI (内置 80+ 中国城市)    │
│  关键词提取: 去停用词 + 分词               │
└──────────────────┘
    │ 规则命中?
    ├── YES ──▶ StructuredFilter
    │
    ▼ NO
┌──────────────────┐
│ Layer 2: LLM 解析 (LocalLlmEngine / Remote LLM) │
│  prompt: "你是图片搜索助手..."             │
│  output: JSON → StructuredFilter          │
└──────────────────┘
    │
    ▼
StructuredFilter {
    timeRange: TimeRange?,
    keywords: List<String>,       // 内容词
    ocrKeywords: List<String>,    // OCR 词
    locationKeywords: List<String>, // 地点词
    personName: String?,
    hasFaces: Boolean?
}
    │
    ▼
QueryBuilder.search(filter)
    │  coroutineScope { async { ... } }
    │  并发查询 6 个 DAO 的精确 + 模糊方法
    │  + legacy LIKE 兼容
    ▼
Map<MediaEntity, Set<命中维度>>
    │
    ▼
SearchRanker.rank()
    加权评分 + 多维度 Boost + 时间衰减
    │
    ▼
List<ScoredMedia> (score DESC, captureDate DESC)
```

### 5.2 QueryParser 增强

在原时间词解析基础上新增：

**地点词检测**：内置 80+ 中国城市及 POI 关键词（北京/上海/三里屯/陆家嘴/西湖...），自动分类到 `locationKeywords`。

**关键词分类**：`extractCategorizedKeywords()` 返回 `Pair<List<String>, List<String>>`（内容词, 地点词）。

### 5.3 QueryBuilder —— 跨维度并发查询

核心方法是 `search(filter: StructuredFilter): List<ScoredMedia>`：

1. 为 filter 中每个非空维度生成 `async {}` 子查询
2. 所有子查询并发执行（利用 Room 的线程池）
3. 结果按 `MediaEntity` 合并，记录命中维度名
4. 时间范围作为最终过滤器
5. 交给 `SearchRanker` 排序

**并发安全**：每个 DAO 方法都是 `suspend`，Room 内部使用 `TransactionExecutor` 保证单写入者/多读取者的线程安全。子查询之间无共享可变状态，天然并发安全。

### 5.4 SearchRanker —— 加权评分

| 维度 | 匹配方式 | 权重 |
|---|---|---|
| tag 精确匹配 | `tagDao.searchByExactTag()` | 1.0 |
| OCR 精确匹配 | `ocrWordDao.searchByExactWord()` | 0.9 |
| 地点精确匹配 | `locationDao.searchByPlace()` | 0.8 |
| tag 模糊匹配 | `tagDao.searchByTagName()` | 0.7 |
| OCR 前缀匹配 | `ocrWordDao.searchByWordPrefix()` | 0.6 |
| 地点模糊匹配 | `mediaDao.searchByLocation()` | 0.5 |
| 文件名匹配 | `mediaDao.searchByFileName()` | 0.4 |
| 时间范围匹配 | `mediaDao.searchByTimeRange()` | 0.3 |
| 有人脸 | `mediaDao.searchByHasFace()` | 0.5 |

**Boosts**：
- 多维度命中：`score *= (1 + 0.2 × N)` — 命中维度越多，信号越强
- 时间衰减：最近 30 天的照片 +0.1 — 用户通常更关心近期照片

### 5.5 LLM Prompt 设计

`MediaSearchEngine.buildLlmSearchPrompt()` 构造结构化 prompt，示例：

```
你是一个图片搜索助手。请将用户的自然语言查询转换为结构化过滤条件。

用户查询："找出去年夏天在北京拍的有猫的照片"

请以 JSON 格式返回过滤条件：
{
  "timeRange": {"startMs": ..., "endMs": ...},
  "keywords": ["猫"],
  "ocrKeywords": [],
  "locationKeywords": ["北京"],
  "hasFaces": false,
  "explanation": "..."
}
当前年份：2026，当前月份：6
```

本地模型（Qwen3.5-2B）因能力边界限制，复杂查询通过 `QueryParser` 规则引擎兜底。远程模型（DeepSeek）可处理更复杂的多步推理查询。

## 6. 缩略图加载策略

### 6.1 Coil 显式配置

```kotlin
MemoryCache: 25% heap (约 200-300 张缓存缩略图)
DiskCache:   250MB (约 10,000 张压缩缩略图)
BitmapConfig: 默认 ARGB_8888 (保留色彩精度用于相册浏览)
respectCacheHeaders: false (本地 URI 无 HTTP headers)
```

### 6.2 ThumbnailPrefetcher

在 `LazyVerticalGrid` 滚动时，计算可见区域前后的 item 列表（前后各约 3 页），批量调用 `ImageLoader.enqueue()` 预加载，减少滑动白块闪烁。

## 7. 代码落点

### 7.1 新建文件（22 个）

```
app/src/main/java/com/mamba/picme/
├── data/local/
│   ├── entity/
│   │   ├── PersonEntity.kt
│   │   ├── FaceEmbeddingEntity.kt
│   │   ├── TagEntity.kt
│   │   ├── MediaTagCrossRef.kt
│   │   ├── OcrWordEntity.kt
│   │   ├── OcrWordOccurrence.kt
│   │   ├── LocationHierarchyEntity.kt
│   │   └── MediaLocationEntity.kt
│   ├── dao/
│   │   ├── TagDao.kt
│   │   ├── OcrWordDao.kt
│   │   ├── PersonDao.kt
│   │   ├── LocationDao.kt
│   │   └── FtsSearchDao.kt
│   └── GalleryDatabaseMigrations.kt
├── data/indexing/
│   ├── MediaStoreObserver.kt
│   ├── IndexingTaskQueue.kt
│   ├── OcrIndexUpdater.kt
│   ├── TagIndexUpdater.kt
│   ├── LocationIndexUpdater.kt
│   └── IncrementalFaceClusterer.kt
├── domain/model/StructuredFilter.kt
├── domain/search/QueryBuilder.kt
├── domain/search/SearchRanker.kt
└── core/image/ThumbnailPrefetcher.kt
```

### 7.2 修改文件（8 个）

| 文件 | 变更内容 |
|---|---|
| `data/local/AppDatabase.kt` | v6→v7，新增 8 实体 + 6 DAO 抽象方法 |
| `di/AppContainer.kt` | 暴露 QueryBuilder、ThumbnailPrefetcher、createMediaStoreObserver |
| `domain/search/MediaSearchEngine.kt` | 接收新 DAO 参数，多维度搜索，LLM prompt 扩展 |
| `domain/search/QueryParser.kt` | 迁移到 domain/model 类型，新增地点词检测 |
| `data/indexing/MediaIndexingWorker.kt` | 新增增量索引模式 `indexIncremental()` |
| `features/gallery/capability/GalleryCapability.kt` | 新增 `queryBuilder` 属性 |
| `core/image/CoilConfig.kt` | 显式配置 memory/disk cache |
| `PicMeApplication.kt` | 注入 `queryBuilder` 到 GalleryCapability |

## 8. 关键设计决策

### 8.1 新旧双轨兼容

旧表字段（`labels` JSON、`ocrText` 全文、`faceId` 字符串）**不删除**，保持向后兼容。MediaSearchEngine 同时查询新 DAO（优先）和旧 LIKE 方法（兜底），确保迁移期间搜索不中断。

### 8.2 后台重索引

现有数据通过 `MediaIndexingWorker.start()` 全量扫描逐步迁移。`indexedAt` 字段保证断点续扫。迁移完成后，`getUnindexedMedia()` 返回空集。

### 8.3 自定义协程 Worker（非 WorkManager）

延续项目现有模式（`FaceClusteringWorker`、`MediaIndexingWorker`），不引入 WorkManager 依赖。Worker 使用 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，生命周期由 Application / ViewModel 管理。

### 8.4 非 WorkManager 的技术权衡

**选择**：自定义协程 Worker。

**原因**：
- 项目全链路（AI 推理、模型下载、美颜）均使用协程，保持一致性
- 避免引入 WorkManager 及其依赖（~500KB）
- 索引是应用内在行为，不需要跨应用/跨重启调度（MediaStoreObserver 覆盖实时变更，前台进入时触发增量索引）

**局限**：
- 进程被杀后不在后台自行恢复索引
- 大量图片批量导入时的性能不如 WorkManager 的约束条件调度

权衡结论：对相册场景（< 10k 图片），增量索引已覆盖 90%+ 场景；全量索引仅在用户切换设备或清数据时触发一次。

## 9. 性能预估

| 指标 | 目标 | 设计依据 |
|---|---|---|
| 全量索引（10k 图片） | < 10 分钟 | Batch 20 × 每张 ~3s (ML Kit + OCR + geocoder) = 60s/batch, 约 500 batch |
| 增量索引（单张） | < 3s | ML Kit 三模型 + geocoder 顺序调用 |
| FTS5 关键词搜索 | < 50ms @ 10k 行 | FTS5 使用 prefix 查询，不走全表扫描 |
| DAO 跨维度并发搜索 | < 100ms | 6 路 async 并发，取最慢一路 |
| 人脸 embedding 存储 | ~2KB/人 | 512 floats × 4 bytes = 2048 bytes |
| 磁盘缓存 | 250MB | 约缓存 10k 张缩略图（每张 ~25KB 压缩后） |
| 内存缓存 | 25% heap | 约 200–300 张缓存缩略图 |

## 10. 风险与缓解

| 风险 | 可能性 | 影响 | 缓解策略 |
|---|---|---|---|
| DB migration v6→v7 数据丢失 | 低 | P0 数据不可逆 | CREATE TABLE IF NOT EXISTS，不修改旧表结构 |
| ContentObserver 耗电 | 中 | 后台电量异常 | 2s 去抖 + 5s 批处理间隔，前台 Gallery 页才注册 |
| FTS5 中文分词不准 | 中 | 搜索召回率下降 | unicode61 tokenizer + 应用层 bigram 分词双路径 |
| 增量人脸聚类漂移 | 中 | 人物混淆 | 每 50 增量触发全量 DBSCAN 重聚校准 |
| 新表写入阻塞 UI | 低 | 掉帧 | 所有索引写入在 Dispatchers.IO 执行 |
| ML Kit 模型未就绪 | 中 | 索引空白 | waitForModelReady() 预热 + 后台自动下载 + indexedAt=-1 标记避免死循环 |
| Geocoder 服务不可用 | 低 | 地理索引为空 | Android Geocoder 内置于系统，无网络也能返回缓存结果 |

## 11. 后续扩展

### 11.1 短期（1–2 周）

- `IncrementalFaceClusterer` 集成到 `FaceClusteringWorker` 增量路径
- `MediaStoreObserver` 绑定到 `GalleryScreen` 生命周期
- `MediaGrid` 集成 `ThumbnailPrefetcher`

### 11.2 中期（2–4 周）

- `GetGroupedMediaUseCase` 接入 TAG/LOCATION/OCR/PERSON 新分组模式
- `LocalPromptBuilder` gallery schema 段增加多维度指令示例
- 索引进度指示器 UI（`gallery_index_status` 字符串资源已就绪）

### 11.3 长期（4–8 周）

- 端侧场景分类模型 MobileViT/EdgeNeXt 替代 ML Kit 标签（更细粒度）
- 图片语义 Embedding（CLIP 端侧版）结合向量检索实现"氛围/风格"级别搜索
- 主动索引建议（Agent 提醒用户"刚拍了 3 张猫的照片，是否创建'猫'相册？"）

## 12. 相关文档

| 文档 | 说明 |
|---|---|
| `docs/Gallery.md` | 智能相册索引缺失维度分析（出发点） |
| `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` | Agent Runtime 架构设计 |
| `docs/02-ARCHITECTURE/ADR/ADR-005-local-remote-pipeline-separation.md` | 本地/远程推理协议分离 |
| `docs/03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md` | 人脸检测引擎架构 |
| `docs/03-TECHNICAL-SPECS/ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md` | 端侧图像理解模型调研 |
