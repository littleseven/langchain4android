# 相册自动 Tag 生成技术方案

> 利用本地已有工具链（人脸 ROI 检测 + 人脸关键点 → 人脸聚类 + Qwen3.5-2B 多模态模型）为相册照片自动生成结构化标签。

---

## 1. 概述

### 1.1 目标

为相册中每张照片自动生成多维度标签（Tag），支撑：
- **人脸维度**：照片中出现了「谁」（通过人脸聚类 + 用户命名）
- **内容维度**：照片的场景、物体、活动（通过 Qwen 图像理解）
- **时间/地点维度**：已有的 EXIF 元数据

### 1.2 现有资源

| 资源 | 归属模块 | 算法/模型 | 接口 | 耗时量级 |
|------|----------|----------|------|----------|
| 人脸 ROI 检测 | `beauty-api` → `FaceDetector` | **InsightFace Det10G**（ONNX/MNN 后端）或 **MediaPipe** | `FaceDetector.detectPhoto()` → `FaceDetectionResult?` | ~10-50ms |
| 人脸关键点检测 | `MnnLandmarkDetector` | **InsightFace 2D106**（106 点，MNN 后端）或 **MediaPipe 468** | `MnnLandmarkDetector.detectLandmarks()` → `FloatArray?` | ~20-80ms |
| 人脸特征提取 | Room 表 `face_embeddings` 已定义，推理管道待实现 | **MobileFaceNet** → 512 维特征向量 | `FaceClusterEngine.extractFeature()` → `FloatArray`（新增接口） | ~30-60ms |
| 人脸聚类（人物去重） | Room 表 `persons` 已定义，聚类逻辑待实现 | **增量式余弦距离匹配 + 定期 DBSCAN 重聚** | `FaceClusterEngine.matchCluster()` → `personId`（新增接口） | ~5-20ms/对比 |
| Qwen3.5-2B 多模态图像理解 | `LocalLlmEngine` | **Qwen3.5-2B**（MNN-LLM 多模态运行时 `visual.mnn` 视觉编码器） | `LocalLlmEngine.imageInference()` → `String` | ~2-8s |

### 1.3 管道中各模型的推理后端

每个模型可通过 `DetectionPipelineConfig` 灵活配置推理后端：

| 模型 | 可选后端 | 默认配置 |
|------|----------|----------|
| Det10G（ROI 检测） | ONNX / MNN / NCNN / TFLite | ONNX + AUTO 设备 |
| InsightFace 2D106（关键点） | ONNX / MNN | ONNX + AUTO 设备 |
| MobileFaceNet（特征提取） | TBD（需新增） | TBD |
| Qwen3.5-2B（图像理解） | MNN-LLM（固定） | GPU（OpenCL）优先 → CPU 降级 |

> **MobileFaceNet 集成说明**：当前代码库已有 `face_embeddings` 和 `persons` 两张 Room 表（含 `embedding: ByteArray` 用于存储 512 维特征向量），但 MobileFaceNet 的加载/推理/特征提取代码尚未实现。这是 Stage 2 需要新增的核心能力。

### 1.4 已有数据模型

``kotlin
// app/.../data/local/entity/PersonEntity.kt - 已有
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true)
    val personId: Long = 0,
    val name: String? = null,              // 用户命名（如"张三"）
    val coverMediaId: Long? = null,        // 簇内代表性照片
    val faceCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// app/.../data/local/entity/FaceEmbeddingEntity.kt - 已有
