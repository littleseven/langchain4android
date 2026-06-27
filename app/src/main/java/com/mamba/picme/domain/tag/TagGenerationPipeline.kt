package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.tag.prompt.DefaultTagPromptProvider
import com.mamba.picme.domain.tag.prompt.TagPromptProvider
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
    private val normalizer: TagNormalizer,
    private val openClGuardian: OpenClGuardian? = null,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val promptProvider: TagPromptProvider = DefaultTagPromptProvider(),
    private val mobileClipEngine: MobileClipEngine? = null
) {

    companion object {
        private const val TAG = "TagPipeline"

        /** 人脸检测前的图片最长边缩放 */
        private const val MAX_FACE_DETECT_SIZE = 640

        /** Qwen 图像推理的图片最长边缩放 */
        private const val MAX_VISION_SIZE = 512

        /** Qwen Stage 3 最大输出 token 数（从 128 增至 256 以支持丰富标签） */
        private const val QWEN_MAX_TOKENS = 256
    }

    /** 当前生成目标语言，由用户设置决定 */
    private val targetLanguage: AppLanguage
        get() = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

    private val stage3SystemPrompt: String
        get() = promptProvider.systemPrompt(targetLanguage)

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

        // 一次性加载 640px Bitmap，Stage 1 和 Stage 2 共用
        val faceBitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE)
        if (faceBitmap == null) {
            Log.w(TAG, "Failed to load bitmap for mediaId=$mediaId")
            return """{"face":{"count":0}}"""
        }

        val stage1Result: Stage1Result
        val stage2Result: Stage2Result?

        try {
            // ── Stage 1: Face ROI + 关键点检测（复用 faceBitmap）───
            stage1Result = stage1FaceDetection(faceBitmap, lensFacing)
            Log.d(TAG, "Stage 1 done: hasFace=${stage1Result.hasFace}, count=${stage1Result.faceCount}")

            // ── Stage 2: 人脸聚类（复用同一个 faceBitmap，不再重新解码）───
            stage2Result = if (stage1Result.hasFace) {
                stage2FaceCluster(faceBitmap, mediaId, stage1Result)
            } else {
                null
            }
            Log.d(TAG, "Stage 2 done: personIds=${stage2Result?.personIds ?: "N/A"}")
        } finally {
            faceBitmap.recycle()
        }

        // ── Stage 3: Qwen 图像理解（独立加载 512px Bitmap，尺寸要求不同）───
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
    //  [Pass 1] 人脸检测 + Embedding 提取（供 3-Pass 混合扫描用）
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 1] 单张照片的人脸检测 + MobileFaceNet Embedding 提取
     *
     * 结果持久化（faceRoiJson 字段）供 Pass 3 构造人脸上下文。
     * Embedding 由调度器写入 face_embeddings 表供 Pass 2 DBSCAN。
     *
     * @param uri 照片 Content URI
     * @param lensFacing 镜头方向
     * @param mediaId 媒体 ID
     * @return 包含 faceRoi JSON 和每张人脸的 embedding 列表
     */
    suspend fun stage1WithEmbeddings(
        uri: String,
        lensFacing: Int,
        mediaId: Long
    ): Stage1WithEmbeddingsResult {
        val faceBitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE)
        if (faceBitmap == null) {
            Log.w(TAG, "[Pass 1] Failed to load bitmap for mediaId=$mediaId")
            return Stage1WithEmbeddingsResult(null, emptyList())
        }

        try {
            val stage1Result = stage1FaceDetection(faceBitmap, lensFacing)
            Log.d(TAG, "[Pass 1] Stage 1 done: hasFace=${stage1Result.hasFace}, count=${stage1Result.faceCount}")

            val faceRoiJson = faceRoiToJson(stage1Result)

            if (!stage1Result.hasFace) {
                return Stage1WithEmbeddingsResult(faceRoiJson, emptyList())
            }

            // 提取每张人脸的 512 维 embedding
            val embeddings = mutableListOf<FloatArray>()
            for (i in stage1Result.roiRects.indices) {
                val roi = stage1Result.roiRects[i]
                val pointsPerFace = 106 * 2
                val offset = i * pointsPerFace
                val faceLandmarks = stage1Result.rawLandmarks.sliceArray(
                    offset until minOf(offset + pointsPerFace, stage1Result.rawLandmarks.size)
                )

                val feature = faceClusterEngine.extractFeature(faceBitmap, roi, faceLandmarks)
                embeddings.add(feature)
            }

            Log.d(TAG, "[Pass 1] Extracted ${embeddings.size} embeddings for mediaId=$mediaId")
            return Stage1WithEmbeddingsResult(faceRoiJson, embeddings)
        } finally {
            faceBitmap.recycle()
        }
    }

    // ═══════════════════════════════════════════════════
    //  [Pass 3] Qwen 图像理解标签生成（可断点续扫）
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 3] Qwen3.5-2B 图像理解标签生成
     *
     * 使用 Pass 1 持久化的 faceRoiJson 恢复人脸上下文。
     * 不依赖传递性 Stage1Result 对象，天然支持断点续扫。
     *
     * @param uri 照片 Content URI
     * @param faceRoiJson Pass 1 持久化的人脸 ROI JSON（null=解码失败）
     * @return 规范化后的标签结果
     */
    suspend fun stage3QwenTagging(
        uri: String,
        faceRoiJson: String?
    ): QwenTagsNormalized {
        val bitmap = loadBitmap(uri, MAX_VISION_SIZE)
        if (bitmap == null) {
            Log.w(TAG, "[Pass 3] Failed to load bitmap, returning empty tags")
            return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
        }

        return try {
            if (!llmEngine.isLoaded) {
                Log.w(TAG, "[Pass 3] LLM not loaded, skipping Qwen tagging")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
            }

            // 从 JSON 恢复人脸上下文
            val faceRoi = faceRoiJson?.let { parseFaceRoi(it) }

            // 构造 user prompt（融入人脸上下文）
            val userPrompt = if (faceRoi != null && faceRoi.hasFace) {
                promptProvider.userPrompt(targetLanguage, faceRoi.faceCount, faceRoi.isGroupPhoto)
            } else {
                promptProvider.userPrompt(targetLanguage, 0, false)
            }

            val response = runVisionInference(bitmap, userPrompt)

            if (response.isBlank()) {
                Log.w(TAG, "[Pass 3] empty response from LLM")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
            }

            Log.d(TAG, "[Pass 3] raw response: $response")

            val jsonPart = extractJson(response)
            if (jsonPart == null) {
                Log.w(TAG, "[Pass 3] failed to extract JSON from response")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "", listOf(response))
            }

            val qwenTags = parseQwenResponse(jsonPart)
            if (qwenTags == null) {
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "", listOf(response))
            }

            normalizer.normalize(qwenTags)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 释放 MobileCLIP 引擎资源
     */
    fun releaseMobileClip() {
        mobileClipEngine?.release()
    }

    // ═══════════════════════════════════════════════════
    //  [Pass 4] MobileCLIP 语义编码
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 4] MobileCLIP 语义编码
     *
     * 使用 MobileCLIP-S0 生成 512 维 L2 归一化图像 embedding，
     * 存储为 Base64 字符串供语义搜索使用。
     *
     * @param uri 照片 Content URI
     * @param mediaId 媒体 ID
     * @return Base64 编码的 embedding 字符串，失败返回 null
     */
    suspend fun stage4MobileClipEncoding(
        uri: String,
        mediaId: Long
    ): String? {
        val engine = mobileClipEngine ?: run {
            Log.w(TAG, "[Pass 4] MobileClipEngine not available")
            return null
        }

        if (!engine.isInitialized) {
            Log.w(TAG, "[Pass 4] MobileClipEngine not initialized, attempting init")
            if (!engine.initialize(useGpu = false)) {
                Log.w(TAG, "[Pass 4] Failed to initialize MobileClipEngine")
                return null
            }
        }

        val bitmap = loadBitmap(uri, MAX_VISION_SIZE)
        if (bitmap == null) {
            Log.w(TAG, "[Pass 4] Failed to load bitmap for mediaId=$mediaId")
            return null
        }

        return try {
            val embedding = engine.encodeImage(bitmap)
            if (embedding == null) {
                Log.w(TAG, "[Pass 4] encodeImage returned null for mediaId=$mediaId")
                return null
            }
            // 编码为 Base64 字符串存储
            val base64 = floatArrayToBase64(embedding)
            Log.d(TAG, "[Pass 4] Encoded embedding for mediaId=$mediaId, dim=${embedding.size}, base64_len=${base64.length}")
            base64
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 将 FloatArray 编码为 Base64 字符串
     */
    private fun floatArrayToBase64(array: FloatArray): String {
        val bytes = ByteArray(array.size * 4)
        for (i in array.indices) {
            val bits = java.lang.Float.floatToRawIntBits(array[i])
            bytes[i * 4] = (bits shr 24).toByte()
            bytes[i * 4 + 1] = (bits shr 16).toByte()
            bytes[i * 4 + 2] = (bits shr 8).toByte()
            bytes[i * 4 + 3] = bits.toByte()
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    // ═══════════════════════════════════════════════════
    //  JSON 序列化/反序列化辅助
    // ═══════════════════════════════════════════════════

    /** 将 Stage 1 结果序列化为 JSON（用于 DB 持久化） */
    private fun faceRoiToJson(result: Stage1Result): String {
        return """{"hasFace":${result.hasFace},"faceCount":${result.faceCount},"isSelfie":${result.isSelfie},"isGroupPhoto":${result.isGroupPhoto}}"""
    }

    /** 从 JSON 恢复人脸上下文 */
    private fun parseFaceRoi(json: String): FaceRoiPersist? {
        return try {
            val obj = JSONObject(json)
            FaceRoiPersist(
                hasFace = obj.optBoolean("hasFace", false),
                faceCount = obj.optInt("faceCount", 0),
                isSelfie = obj.optBoolean("isSelfie", false),
                isGroupPhoto = obj.optBoolean("isGroupPhoto", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse faceRoi JSON: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════
    //  原私有方法的访问控制变更为 internal（供 scheduler 直接调用）
    // ═══════════════════════════════════════════════════

    // 以下方法已通过 stage1WithEmbeddings / stage3QwenTagging 对外暴露
    // 保留原 processPhoto 用于 processSingle 单张场景

    // ═══════════════════════════════════════════════════
    //  Stage 1: Face ROI + 106 关键点检测
    // ═══════════════════════════════════════════════════

    private suspend fun stage1FaceDetection(bitmap: Bitmap, lensFacing: Int): Stage1Result {
        val result = faceDetector.detectPhoto(bitmap, lensFacing)
        if (result == null) {
            return Stage1Result(false)
        }

        val pointsPerFace = 106 * 2
        val faceCount = result.landmarks106.size / pointsPerFace
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height

        val roiRects = mutableListOf<RectF>()
        for (faceIdx in 0 until faceCount) {
            val offset = faceIdx * pointsPerFace
            val facePoints = result.landmarks106.sliceArray(offset until offset + pointsPerFace)

            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            for (i in 0 until pointsPerFace) {
                val v = facePoints[i]
                if (i % 2 == 0) {
                    if (v < minX) minX = v
                    if (v > maxX) maxX = v
                } else {
                    if (v < minY) minY = v
                    if (v > maxY) maxY = v
                }
            }
            roiRects.add(RectF(
                minX * imageWidth,
                minY * imageHeight,
                maxX * imageWidth,
                maxY * imageHeight
            ))
        }

        return Stage1Result(
            hasFace = true,
            faceCount = faceCount,
            roiRects = roiRects,
            rawLandmarks = result.landmarks106.copyOf()
        )
    }

    // ═══════════════════════════════════════════════════
    //  Stage 2: MobileFaceNet 特征提取 → 人脸聚类
    // ═══════════════════════════════════════════════════

    private suspend fun stage2FaceCluster(
        bitmap: Bitmap,
        mediaId: Long,
        stage1Result: Stage1Result
    ): Stage2Result? {
        val embeddings = mutableListOf<FaceEmbeddingOutput>()

        for (i in stage1Result.roiRects.indices) {
            val roi = stage1Result.roiRects[i]
            val pointsPerFace = 106 * 2
            val offset = i * pointsPerFace
            val faceLandmarks = stage1Result.rawLandmarks.sliceArray(
                offset until minOf(offset + pointsPerFace, stage1Result.rawLandmarks.size)
            )

            val feature = faceClusterEngine.extractFeature(bitmap, roi, faceLandmarks)

            val matchedPersonId = faceClusterEngine.matchCluster(feature)

            val personId: Long = if (matchedPersonId != null) {
                faceClusterEngine.addToCluster(matchedPersonId, feature, mediaId)
                matchedPersonId
            } else {
                faceClusterEngine.createCluster(feature, mediaId)
            }

            embeddings.add(FaceEmbeddingOutput(mediaId, feature, personId))
        }

        return Stage2Result(
            faceEmbeddings = embeddings,
            personIds = embeddings.mapNotNull { it.personId }
        )
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
            val userPrompt = promptProvider.userPrompt(
                targetLanguage,
                if (stage1Result.hasFace) stage1Result.faceCount else 0,
                stage1Result.isGroupPhoto
            )

            val response = runVisionInference(bitmap, userPrompt)

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
     * 带 OpenCL 守护的多模态推理
     *
     * - 若 [openClGuardian] 存在，则使用其超时保护与自动降级逻辑
     * - 若 OpenCL 路径返回 Timeout，自动降级到 CPU 并立即重试一次
     * - 若不存在 Guardian，回退到原始 llmEngine.imageInference
     */
    private suspend fun runVisionInference(bitmap: Bitmap, userPrompt: String): String {
        val guardian = openClGuardian
        if (guardian != null) {
            val result = guardian.inference(
                bitmap = bitmap,
                systemPrompt = stage3SystemPrompt,
                userPrompt = userPrompt,
                maxTokens = QWEN_MAX_TOKENS
            )
            return when (result) {
                is OpenClInferenceResult.Success -> result.response
                is OpenClInferenceResult.Timeout -> {
                    Log.w(TAG, "OpenCL timeout, falling back to CPU and retry once")
                    guardian.fallbackToCpu("OpenCL timeout during Pass 3")
                    val retry = guardian.inference(
                        bitmap = bitmap,
                        systemPrompt = stage3SystemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = QWEN_MAX_TOKENS
                    )
                    when (retry) {
                        is OpenClInferenceResult.Success -> retry.response
                        is OpenClInferenceResult.Timeout -> "__ERROR_OPENCL_TIMEOUT__"
                        is OpenClInferenceResult.Error -> "__ERROR_${retry.message}"
                    }
                }
                is OpenClInferenceResult.Error -> "__ERROR_${result.message}"
            }
        }

        return llmEngine.imageInference(
            bitmap = bitmap,
            systemPrompt = stage3SystemPrompt,
            userPrompt = userPrompt,
            maxTokens = QWEN_MAX_TOKENS
        )
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
