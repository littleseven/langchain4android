# MobileCLIP 端侧语义编码引入与 VLM 语义搜索规划

> **状态**: 已实施 (Pass 4 编码完成，语义搜索 Phase 1-2 待启动)  
> **日期**: 2026-06-27  
> **决策**: RD  
> **依赖**: ADR-007（自然语言相册搜索）、AUTO_TAG_GENERATION_SPEC（4-Pass 混合扫描管道）

---

## 1. 背景与问题陈述

### 1.1 现有搜索架构的局限

PicMe 当前搜索基于 **ADR-007** 的 CV 标签 + LLM 语义解析双层架构：

- **Layer 1 (规则匹配)**：时间词/关键词 → SQLite `LIKE` 查询（< 100ms）
- **Layer 2 (LLM 解析)**：复杂查询 → Agent Runtime → `search_media` 命令

该架构存在两个根本局限：

| 问题 | 表现 | 根因 |
|------|------|------|
| **标签覆盖不足** | 用户搜"温馨的氛围"找不到照片 | ML Kit/Qwen 标签是离散词表，无法表达连续语义 |
| **跨模态语义鸿沟** | 搜"夕阳下的海边" vs 实际标签是"户外、海滩、日落" | 文本查询与图像标签的语义空间不一致 |

### 1.2 为什么需要 MobileCLIP

**CLIP（Contrastive Language-Image Pre-training）** 将图像和文本映射到同一语义空间，通过余弦相似度直接匹配。MobileCLIP 是 Apple 发布的轻量化变体，专为移动端优化：

| 特性 | MobileCLIP-S0 | 说明 |
|------|---------------|------|
| 模型大小 | ~45MB (vision) + ~170MB (text) | 端侧可承受 |
| 推理速度 | ~50-100ms/张 (vision) | MNN 后端，CPU 即可 |
| 输出维度 | 512 维 L2 归一化 | 与 MobileFaceNet 同维度，便于统一存储 |
| 语义能力 | 零样本图像分类、图文检索 | 无需预定义标签词表 |

**引入 MobileCLIP 后，搜索从"标签匹配"升级为"语义相似度匹配"**：

```
用户输入: "温馨的家庭聚餐"
    │
    ▼
Text Encoder → 512-dim text embedding
    │
    ▼
与所有照片的 image embedding 计算余弦相似度
    │
    ▼
Top-K 结果（无需照片有"家庭"或"聚餐"标签）
```

### 1.3 约束

- **[PRIVACY]**：所有 embedding 计算端侧完成，不上传照片或文本
- **[PERF]**：单次图像编码 < 200ms，文本编码 < 50ms，Top-100 相似度搜索 < 100ms
- **[存储]**：每张照片 512 维 float32 = 2KB，万级图库约 20MB，可接受
- **[复用]**：复用已有 MNN 推理栈、模型中心下载系统、Room 数据库

---

## 2. MobileCLIP 引入方案

### 2.1 模型来源与文件清单

从 ModelScope (`budaoshou/MobileCLIP-MNN`) 下载 MNN 转换版模型：

| 文件名 | 大小 | 用途 | 必需 |
|--------|------|------|------|
| `vision_model.mnn` | ~45MB | 图像编码器 | ✅ |
| `text_model.mnn` | ~170MB | 文本编码器 | ✅ |
| `configuration.json` | 52B | 模型配置 | ✅ |
| `tokenizer.json` | ~2.2MB | Tokenizer 数据 | ✅ |
| `tokenizer_config.json` | 763B | Tokenizer 配置 | ✅ |
| `vocab.txt` | ~763KB | 词汇表 | ✅ |
| `merges.txt` | ~524KB | BPE merges | ✅ |

> 存储路径：`/sdcard/Android/data/com.mamba.picme/files/llm_models/mobileclip-mnn/`

### 2.2 引擎架构