@Entity(
    tableName = "face_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("personId")]
)
data class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    val embeddingId: Long = 0,
    val mediaId: Long,
    val personId: Long? = null,
    val embedding: ByteArray,              // MobileFaceNet 512 维特征向量
    val createdAt: Long = System.currentTimeMillis()
)
```

`MediaAsset`（Room 表 `media_assets`）已有预留字段：

```kotlin
@Entity(tableName = "media_assets")
data class MediaAsset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val type: MediaType,
    val captureDate: Long,
    val fileName: String,
    val duration: Long? = null,
    val hasFace: Boolean = false,       // 是否含人脸
    val faceId: String? = null,         // 人脸聚类分组 ID
    val labels: String? = null,         // 标签（逗号分隔，例如 "聚会,室内,多人")
    val ocrText: String? = null,        // OCR 识别文字
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val indexedAt: Long? = null
)
```

---

## 2. Tag 分类体系

照片的 Tag 分为三个层次，由不同工具产出：

```
┌──────────────────────────────────────────────────┐
│                   Tag 体系                         │
├──────────┬──────────────────┬─────────────────────┤
│  L1:人脸 │  L2:内容         │  L3:元数据          │
│          │                  │                     │
│ - 人物ID │ - 场景(室内/户外) │ - 日期(早/中/晚)    │
│ - 人数   │ - 活动(聚会/运动) │ - 季节(春/夏/秋/冬)  │
│ - 多人/单人 │ - 物体(食物/猫) │ - 地点(家/公司)     │
│          │ - 风格(自拍/合影) │ - 来源(微信/相机)    │
├──────────┴──────────────────┴─────────────────────┤
│  L1: FaceDetector + FaceCluster                   │
│  L2: Qwen3.5-2B imageInference                    │
│  L3: EXIF + MediaStore + 已有 source 字段          │
└──────────────────────────────────────────────────┘
```

### 2.1 L1 人脸标签

| 标签 | 来源 | 示例值 |
|------|------|--------|
| `has_face` | ROI 检测 | `true` / `false` |
| `face_count` | ROI 检测 | `1` / `2` / `3+` |
| `person:{name}` | 人脸聚类 + 用户命名 | `person:张三` |
| `group_photo` | face_count >= 3 | `true` |
| `selfie` | face_count == 1 + 镜头方向 | `true` |
| `face_unknown:N` | 聚类未命名 | `face_unknown:3`（第3个未命名人脸簇） |

### 2.2 L2 内容标签（Qwen 产出）

Qwen 输出的标签经过 [4.6 节](#46-受控词表与后处理) 的受控词表规范化后，最终标签值限定为以下类别：

| 类别 | 受控词表（部分示例） |
|------|--------------------|
| 场景 | `室内`, `户外`, `办公室`, `公园`, `街道`, `餐厅`, `咖啡厅`, `海边`, `山脉`, `城市`, `乡村`, `花园`, `阳台`, `停车场`, `河边`, `森林`, `雪地`, `沙漠`, `泳池`, `体育馆`, `教室`, `商场`, `车内` |
| 活动 | `吃饭`, `旅行`, `运动`, `会议`, `购物`, `聚会`, `散步`, `遛狗`, `拍照`, `自拍`, `阅读`, `工作`, `学习`, `开车`, `休息`, `游泳`, `爬山`, `骑行`, `跑步`, `婚礼`, `生日`, `节日`, `遛娃` |
| 物体 | `食物`, `饮品`, `甜点`, `咖啡`, `茶`, `宠物`, `猫`, `狗`, `花`, `植物`, `书`, `手机`, `电脑`, `车`, `婴儿`, `蛋糕`, `酒`, `水果` |
| 氛围/光线 | `日出`, `日落`, `白天`, `夜晚`, `晴天`, `阴天`, `雨天`, `雪天`, `明亮`, `昏暗`, `暖色调`, `冷色调` |
| 人物关系 | `单人`, `双人`, `多人`, `合影`, `全家福`, `自拍`, `情侣`, `亲子`, `朋友` |

> 所有标签统一使用中文。受控词表在开发过程中可持续扩展。

### 2.3 L3 元数据标签（EXIF / MediaStore）

| 标签 | 来源 | 示例值 |
|------|------|--------|
| `time:morning/afternoon/evening/night` | `captureDate` | `time:afternoon` |
| `season:spring/summer/autumn/winter` | `captureDate` | `season:summer` |
| `source:{source}` | `MediaAsset.source` | `source:wechat`, `source:camera` |
| `location:{name}` | `locationName` | `location:home`, `location:beijing` |

---

## 3. 执行管道（Pipeline）设计

### 3.1 整体流程

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────────┐
│ 照片入库 │───→│ Stage 1  │───→│ Stage 2  │───→│  Stage 3     │
│ 或主动触发│    │ ROI 检测  │    │ 人脸聚类  │    │ Qwen 图像理解 │
└──────────┘    └──────────┘    └──────────┘    └──────┬───────┘
       │               │               │               │
       ↓               ↓               ↓               ↓
  ┌─────────┐    ┌──────────┐    ┌───────────┐    ┌──────────┐
  │            │  │ has_face │    │ faceId    │    │ labels   │
  │ MediaAsset │  │ face_count│    │ person:X  │    │ (JSON)   │
  │            │  │          │    │           │    │          │
  └─────────┘    └──────────┘    └───────────┘    └──────────┘
```

