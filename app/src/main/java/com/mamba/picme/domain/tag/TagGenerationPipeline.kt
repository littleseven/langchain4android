package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import org.json.JSONArray
import org.json.JSONObject

/**
 * 三阶段 Tag 生成管道
 *
 * ```
 * Stage 1 (Face ROI) ─── 有人脸? ──→ YES ──→ Stage 2 (Face Cluster)
 *      │                                         │
 *      │ NO                                      │
 *      ↓                                         ↓
 * Stage 3 (Qwen without face context)    Stage 3 (Qwen with face context)
 * ```
 *
 * 依赖注入：
 * - [faceDetector]：Stage 1 使用，InsightFace Det10G + 2D106
 * - [llmEngine]：Stage 3 使用，Qwen3.5-2B MNN-LLM
 * - [faceClusterEngine]：Stage 2 使用，MobileFaceNet（Phase 2）+ 余弦聚类
 * - [normalizer]：Stage 3 产出后处理规范化
 * - [context]：Android Context，用于 ContentResolver 加载图片
 *
 * **注意**：Stage 2（MobileFaceNet）当前为占位实现，
 * Stage 2b 返回的 embedding 为零向量，聚类结果无效。
 */
class TagGenerationPipeline(
    private val context: Context,
    private val faceDetector: FaceDetector,
    private val llmEngine: LocalLlmEngine,
    private val faceClusterEngine: FaceClusterEngine,
    private val normalizer: TagNormalizer
) {

    companion object {
        private const val TAG = "TagPipeline"

        /** 人脸检测前的图片最长边缩放 */
        private const val MAX_FACE_DETECT_SIZE = 640

        /** Qwen 图像推理的图片最长边缩放 */
        private const val MAX_VISION_SIZE = 512

        /** Qwen Stage 3 最大输出 token 数 */
        private const val QWEN_MAX_TOKENS = 128
    }

    // ── Stage 3 Prompt 模板 ──────────────────────────────

    private val stage3SystemPrompt = buildString {
        appendLine("你是一个相册照片标签生成助手。")
        appendLine("请从以下维度用中文描述这张照片，输出格式为JSON：")
        appendLine("{")
        appendLine("  \"scene\": \"场景(室内/户外/公园/街道/餐厅/海边/城市等)\",")
        appendLine("  \"activity\": \"活动(吃饭/旅行/运动/会议/购物/聚会等)\",")
        appendLine("  \"objects\": [\"物体1\",\"物体2\"],")
        appendLine("  \"tags\": [\"标签1\",\"标签2\",\"标签3\"],")
        appendLine("  \"summary\": \"一句话概括\"")
        appendLine("}")
        appendLine()
        appendLine("【重要规则】")
        appendLine("1. scene/activity/objects/tags/summary 全部使用中文")
        appendLine("2. tags字段生成3-5个中文关键词标签")
        appendLine("3. 不要输出英文，除非是专有名词如 iPhone、Coca-Cola")
    }

    /**
     * 单张照片完整处理管道
     *
     * @param uri 照片 Content URI
     * @param lensFacing 镜头方向（CameraSelector.LENS_FACING_BACK/FRONT）
     * @param mediaId 数据库中的媒体 ID
     * @return 最终写入 labels 字段的 JSON 字符串
     */
    suspend fun processPhoto(
        uri: String,
        lensFacing: Int,
        mediaId: Long
    ): String {
        Log.d(TAG, "=== Pipeline start: mediaId=$mediaId ===")

        // ── Stage 1: Face ROI + 关键点检测 ─────────────────
        val stage1Result = stage1FaceDetection(uri, lensFacing)
        Log.d(TAG, "Stage 1 done: hasFace=${stage1Result.hasFace}, count=${stage1Result.faceCount}")

        // ── Stage 2: 人脸聚类（仅有人脸时执行）─────────────
        val stage2Result: Stage2Result? = if (stage1Result.hasFace) {
            stage2FaceCluster(uri, mediaId, stage1Result)
        } else {
            null
        }
        Log.d(TAG, "Stage 2 done: personIds=${stage2Result?.personIds ?: "N/A"}")

        // ── Stage 3: Qwen 图像理解 ─────────────────────────
        val stage3Result = stage3QwenTagging(uri, stage1Result, stage2Result)
        Log.d(TAG, "Stage 3 done: scene=${stage3Result.scene}, tags=${stage3Result.tags}")

        // ── 组装最终结果 ───────────────────────────────────
        val faceIds = stage2Result?.personIds ?: emptyList()
        val unified = UnifiedTagResult(
            face = FaceTagInfo(
                count = stage1Result.faceCount,
                selfie = stage1Result.isSelfie,
                groupPhoto = stage1Result.isGroupPhoto,
                personIds = faceIds
            ),
            scene = stage3Result.scene,
            activity = stage3Result.activity,
            objects = stage3Result.objects,
            tags = stage3Result.tags,
            qwenSummary = stage3Result.summary
        )

        val resultJson = toJsonString(unified)
        Log.d(TAG, "=== Pipeline done: $resultJson ===")
        return resultJson
    }

    // ═══════════════════════════════════════════════════
    //  Stage 1: Face ROI + 106 关键点检测
    // ═══════════════════════════════════════════════════

    private suspend fun stage1FaceDetection(uri: String, lensFacing: Int): Stage1Result {
        val bitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE) ?: return Stage1Result(false)
        return try {
            val result = faceDetector.detectPhoto(bitmap, lensFacing)
            if (result == null) {
                return Stage1Result(false)
            }

            // landmarks106 格式：[x0, y0, x1, y1, ...] = 106点 × 2坐标 = 212个float
            val pointsPerFace = 106 * 2
            val faceCount = result.landmarks106.size / pointsPerFace
            val imageWidth = bitmap.width
            val imageHeight = bitmap.height

            // 按人脸分组 landmarks 并计算 ROI
            val roiRects = mutableListOf<RectF>()
            for (faceIdx in 0 until faceCount) {
                val offset = faceIdx * pointsPerFace
                val facePoints = result.landmarks106.sliceArray(offset until offset + pointsPerFace)

                // 从 106 点计算 ROI: 取 x/y 最小最大值
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE
                var maxY = Float.MIN_VALUE
                for (i in 0 until pointsPerFace) {
                    val v = facePoints[i]
                    if (i % 2 == 0) { // x
                        if (v < minX) minX = v
                        if (v > maxX) maxX = v
                    } else { // y
                        if (v < minY) minY = v
                        if (v > maxY) maxY = v
                    }
                }
                // 坐标是归一化的 (0~1)，转成像素坐标
                roiRects.add(RectF(
                    minX * imageWidth,
                    minY * imageHeight,
                    maxX * imageWidth,
                    maxY * imageHeight
                ))
            }

            Stage1Result(
                hasFace = true,
                faceCount = faceCount,
                roiRects = roiRects,
                rawLandmarks = result.landmarks106.copyOf()
            )
        } finally {
            bitmap.recycle()
        }
    }

    // ═══════════════════════════════════════════════════
    //  Stage 2: MobileFaceNet 特征提取 → 人脸聚类
    // ═══════════════════════════════════════════════════

    private suspend fun stage2FaceCluster(
        uri: String,
        mediaId: Long,
        stage1Result: Stage1Result
    ): Stage2Result? {
        val bitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE) ?: return null
        return try {
            val embeddings = mutableListOf<FaceEmbeddingOutput>()

            for (i in stage1Result.roiRects.indices) {
                val roi = stage1Result.roiRects[i]
                val pointsPerFace = 106 * 2
                val offset = i * pointsPerFace
                val faceLandmarks = stage1Result.rawLandmarks.sliceArray(
                    offset until minOf(offset + pointsPerFace, stage1Result.rawLandmarks.size)
                )

                // Step 2b: MobileFaceNet 特征提取（当前为占位实现）
                val feature = faceClusterEngine.extractFeature(bitmap, roi, faceLandmarks)

                // Step 2c: 增量聚类匹配
                val matchedPersonId = faceClusterEngine.matchCluster(feature)

                val personId: Long = if (matchedPersonId != null) {
                    faceClusterEngine.addToCluster(matchedPersonId, feature, mediaId)
                    matchedPersonId
                } else {
                    faceClusterEngine.createCluster(feature, mediaId)
                }

                embeddings.add(FaceEmbeddingOutput(mediaId, feature, personId))
            }

            Stage2Result(
                faceEmbeddings = embeddings,
                personIds = embeddings.mapNotNull { it.personId }
            )
        } finally {
            bitmap.recycle()
        }
    }

    // ═══════════════════════════════════════════════════
    //  Stage 3: Qwen3.5-2B 多模态图像理解
    // ═══════════════════════════════════════════════════

    private suspend fun stage3QwenTagging(
        uri: String,
        stage1Result: Stage1Result,
        stage2Result: Stage2Result?
    ): QwenTagsNormalized {
        val bitmap = loadBitmap(uri, MAX_VISION_SIZE)
        if (bitmap == null) {
            Log.w(TAG, "Stage 3: failed to load bitmap, returning empty tags")
            return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
        }

        return try {
            if (!llmEngine.isLoaded) {
                Log.w(TAG, "Stage 3: LLM not loaded, skipping Qwen tagging")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
            }

            // 构造 user prompt（融入人脸上下文）
            val userPrompt = buildString {
                if (stage1Result.hasFace) {
                    val count = stage1Result.faceCount
                    append("照片中有${count}张人脸，")
                    append(if (count >= 3) "可能是合影。" else if (count >= 2) "可能是双人照。" else "可能是单人照。")
                }
                append("请分析场景、活动、物体并生成标签。")
            }

            val response = llmEngine.imageInference(
                bitmap = bitmap,
                systemPrompt = stage3SystemPrompt,
                userPrompt = userPrompt,
                maxTokens = QWEN_MAX_TOKENS
            )

            if (response.isBlank()) {
                Log.w(TAG, "Stage 3: empty response from LLM")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
            }

            Log.d(TAG, "Stage 3 raw response: $response")

            // 提取 JSON 部分（模型可能生成额外文本）
            val jsonPart = extractJson(response)
            if (jsonPart == null) {
                Log.w(TAG, "Stage 3: failed to extract JSON from response")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "", listOf(response))
            }

            val qwenTags = parseQwenResponse(jsonPart)
            if (qwenTags == null) {
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "", listOf(response))
            }

            // 后处理规范化
            normalizer.normalize(qwenTags)
        } finally {
            bitmap.recycle()
        }
    }

    // ═══════════════════════════════════════════════════
    //  JSON 序列化/反序列化（使用 org.json）
    // ═══════════════════════════════════════════════════

    private fun parseQwenResponse(jsonStr: String): QwenTags? {
        return try {
            val obj = JSONObject(jsonStr)
            QwenTags(
                scene = obj.optString("scene", ""),
                activity = obj.optString("activity", ""),
                objects = obj.optJSONArray("objects")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                tags = obj.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                summary = obj.optString("summary", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Stage 3: failed to parse JSON: ${e.message}")
            null
        }
    }

    private fun toJsonString(result: UnifiedTagResult): String {
        val obj = JSONObject()
        val face = JSONObject()
        face.put("count", result.face.count)
        face.put("selfie", result.face.selfie)
        face.put("groupPhoto", result.face.groupPhoto)
        face.put("personIds", JSONArray(result.face.personIds))
        obj.put("face", face)
        obj.put("scene", result.scene)
        obj.put("activity", result.activity)
        obj.put("objects", JSONArray(result.objects))
        obj.put("tags", JSONArray(result.tags))
        obj.put("qwenSummary", result.qwenSummary)
        return obj.toString()
    }

    /**
     * 从 LLM 返回中提取 JSON 对象
     */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start != -1 && end > start) {
            text.substring(start, end + 1)
        } else null
    }

    /**
     * 加载并缩放 Bitmap
     */
    private fun loadBitmap(uriString: String, maxSize: Int): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            val cr = context.contentResolver

            // 先解码尺寸
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }

            val rawW = opts.outWidth
            val rawH = opts.outHeight
            if (rawW <= 0 || rawH <= 0) return null

            // 计算 sample size
            var sampleSize = 1
            while ((rawW / sampleSize) > maxSize || (rawH / sampleSize) > maxSize) {
                sampleSize *= 2
            }

            // 实际解码
            cr.openInputStream(uri)?.use {
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bitmap: $uriString", e)
            null
        }
    }
}