```
app/src/main/java/com/mamba/picme/domain/tag/
├── MobileClipEngine.kt          # Kotlin 封装层
├── MobileClipEncoder.java       # JNI 桥接层 (beauty-engine 模块)
└── TagGenerationPipeline.kt     # 集成 Pass 4
```

**MobileClipEngine** 职责：
- 模型存在性检查（`isModelReady`）
- MNN 模型加载/卸载（`initialize()` / `release()`）
- 图像编码 → 512 维 `FloatArray`（`encodeImage()`）
- 文本编码 → 512 维 `FloatArray`（`encodeText()`）
- 余弦相似度计算（`cosineSimilarity()`）

```kotlin
class MobileClipEngine(context: Context) {
    fun initialize(useGpu: Boolean = false): Boolean
    fun encodeImage(bitmap: Bitmap): FloatArray?      // 512-dim, L2-normalized
    fun encodeText(tokenIds: LongArray): FloatArray?   // 512-dim, L2-normalized
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float
    fun release()
}
```

### 2.3 4-Pass 混合扫描管道（新增 Pass 4）

现有管道（AUTO_TAG_GENERATION_SPEC）已包含 3 个 Pass：

```
Pass 1: Face ROI + MobileFaceNet Embedding
Pass 2: DBSCAN 人脸聚类
Pass 3: Qwen3.5-2B 图像理解标签生成
```

**新增 Pass 4: MobileCLIP 语义编码**

```
┌─────────────────────────────────────────────────────────────┐
│  Pass 4: MobileCLIP Semantic Encoding                        │
│                                                              │
│  输入: 已有 labels 的照片（Pass 3 已完成）                    │
│  处理: MobileClipEngine.encodeImage(bitmap) → 512-dim embedding│
│  存储: Base64(float32[]) → media_assets.semanticEmbedding    │
│  输出: 每张照片的语义向量，供后续语义搜索使用                  │
└─────────────────────────────────────────────────────────────┘
```

**执行时机**：Pass 3 完成后，仅对 `labels IS NOT NULL AND semanticEmbedding IS NULL` 的照片执行。

**数据库存储**：

```kotlin
// MediaEntity.kt (v4 migration)
@Entity(tableName = "media_assets")
data class MediaEntity(
    // ... 已有字段 ...
    
    // Pass 4: MobileCLIP 语义 embedding（512 维 FloatArray 的 Base64）
    val semanticEmbedding: String? = null,
)
```

Base64 编码方案（小端序 float32 → byte[] → Base64.NO_WRAP）：

```kotlin
private fun floatArrayToBase64(array: FloatArray): String {
    val bytes = ByteArray(array.size * 4)
    for (i in array.indices) {
        val bits = java.lang.Float.floatToRawIntBits(array[i])
        bytes[i * 4] = (bits shr 24).toByte()
        bytes[i * 4 + 1] = (bits shr 16).toByte()
        bytes[i * 4 + 2] = (bits shr 8).toByte()
        bytes[i * 4 + 3] = bits.toByte()
    }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}
```

### 2.4 调度器集成

`TagGenerationScheduler.scanAll()` 已扩展为 4-Pass：

```kotlin
// Pass 4: MobileCLIP 语义编码
val needSemantic = dao.getMediaNeedingSemanticEncoding()
for (entity in needSemantic) {
    val embedding = pipeline.stage4MobileClipEncoding(entity.uri, entity.id)
    if (embedding != null) {
        dao.updateSemanticEmbedding(entity.id, embedding)
    }
}
```

**并发控制**：
- MobileCLIP vision 推理单线程即可（~50-100ms/张）
- 与 Qwen (Pass 3) 不共享 GPU 资源，可安全并发
- 最后释放资源：`pipeline.releaseMobileClip()`

---

## 3. VLM 语义搜索规划

### 3.1 目标：小米智能相册级自然语言搜索

小米相册的搜索能力示例：