### 3.2 执行顺序决策

**核心原则**：快速失败、资源降级、逐步深入。

```
Stage 1 (Face ROI) ─── 有人脸? ──→ YES ──→ Stage 2 (Face Landmark + Cluster)
     │                                │
     │ NO                             │
     ↓                                ↓
  Stage 3 (Qwen without face context)  Stage 3 (Qwen with face context)
```

**决策理由**：

| 顺序 | 步骤 | 理由 |
|------|------|------|
| 1st | Face ROI 检测 | **最轻量**（~30ms），快速过滤无人脸照片，避免后续无用功；输出人脸数可丰富 L1 标签 |
| 2nd | 人脸关键点 → 人脸聚类 | **中等开销**（~50ms/张），仅对含人脸照片执行；输出 faceId 用于后续人物标签 |
| 3rd | Qwen 图像理解 | **最重**（~3-5s/张），最后执行；可利用前两步结果构造更精准的 prompt |

### 3.3 Stage 1：人脸 ROI + 106 关键点检测

**使用模型**：
- ROI 检测：**InsightFace Det10G**（ONNX/MNN 后端，`DetectionPipelineConfig.roiDetector = DET10G`）
- 106 关键点：**InsightFace 2D106**（MNN 后端，`DetectionPipelineConfig.landmarkDetector = INSIGHTFACE_2D106`）
- 备选：**MediaPipe Face Detection**（TFLite 后端）

> 该阶段输出的人脸人像106点坐标用于 `face_count` 统计和 `selfie`/`group_photo` 判定，**不能直接用于人脸聚类确认一个人**——这是第二个阶段需要专门特征模型的原因。

**执行流程**：

```
输入: Bitmap（缩放到 640px 最长边）
输出: FaceDetectionResult? (roiRect, landmarks106)

伪代码:
fun stage1(bitmap: Bitmap): Stage1Result {
    val result = faceDetector.detectPhoto(bitmap, lensFacing)
    if (result == null) return Stage1Result(hasFace = false, faceCount = 0)
    
    // FaceDetectionResult.landmarks106 是归一化坐标 (0~1)
    // 格式：[x0, y0, x1, y1, ...] = 106个点 × 2坐标 = 212个float
    // 每张人脸一个独立的106点集合
    val pointsPerFace = 106 * 2
    val faceCount = result.landmarks106.size / pointsPerFace
    val roiRects = extractRois(result.landmarks106, bitmap.width, bitmap.height)
    
    return Stage1Result(
        hasFace = true,
        faceCount = faceCount,
        roiRects = roiRects,        // 每张脸的 ROI
        rawLandmarks = result.landmarks106
    )
}
```

**耗时优化**：
- 将 Bitmap 缩放到 640px 最长边（MNN 人脸检测推荐分辨率）
- 使用 `MnnResourceManager.acquireFaceDetection()` + `releaseFaceDetection()` 管理模型加载
- 不缩放直接使用原始尺寸会显著增加延时（>200ms）

### 3.4 Stage 2：MobileFaceNet 特征提取 → 人脸聚类

> **关键澄清**：Stage 1 的 106 点关键点只是人脸轮廓的几何坐标，**不能用于做人脸识别或聚类**。
> 确认一个人需要专门的 face embedding 模型。本项目选用 **MobileFaceNet**（轻量化 CNN），
> 输入对齐后的人脸 ROI → 输出 512 维特征向量。

**使用模型**：
- 特征提取：**MobileFaceNet**（MobileNet 变体，专为人脸识别优化的轻量 CNN）
  - 输入：112×112 RGB 对齐人脸 ROI
  - 输出：512 维特征向量（`FloatArray`）
  - 推理后端：待定（建议 MNN，与现有推理栈一致）
- 聚类算法：**增量式余弦距离匹配 + 定期 DBSCAN 重聚**
  - 距离度量：余弦距离（cosine distance）
  - 匹配阈值：> 0.6 归入已有簇

> **实现状态**：代码库已预置 `FaceEmbeddingEntity`（存储 512 维特征向量）和 `PersonEntity`（人物去重表）
> 两个 Room 表。但 MobileFaceNet 的模型加载和推理代码尚未实现，是 Stage 2 需要新增的核心模块。

**Step 2a - 人脸 ROI 对齐与裁剪**：

```
输入: Bitmap + Stage1 输出的 roiRects
输出: 对齐后的 112×112 人脸 ROI Bitmap

流程:
1. 对每张人脸，用 Stage 1 的 landmarks106 做仿射变换对齐
   （将双眼映射到固定位置，标准化人脸姿态）
2. 裁剪并缩放到 112×112（MobileFaceNet 标准输入尺寸）
```

**Step 2b - MobileFaceNet 特征提取**：

```
fun stage2ExtractFeatures(bitmap: Bitmap, roi: RectF, landmarks106: FloatArray): FloatArray {
    // 1. 用 landmarks106 做仿射对齐 → 112×112 标准化人脸图
    val alignedFace = alignFace(bitmap, landmarks106)
    
    // 2. MobileFaceNet 推理 → 512 维特征向量
    //    新模块，通过 MnnFaceDetector 类似的 JNI 桥接方式接入
    val feature: FloatArray = mobileFaceNet.extract(alignedFace)
    
    return feature  // 512维，FloatArray
}
```

**Step 2c - 增量聚类匹配**：

```
输入: 512 维特征向量
输出: personId（已有簇）或新建簇

算法: 增量式余弦距离匹配

流程:
1. 计算新特征向量与所有簇质心的余弦距离
2. 找到距离最近的簇：
   - 余弦相似度 > 0.6 → 归入该簇，更新质心（滑动平均）
   - 否则 → 新建簇，以该特征为初始质心
3. 每积累 N=100 张新特征，触发全量 DBSCAN 重聚类
   （避免增量漂移，保证聚类质量）
4. 更新 FaceEmbeddingEntity 和 PersonEntity
```

**存入已有 Room 表**：

```kotlin
// 写入 face_embeddings 表
val embeddingEntity = FaceEmbeddingEntity(
    mediaId = asset.id,
    personId = matchedPerson?.personId,  // null 表示新建簇
    embedding = feature.toByteArray()     // 512 维 FloatArray → ByteArray
)
faceEmbeddingDao.insert(embeddingEntity)

// 更新 persons 表（已有）
personDao.upsert(personEntity)
```

### 3.5 Stage 3：Qwen3.5-2B 图像理解

**使用模型**：
- 多模态基础模型：**Qwen3.5-2B**（通义千问 3.5-2B 端侧模型）
- 推理引擎：**MNN-LLM**（`visual.mnn` 视觉编码器 + `qwen3.5-2b.mnn` 语言模型）
- **配置要求**：`runtime config` 中必须显式指定 `visual_model` 和 `visual_weight` 字段指向 `visual.mnn`，否则视觉编码器加载为空导致 SIGSEGV
- 线程模式：`modelDispatcher` 单线程串行化（`engineMutex` 保护）

**利用前序结果构造多层级 Prompt**：