| 查询类型 | 示例 | 技术需求 |
|----------|------|----------|
| 物体/场景 | "猫""海滩""食物" | 语义相似度（CLIP） |
| 属性描述 | "红色的花""温馨的氛围" | 语义相似度（CLIP） |
| 活动/事件 | "生日派对""工作会议" | 语义相似度 + 标签融合 |
| 组合查询 | "去年夏天在海边拍的日落" | 结构化过滤 + 语义重排 |
| 以图搜图 | 选一张照片找相似 | 图像-图像余弦相似度 |

### 3.2 搜索架构演进：三层混合检索

```
┌──────────────────────────────────────────────────────────────┐
│                    用户查询                                    │
│  "去年夏天在海边拍的温馨家庭聚餐"                              │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  Layer 1: QueryParser 结构化解析（本地，< 50ms）              │
│  - 时间词: "去年夏天" → TimeRange(2025-06 ~ 2025-08)         │
│  - 地点词: "海边" → locationKeywords=["海边","海滩"]         │
│  - 人脸词: "家庭" → personNames（需结合上下文）               │
│  - 内容词: "温馨""聚餐" → semanticQuery="温馨的家庭聚餐"      │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  Layer 2: 多路召回（并发，< 100ms）                            │
│  ├── 结构化过滤（时间 + 地点 + 人脸）→ 候选集 A               │
│  │   └── SQL: WHERE captureDate BETWEEN ? AND ?              │
│  │       AND locationName LIKE '%海边%'                        │
│  │       AND personId IN (...)                               │
│  │                                                           │
│  ├── 标签召回（关键词）→ 候选集 B                             │
│  │   └── SQL: JOIN tags WHERE name IN ('聚餐','户外','食物')   │
│  │                                                           │
│  └── 语义召回（CLIP）→ 候选集 C                               │
│      └── TextEncoder("温馨的家庭聚餐") → embedding            │
│          → 与候选集 A∪B 的 image embedding 计算余弦相似度      │
│          → Top-K 按相似度排序                                   │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  Layer 3: 融合排序（< 50ms）                                  │
│  - 结构化匹配分（时间/地点/人脸命中 boost）                     │
│  - 标签匹配分（标签命中 boost）                                │
│  - 语义相似度分（CLIP 余弦相似度）                             │
│  - 时间衰减（新照片 boost）                                    │
│  - 加权求和 → 最终排序                                         │
└──────────────────────────────────────────────────────────────┘
```

### 3.3 语义搜索核心模块设计

#### 3.3.1 SemanticSearchEngine

```kotlin
class SemanticSearchEngine(
    private val mobileClipEngine: MobileClipEngine,
    private val mediaDao: MediaDao
) {
    /**
     * 文本→图像语义搜索
     * 
     * @param query 用户输入的自然语言查询
     * @param filter 结构化过滤条件（时间/地点/人脸）
     * @param topK 返回结果数量
     * @return 按语义相似度排序的媒体列表
     */
    suspend fun searchByText(
        query: String,
        filter: StructuredFilter? = null,
        topK: Int = 50
    ): List<ScoredMedia> {
        // 1. 文本编码
        val textEmbedding = encodeTextQuery(query) ?: return emptyList()
        
        // 2. 获取候选集（结构化过滤优先缩小范围）
        val candidates = if (filter != null) {
            mediaDao.searchByFilter(filter)  // 先 SQL 过滤
        } else {
            mediaDao.getMediaWithSemanticEmbedding()  // 全量有 embedding 的照片
        }
        
        // 3. 计算余弦相似度并排序
        return candidates
            .mapNotNull { entity ->
                val imageEmbedding = base64ToFloatArray(entity.semanticEmbedding) ?: return@mapNotNull null
                val similarity = mobileClipEngine.cosineSimilarity(textEmbedding, imageEmbedding)
                ScoredMedia(entity, similarity)
            }
            .sortedByDescending { it.score }
            .take(topK)
    }
    
    /**
     * 以图搜图
     */
    suspend fun searchByImage(
        bitmap: Bitmap,
        topK: Int = 50
    ): List<ScoredMedia> {
        val imageEmbedding = mobileClipEngine.encodeImage(bitmap) ?: return emptyList()
        // ... 同上，计算与所有照片 embedding 的相似度
    }
}
```