```kotlin
suspend fun stage3(bitmap: Bitmap, stage1Result: Stage1Result, stage2Result: Stage2Result?): String {
    // 根据是否有人脸构造不同的 system prompt
    val systemPrompt = buildString {
        appendLine("你是一个相册照片标签生成助手。")
        appendLine("请从以下维度用中文描述这张照片，输出格式为JSON：")
        appendLine("{")
        appendLine("  \"scene\": \"场景(室内/户外/公园/街道/餐厅/海边/城市等)\",")
        appendLine("  \"activity\": \"活动(吃饭/旅行/运动/会议/购物/聚会等)\",")
        appendLine("  \"objects\": [\"物体1\",\"物体2\"],")
        appendLine("  \"tags\": [\"标签1\",\"标签2\",\"标签3\"],")
        appendLine("  \"summary\": \"一句话概括\"")
        appendLine("}")
        appendLine("")
        appendLine("【重要规则】")
        appendLine("1. scene/activity/objects/tags/summary**全部使用中文**")
        appendLine("2. tags字段生成3-5个中文关键词标签")
        appendLine("3. 不要输出英文，除非是专有名词如 iPhone、Coca-Cola")
    }
    
    val userPrompt = buildString {
        if (stage1Result.hasFace) {
            val count = stage1Result.faceCount
            append("照片中有${count}张人脸，可能是${if (count >= 3) "合影" else if (count >= 2) "双人照" else "单人照"}。")
        }
        append("请分析场景、活动、物体并生成标签。")
    }
    
    return llmEngine.imageInference(
        bitmap = bitmap,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        maxTokens = 128
    )
}
```

**JSON 解析后写入 MediaAsset.labels**：

```kotlin
data class QwenTags(
    val scene: String = "",
    val activity: String = "",
    val objects: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String = ""
)

// 写入 MediaAsset.labels 为 JSON 字符串
val labelJson = Json.encodeToString(qwenTags)
```

---

## 4. 标签语言策略：统一中文

### 4.1 现状分析

| 标签来源 | 当前语言 | 说明 |
|----------|----------|------|
| ML Kit ImageLabeler（已有） | **英文**（默认） | ML Kit 的 `ImageLabeler` 输出的 `label.text` 为英文，如 `"Outdoor"`、`"Food"` |
| Qwen3.5-2B（Stage 3 设计） | 原设计为**混用**英文 scene + 中文 tags | prompt 示例给出了英文场景词，但也给了中文标签 |
| L3 元数据标签 | **中文**（地点名/来源名） | 用户输入 + 系统字段 |
| 人脸聚类 `person:{name}` | **中文**（用户命名） | 如 `person:张三` |

### 4.2 语言不统一的风险

现有搜索系统 `QueryBuilder` 使用 SQLite `LIKE` 进行标签匹配：

```kotlin
// QueryBuilder.kt (已有代码)
tagDao.searchByExactTag(keyword)  // 精确匹配
tagDao.searchByTagName(keyword)   // LIKE 模糊匹配
mediaDao.searchByLabel(keyword)   // legacy LIKE 搜索
```

**问题场景**：
- 用户搜索 `"公园"` → `LIKE '%公园%'` → 匹配中文 `"公园"`，但不匹配英文 `"park"`
- 用户搜索 `"户外"` → 不匹配英文 `"outdoor"`
- 用户搜索 `"吃饭"` → 不匹配英文 `"eating"`

### 4.3 决策：Qwen 输出统一为中文

```
┌─────────────────────────────────────────────────────────┐
│  Stage 3 Qwen 输出 → 全部中文                           │
│                                                         │
│  scene: "户外"         (而非 "outdoor")                  │
│  activity: "旅行"      (而非 "travelling")               │
│  tags: ["风景","山"]  (而非 ["landscape","mountain"])   │
│  summary: "在山顶拍摄的风景照片"                          │
│                                                         │
│  ML Kit 标签保持英文（单独索引，与 Qwen 标签不混用）       │
└─────────────────────────────────────────────────────────┘
```

**理由**：
1. **用户搜索习惯**：App 面向中文用户，`"公园"` 比 `"park"` 自然
2. **SQLite LIKE 精准匹配**：中文关键词精确命中中文标签，无翻译损耗
3. **ML Kit 英文标签独立路径**：已有标签引擎的英文结果在 `tagDao` 中单独索引，与 Qwen 的 `labels` 字段不冲突
4. **LLM Agent 桥接**：`MediaSearchEngine` 的第二层走 LLM 语义解析，天然支持中英文跨语言

### 4.4 ML Kit 标签处理策略

已有的 ML Kit `MetadataExtractor` 输出英文标签（如 `"Food"`、`"Plant"`、`"Sky"`），策略如下：