#### 3.3.2 文本编码流程

MobileCLIP 文本编码需要 tokenization，但当前 `MobileClipEncoder` 的 JNI 层仅接受 `LongArray` token IDs。需要接入 tokenizer：

```
用户输入: "温馨的家庭聚餐"
    │
    ▼
Tokenizer (Hugging Face Tokenizer)  
    │  加载 vocab.txt + merges.txt + tokenizer.json
    ▼
Token IDs: [101, 2345, 6789, ...]
    │
    ▼
MobileClipEncoder.encodeText(tokenIds) → 512-dim embedding
```

**Phase 1 简化方案**：使用 Android 端轻量级 tokenizer 库（如 `com.google.mlkit:tokenization` 或自研 BPE），或直接复用 Qwen tokenizer（若词汇表兼容）。

**Phase 2 完整方案**：接入 Hugging Face `tokenizer.json` 解析器，支持完整 BPE 编码。

#### 3.3.3 相似度搜索优化

万级照片 × 512 维向量的暴力搜索在 CPU 上约 10-50ms，可接受。但随图库增长需要优化：

| 优化策略 | 适用场景 | 实现复杂度 |
|----------|----------|-----------|
| **暴力搜索** | < 1 万张照片 | 已可用 |
| **向量量化 (PQ)** | 1-10 万张照片 | 中（Product Quantization 将 512-dim 压缩到 64-dim） |
| **HNSW 索引** | > 10 万张照片 | 高（需引入 HNSW 库或自研） |
| **GPU 加速** | 实时搜索 | 低（MNN OpenCL 支持矩阵运算） |

**当前策略**：暴力搜索 + 结构化过滤先缩小候选集。例如用户搜"去年夏天的猫"，先 SQL 过滤出 2024 年 6-8 月的照片（可能只有 500 张），再对这 500 张做 CLIP 相似度计算。

### 3.4 与 VLM (Qwen3.5-2B) 的协同

MobileCLIP 和 Qwen 不是替代关系，而是互补：

| 能力 | MobileCLIP | Qwen3.5-2B |
|------|------------|------------|
| 图像理解 | 全局语义 embedding | 详细文字描述 |
| 文本理解 | 短文本 embedding | 复杂推理、多轮对话 |
| 搜索匹配 | 语义相似度（连续空间） | 标签匹配（离散空间） |
| 速度 | ~50ms | ~3-5s |
| 存储 | 2KB/照片 | 可变长度标签 JSON |

**协同方案**：

```
用户查询: "去年生日派对上穿红色衣服的人"
    │
    ▼
QueryParser 分解:
    - 时间: "去年" → TimeRange
    - 事件: "生日派对" → semanticQuery="birthday party"
    - 人物: "穿红色衣服的人" → 需要 VLM 细粒度理解
    │
    ▼
Layer 2 多路召回:
    ├── 结构化: TimeRange 过滤 → 候选集 1000 张
    ├── 语义: CLIP "birthday party" → Top 100
    └── VLM (可选): 对 Top 20 调用 Qwen 确认 "是否有穿红色衣服的人"
    │
    ▼
Layer 3 融合排序
```

**VLM 重排序（Re-ranking）**：
- CLIP 召回 Top-50 后，对 Top-10 调用 Qwen 做细粒度验证
- Qwen Prompt: "这张照片里是否有穿红色衣服的人？只回答 yes/no"
- 将 Qwen 的 yes/no 作为二分类特征融入排序分数

---

## 4. 实施路线图

### Phase 1: MobileCLIP 基础设施（已完成 ✅）