| 处理方式 | 说明 |
|----------|------|
| **保留英文原样写入** | ML Kit 标签写入 `MediaEntity.labels` 的 ML Kit 专属字段（已有逻辑） |
| **不翻译** | 不引入翻译步骤（增加延迟且可能不准确） |
| **搜索时兼容** | `QueryBuilder` 的 `searchByLabel()` 会同时搜索中英文；用户通过 LLM Agent 搜索时自动转换语言 |
| **未来可选** | 若误识别率过高，可关闭 ML Kit 标签，完全依赖 Qwen 中文标签 |

### 4.6 受控词表与后处理

> 受控词表是确保标签一致性的核心机制。Qwen 的 prompt 只给示例定格式，**不要求模型严格限定在词表内**。
> 后处理阶段将模型返回的自由文本匹配到受控词表，同时保留未匹配的原始文本作为补充。

#### 4.6.1 受控词表定义

词表以 JSON 配置文件存储，随业务持续扩展：

```json
// controlled_vocab.json
{
  "scene": ["室内", "户外", "办公室", "公园", "街道", "餐厅", "咖啡厅",
            "海边", "山脉", "城市", "乡村", "花园", "阳台", "停车场",
            "河边", "森林", "雪地", "沙漠", "泳池", "体育馆", "教室",
            "商场", "车内", "屋顶", "海滩"],
  "activity": ["吃饭", "旅行", "运动", "会议", "购物", "聚会", "散步",
               "遛狗", "拍照", "自拍", "阅读", "工作", "学习", "开车",
               "休息", "游泳", "爬山", "骑行", "跑步", "婚礼", "生日",
               "节日", "遛娃", "唱歌", "跳舞", "露营", "烧烤"],
  "objects": ["食物", "饮品", "甜点", "咖啡", "茶", "宠物", "猫", "狗",
               "花", "植物", "书", "手机", "电脑", "车", "婴儿", "蛋糕",
               "酒", "水果", "蔬菜", "自行车", "乐器"],
  "atmosphere": ["日出", "日落", "白天", "夜晚", "晴天", "阴天", "雨天",
                  "雪天", "明亮", "昏暗", "暖色调", "冷色调", "霓虹", "逆光"],
  "people": ["单人", "双人", "多人", "合影", "全家福", "自拍", "情侣",
              "亲子", "朋友", "同事"]
}
```

#### 4.6.2 标签规范化流程

```
Qwen 输出 JSON
    ↓
┌─────────────────────────────────────────────┐
│  规范化算法                                   │
│                                              │
│  for each field (scene/activity/objects/tags):│
│    for each raw_value:                        │
│      ① 精确匹配 → 命中词表 → 直接使用          │
│      ② 包含匹配 → raw_value 包含词表词 → 使用  │
│      ③ 语义匹配 → 编辑距离 ≤ 1 → 纠正 → 使用  │
│      ④ 未匹配 → 保留原始值（标记为 non_std）   │
│                                              │
│  输出: QwenTagsNormalized                     │
└─────────────────────────────────────────────┘
    ↓
写入 MediaAsset.labels
```

**规范化算法实现**：

```kotlin
data class QwenTagsNormalized(
    val scene: String,                    // 已映射到受控词表
    val activity: String,                 // 已映射到受控词表
    val objects: List<String>,            // 每个object尽量映射
    val tags: List<String>,               // 每个tag尽量映射
    val summary: String,                  // 原始文本（不做映射）
    val nonStandard: List<String> = emptyList()  // 未匹配的原始词
)

class TagNormalizer(private val vocab: ControlledVocab) {
    
    fun normalize(raw: QwenTags): QwenTagsNormalized {
        return QwenTagsNormalized(
            scene = bestMatch(raw.scene, vocab.scene),
            activity = bestMatch(raw.activity, vocab.activity),
            objects = raw.objects.map { bestMatch(it, vocab.objects) },
            tags = raw.tags.map { bestMatchAcrossCategories(it) },
            summary = raw.summary,
            nonStandard = collectNonStandard(raw)
        )
    }
    
    private fun bestMatch(input: String, candidates: List<String>): String {
        // 1. 精确匹配
        if (input in candidates) return input
        // 2. 包含匹配: "海边沙滩" → 包含 "海边"
        candidates.firstOrNull { input.contains(it) || it.contains(input) }
            ?.let { return it }
        // 3. 编辑距离 ≤ 1 容错
        candidates.firstOrNull { levenshtein(input, it) <= 1 }
            ?.let { return it }
        // 4. 未匹配，保留原始值
        return input
    }
}
```

#### 4.6.3 为什么不把所有词写在 Prompt 里

| 方案 | 缺点 |
|------|------|
| 将所有受控词写在 prompt | ① 场景+活动+物体 ≈ 80 个词，占 ~200 tokens；② 模型会倾向于只输出示例词，忽略未见过的但合理的内容；③ 词表更新需修改提示词，耦合度高 |
| Prompt 只给格式示例 + 后处理映射 | ① prompt 仅 ~50 tokens；② 模型自由输出 → 后处理映射到标准词；③ 未匹配的词保留原值，不丢失信息；④ 词表更新只需改 JSON 配置 |

#### 4.6.4 搜索时的匹配策略

```
用户搜索 "爬山"
    ↓
LIKE '%爬山%' 搜索 → 命中 scene="山脉", tags=["爬山"] 等
    ↓
LLM Agent 搜索 → 同义词扩展 "爬山" → "hiking", "户外", "运动"
    ↓
如果 Qwen 输出了 "健行"（受控词表中无此词）
    → nonStandard=["健行"] 仍然可被 LIKE 搜索命中！
    → 后续纳入词表后，规范化后可被更多用户匹配到
```

**关键设计原则**：受控词表是软约束，不是硬限制。未匹配的词保留在 `nonStandard` 中，仍然可搜索。

---

## 5. 增量更新策略

### 5.1 新照片触发

```
用户拍照/导入 → MediaStore ContentObserver 监听到变化
    ↓
等待 30s（防频繁触发）
    ↓
检查新照片 URI 列表
    ↓
单张处理管道（非批处理，体验优先）
    ↓
写入标签 → 更新相册 Grid（RecyclerView 局部刷新）
```

### 5.2 已处理照片重扫

```
用户触发「重扫全部标签」
    ↓
清除 face_embeddings 和 persons 表
    ↓
清除所有 MediaAsset.labels
    ↓
全量批处理（带进度条展示）
    ↓
通知相册刷新
```

---

## 6. 数据模型说明

### 6.1 已有 Room 表（重复使用）

人脸聚类相关的两张表已由项目预定义，无需新增：

```kotlin
// face_embeddings.kt - 已有（app/.../data/local/entity/）
@Entity(tableName = "face_embeddings", ...)
data class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    val embeddingId: Long = 0,
    val mediaId: Long,
    val personId: Long? = null,            // 关联到 persons 表
    val embedding: ByteArray,              // MobileFaceNet 512 维特征向量
    val createdAt: Long = System.currentTimeMillis()
)

// persons.kt - 已有
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true)
    val personId: Long = 0,
    val name: String? = null,              // 用户命名
    val coverMediaId: Long? = null,        // 代表性照片
    val faceCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

### 6.2 MediaAsset 扩展

```
// MediaAsset.labels 字段存储完整 JSON:
{
  "face": {"count":2, "selfie":false, "personIds":[1, 2]},
  "scene": "户外",
  "activity": "旅行",
  "objects": ["山", "天空"],
  "tags": ["风景", "旅行", "户外"],
  "qwen_summary": "在山顶拍摄的风景照片"
}
```

---

## 7. 性能预算

| 阶段 | 单张耗时 | 10 张耗时 | 100 张耗时 | 可并行 |
|------|----------|-----------|------------|--------|
| Stage 1 ROI | ~30ms | ~30ms | ~30ms | ✅ 全并行 |
| Stage 2 聚类 | ~50ms | ~500ms | ~5s | ❌ 全局依赖 |
| Stage 3 Qwen | ~3-5s | ~30-50s | ~5-8min | ❌ 独占调度器 |
| **总计** | **~3-5s** | **~30-50s** | **~5-8min** | |

### 7.1 用户体感优化

- **首次全量扫描**：后台静默执行，不阻塞 UI
- **进度反馈**：相册页显示「正在扫描 X/100 张照片」轻提示
- **新照片**：单张处理，延时 < 5s
- **中断恢复**：记录已处理的 `MediaAsset.id`，中断后从断点续传

---

## 8. 风险与 mitigation

| 风险 | 影响 | Mitigation |
|------|------|------------|
| Qwen 推理耗时过长 | 全量扫描 > 10min | 批处理 + 可中断 + 进度展示 |
| 人脸聚类误判 | 相同人物归入不同簇 | 支持用户手动合并簇（UI 操作） |
| 模型加载冲突 | 人脸检测 + LLM 同时加载 OOM | ResourceManager 引用计数 + 卸载策略 |
| 标签质量不可靠 | 小模型 Qwen 3.5-2B 能力有限 | ①只生成场景级粗粒度标签 ②可编辑标签 |
| 电量消耗 | 全量扫描费电 | 仅 Wi-Fi + 充电时自动触发 |

---

## 9. 后续迭代方向

| 阶段 | 功能 | 依赖 |
|------|------|------|
| **Phase 1** | 基础 ROI + 单人/多人标签 | 已有 FaceDetector |
| **Phase 2** | 人脸聚类 + faceId 分组（MobileFaceNet 512 维 embedding） | MobileFaceNet 模型接入 + MNN 推理 |
| **Phase 3** | Qwen 内容标签 | 已有 LocalLlmEngine |
| **Phase 4** | 标签搜索 + 智能相册分组 | 上述全部 + UI |
| **Phase 5** | 用户命名人脸簇 + 合并/拆分 | UI + 数据迁移 |

---

## 10. 关键接口定义

### 10.1 TagGenerationScheduler

```kotlin
interface TagGenerationScheduler {
    /** 触发全量扫描 */
    suspend fun scanAll(progressCallback: (processed: Int, total: Int) -> Unit)
    