| 任务 | 状态 | 说明 |
|------|------|------|
| 模型文件清单补全 | ✅ | 7 个文件（含 tokenizer 配置） |
| MobileClipEngine 封装 | ✅ | Kotlin 层 + JNI 桥接 |
| Pass 4 集成到 Pipeline | ✅ | stage4MobileClipEncoding() |
| 数据库 schema 扩展 | ✅ | v4 migration + semanticEmbedding 列 |
| DAO 层 CRUD | ✅ | update/get/query 方法 |
| Scheduler 4-Pass 调度 | ✅ | scanAll / scanIncremental 集成 |
| 编译验证 | ✅ | BUILD SUCCESSFUL |

### Phase 2: 语义搜索 MVP（待启动）

| 任务 | 优先级 | 说明 |
|------|--------|------|
| Tokenizer 接入 | P0 | 加载 vocab.txt + merges.txt，实现 BPE encode |
| SemanticSearchEngine | P0 | 文本编码 + 余弦相似度 + Top-K 排序 |
| 搜索 UI 集成 | P0 | Gallery 搜索框支持语义搜索模式 |
| 结构化过滤 + 语义融合 | P1 | QueryParser → StructuredFilter → 语义重排 |
| 以图搜图 | P1 | encodeImage → 相似度搜索 |
| 向量量化优化 | P2 | PQ/HNSW 应对大图库 |

### Phase 3: VLM 协同增强（远期）

| 任务 | 优先级 | 说明 |
|------|--------|------|
| Qwen 重排序 | P2 | Top-K 结果调用 Qwen 细粒度验证 |
| 多模态查询 | P2 | "找和这张照片风格相似的猫" |
| 语义聚类 | P3 | 基于 embedding 的自动相册分类 |

---

## 5. 数据流全景

```
[拍照/导入]
    │
    ▼
TagGenerationScheduler.scanAll()
    │
    ├── Pass 1: Face ROI + MobileFaceNet Embedding → face_embeddings
    ├── Pass 2: DBSCAN Clustering → persons
    ├── Pass 3: Qwen Tagging → media_assets.labels
    └── Pass 4: MobileCLIP Encoding → media_assets.semanticEmbedding
                              │
                              ▼
[用户搜索: "温馨的家庭聚餐"]
    │
    ▼
SemanticSearchEngine
    │
    ├── 1. Tokenizer → tokenIds
    ├── 2. MobileClipEngine.encodeText(tokenIds) → textEmbedding
    ├── 3. MediaDao.getMediaWithSemanticEmbedding() → candidateList
    ├── 4. cosineSimilarity(textEmbedding, each.imageEmbedding)
    └── 5. sortByDescending(similarity) → Top-K results
                              │
                              ▼
                    GalleryScreen 展示搜索结果
```

---

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| MobileCLIP 模型下载失败 | 中 | 高 | 模型中心断点续传 + 下载进度提示 |
| Tokenizer 词汇表与模型不匹配 | 低 | 高 | 使用模型配套 vocab.txt + merges.txt |
| 文本编码精度不足 | 中 | 中 | 与 Qwen 标签搜索融合，CLIP 作为补充 |
| 大图库相似度搜索慢 | 中 | 中 | 结构化过滤先缩小候选集；后续引入 PQ/HNSW |
| embedding 存储膨胀 | 低 | 低 | 2KB/张，万级 20MB，可接受；支持清理未用 embedding |
| GPU 内存不足 | 低 | 中 | MobileCLIP vision 模型仅 45MB，CPU 推理即可 |

---

## 7. 相关文档

- `docs/02-ARCHITECTURE/ADR/ADR-007-natural-language-photo-search.md` — 原始搜索架构 ADR
- `docs/03-TECHNICAL-SPECS/AUTO_TAG_GENERATION_SPEC.md` — 4-Pass 混合扫描管道
- `docs/03-TECHNICAL-SPECS/GALLERY_INDEX_LAYER_TECH_SPEC.md` — 多维度索引层
- `app/src/main/java/com/mamba/picme/domain/tag/MobileClipEngine.kt` — 引擎实现
- `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt` — 管道集成
- `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt` — 调度器