    /** 处理单张新照片 */
    suspend fun processSingle(asset: MediaAsset)
    
    /** 取消进行中的扫描 */
    fun cancel()
    
    /** 获取扫描状态 */
    val isScanning: StateFlow<Boolean>
    val progress: StateFlow<ScanProgress?>
}

data class ScanProgress(
    val processed: Int,
    val total: Int,
    val currentStage: Stage = Stage.FACE_ROI
)

enum class Stage {
    FACE_ROI,
    FACE_CLUSTER,
    QWEN_TAGGING,
    COMPLETE
}
```

### 10.2 FaceClusterEngine

```kotlin
interface FaceClusterEngine {
    /** 提取人脸特征向量（MobileFaceNet: 112×112 ROI → 512 维） */
    suspend fun extractFeature(bitmap: Bitmap, roi: RectF, landmarks106: FloatArray): FloatArray
    
    /** 匹配已有簇（512 维特征 → 余弦距离 → personId） */
    suspend fun matchCluster(feature: FloatArray): Long?  // personId, null=新建簇
    
    /** 创建新簇 */
    suspend fun createCluster(feature: FloatArray, mediaId: Long): Long
    
    /** 合并两个簇 */
    suspend fun mergeClusters(personA: Long, personB: Long)
    
    /** 获取簇内代表照片 */
    suspend fun getClusterPhotos(personId: Long): List<MediaAsset>
}
```

---

## 11. 与现有架构的集成

### 11.1 Agent 集成

标签系统作为 **Capability** 暴露给 Agent：

```kotlin
class AutoTagCapability(
    private val tagScheduler: TagGenerationScheduler
) : Capability {
    override val name = "auto_tag"
    
    @Tool("Scan all photos and generate tags")
    fun scanAll() { ... }
    
    @Tool("Get tags for a specific photo")
    fun getTags(photoId: Long): TagsResult { ... }
    
    @Tool("Get face clusters (person groups)")
    fun getPersonList(): List<PersonGroup> { ... }
}
```

### 11.2 搜索集成

标签写入 `labels` 字段后，Agent 搜索可直接利用：

```
用户: "帮我找到上周在公园拍的合照"
Agent SQL: SELECT * FROM media_assets 
           WHERE labels LIKE '%park%' 
           AND labels LIKE '%group%' 
           AND captureDate BETWEEN ? AND ?
```

---

> **维护者**：RD Agent  
> **状态**：方案设计 · 待评审  
> **最后更新**：2026-06-23（已标注各阶段使用的算法模型）
