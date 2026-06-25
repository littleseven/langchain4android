package com.mamba.picme.domain.tag

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.mamba.picme.beauty.api.facedetect.FaceDetectorFactory
import com.mamba.picme.beauty.api.facedetect.InferenceBackendType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * 标签生成批处理调度器
 *
 * 管理全量扫描和单张处理的调度，提供进度回调和取消支持。
 *
 * ## 架构
 * - 通过 [dispatcher] 参数控制执行线程（Service 场景传入单线程调度器保证串行）
 * - 通过 [guard] 在每张照片处理前检查是否允许继续（电池/热状态守卫）
 *
 * ## 触发方式
 * - [scanAll]：全量扫描所有照片
 * - [scanIncremental]：增量扫描未标记照片
 * - [processSingle]：处理单张新照片
 * - [cancel]：取消进行中的扫描
 */
class TagGenerationScheduler(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val guard: suspend () -> GuardResult = { GuardResult.ALLOW },
    private val getThrottleMs: () -> Long = { 1000L }
) {

    companion object {
        private const val TAG = "TagScheduler"

        /** Qwen 模型 ID */
        private const val MODEL_KEY = "qwen3_5_2b"

        /** 批次大小：每处理此数量照片后强制冷却 */
        private const val BATCH_SIZE = 10

        /** 批次间强制冷却时间（ms） */
        private const val BATCH_COOLDOWN_MS = 15_000L

        /** 增量扫描单次最大处理量 */
        private const val INCREMENTAL_MAX_PHOTOS = 50
    }

    /**
     * 守卫检查结果
     */
    enum class GuardResult {
        /** 允许继续 */
        ALLOW,
        /** 暂停等待（增大节流间隔） */
        PAUSE,
        /** 终止扫描 */
        ABORT
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var currentJob: Job? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _progress = MutableStateFlow<TagScanProgress?>(null)
    val progress: StateFlow<TagScanProgress?> = _progress.asStateFlow()

    private val _lastScanMessage = MutableStateFlow<String?>(null)
    val lastScanMessage: StateFlow<String?> = _lastScanMessage.asStateFlow()

    private val db = AppDatabase.getDatabase(context)
    private val personDao = db.personDao()
    private val vocab = ControlledVocab.loadFromAssets(context)
    private val normalizer = TagNormalizer(vocab)
    private val faceClusterEngine = FaceClusterEngine(context)

    private val pipeline: TagGenerationPipeline by lazy {
        val faceDetector = FaceDetectorFactory.create(context)
        // 【关键修复】必须调用 updatePipelineConfig()，否则 FaceDetectorManager
        // 的 isPipelineInitialized 保持 false，所有 detectPhoto() 静默返回 null
        faceDetector.updatePipelineConfig(DetectionPipelineConfig(
            roiEngine = InferenceBackendType.MNN,
            landmarkEngine = InferenceBackendType.MNN
        ))
        val llmEngine = AgentOrchestrator.getInstance(context).getLlmEngine()
        TagGenerationPipeline(context, faceDetector, llmEngine, faceClusterEngine, normalizer)
    }

    /** 触发全量 3-Pass 混合扫描 */
    fun scanAll(progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Scan already in progress, ignoring")
            return
        }

        currentJob = scope.launch {
            try {
                _isScanning.value = true
                Log.i(TAG, "=== 3-Pass Hybrid Scan started ===")

                if (!ensureModelLoaded()) {
                    Log.w(TAG, "Model not loaded, aborting")
                    return@launch
                }

                val dao = db.mediaDao()
                val allMedia = dao.getAllMediaNow()
                val total = allMedia.size
                Log.i(TAG, "Total media: $total")

                if (total == 0) {
                    _progress.value = TagScanProgress(0, 0, PipelineStage.COMPLETE)
                    return@launch
                }

                // ═══════════════════════════════════════════════
                //  Pass 1: 人脸检测 + Embedding 提取
                // ═══════════════════════════════════════════════
                _progress.value = TagScanProgress(0, total, PipelineStage.FACE_ROI)
                Log.i(TAG, "=== Pass 1: Face detection + Embedding ===")

                // 清理旧 embedding 和 persons，确保 Pass 2 结果正确
                personDao.clearAllEmbeddings()
                personDao.clearAllPersons()
                dao.resetAllFaceIds()

                var pass1Processed = 0
                for (entity in allMedia) {
                    if (!isActive) {
                        Log.i(TAG, "Pass 1 cancelled at $pass1Processed/$total")
                        break
                    }
                    if (!guardCheck()) break

                    try {
                        val result = pipeline.stage1WithEmbeddings(
                            uri = entity.uri,
                            lensFacing = CameraSelector.LENS_FACING_BACK,
                            mediaId = entity.id
                        )

                        // 持久化 faceRoi 结果
                        if (result.faceRoiJson != null) {
                            dao.updateFaceRoiResult(entity.id, result.faceRoiJson, true)
                        }

                        // 写入 embeddings（personId=null，供 Pass 2 DBSCAN）
                        for (embedding in result.embeddings) {
                            personDao.insertEmbedding(
                                com.mamba.picme.data.local.entity.FaceEmbeddingEntity(
                                    mediaId = entity.id,
                                    personId = null,
                                    embedding = floatArrayToByteArray(embedding)
                                )
                            )
                        }

                        pass1Processed++
                        _progress.value = TagScanProgress(pass1Processed, total, PipelineStage.FACE_ROI)
                        progressCallback(pass1Processed, total)
                        delay(getThrottleMs())
                    } catch (e: Exception) {
                        Log.w(TAG, "Pass 1 failed for media ${entity.id}: ${e.message}")
                    }
                }

                if (!isActive) return@launch

                // ═══════════════════════════════════════════════
                //  Pass 2: DBSCAN 全局聚类
                // ═══════════════════════════════════════════════
                _progress.value = TagScanProgress(pass1Processed, total, PipelineStage.FACE_CLUSTER)
                Log.i(TAG, "=== Pass 2: DBSCAN clustering ===")

                try {
                    runDbscanClustering(dao)
                    Log.i(TAG, "Pass 2 completed")
                } catch (e: Exception) {
                    Log.w(TAG, "Pass 2 failed: ${e.message}")
                }

                if (!isActive) return@launch

                // ═══════════════════════════════════════════════
                //  Pass 3: Qwen 图像理解标签生成
                // ═══════════════════════════════════════════════
                val needTagging = dao.getMediaWithFaceRoiWithoutLabels()
                val taggingTotal = needTagging.size
                Log.i(TAG, "=== Pass 3: Qwen tagging ($taggingTotal media) ===")

                var pass3Processed = 0
                for (entity in needTagging) {
                    if (!isActive) {
                        Log.i(TAG, "Pass 3 cancelled at $pass3Processed/$taggingTotal")
                        break
                    }
                    if (!guardCheck()) break

                    try {
                        val faceRoiJson = dao.getFaceRoiResult(entity.id)
                        val normalized = pipeline.stage3QwenTagging(entity.uri, faceRoiJson)

                        val unified = UnifiedTagResult(
                            scene = normalized.scene,
                            activity = normalized.activity,
                            objects = normalized.objects,
                            tags = normalized.tags,
                            qwenSummary = normalized.summary
                        )
                        val resultJson = unifiedTagToJson(unified)
                        dao.updateLabels(entity.id, resultJson)

                        pass3Processed++
                        val overallProcessed = pass1Processed + pass3Processed
                        _progress.value = TagScanProgress(overallProcessed, total, PipelineStage.QWEN_TAGGING)
                        progressCallback(overallProcessed, total)
                        delay(getThrottleMs())

                        // 批次冷却：每 BATCH_SIZE 张后强制冷却，防止连续 LLM 推理导致过热
                        if (pass3Processed % BATCH_SIZE == 0 && pass3Processed < taggingTotal) {
                            Log.i(TAG, "Pass 3 batch cooldown after $pass3Processed/$taggingTotal")
                            delay(BATCH_COOLDOWN_MS)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Pass 3 failed for media ${entity.id}: ${e.message}")
                    }
                }

                _progress.value = TagScanProgress(total, total, PipelineStage.COMPLETE)
                Log.i(TAG, "=== 3-Pass Hybrid Scan completed: " +
                    "P1=$pass1Processed/$total, P3=$pass3Processed/$taggingTotal ===")
            } finally {
                _isScanning.value = false
                unloadLlm()
            }
        }
    }

    /** 处理单张新照片 */
    suspend fun processSingle(uri: String, mediaId: Long) {
        scope.launch {
            try {
                if (!ensureModelLoaded()) return@launch

                val resultJson = pipeline.processPhoto(
                    uri = uri,
                    lensFacing = CameraSelector.LENS_FACING_BACK,
                    mediaId = mediaId
                )

                if (resultJson.isNotEmpty()) {
                    db.mediaDao().updateLabels(mediaId, resultJson)
                }
                Log.d(TAG, "Single photo processed: mediaId=$mediaId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to process single photo $mediaId: ${e.message}")
            }
        }
    }

    /** 取消进行中的扫描 */
    fun cancel() {
        currentJob?.cancel()
        _isScanning.value = false
        Log.i(TAG, "Scan cancelled")
    }

    /**
     * 增量扫描：3-Pass 混合模型，仅处理未标记标签的照片
     *
     * 与 [scanAll] 的全量不同，跳过已有 labels 的媒体。
     * - 对未检测 faceRoi 的媒体执行 Pass 1
     * - 若新增 embedding 则执行 Pass 2 DBSCAN
     * - 对已检测但无标签的媒体执行 Pass 3
     *
     * @param maxPhotos Pass 3 最大处理量，防止自动触发时连续长时间推理
     */
    fun scanIncremental(
        maxPhotos: Int = INCREMENTAL_MAX_PHOTOS,
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Scan already in progress, ignoring")
            return
        }

        currentJob = scope.launch {
            try {
                _isScanning.value = true
                Log.i(TAG, "=== Incremental 3-Pass Scan started ===")

                if (!ensureModelLoaded()) {
                    Log.w(TAG, "Model not loaded, aborting")
                    return@launch
                }

                val dao = db.mediaDao()
                val unlabeledMedia = dao.getUnlabeledMedia()
                val total = unlabeledMedia.size

                if (total == 0) {
                    Log.i(TAG, "All media already tagged, nothing to do")
                    _progress.value = TagScanProgress(0, 0, PipelineStage.COMPLETE)
                    return@launch
                }

                val allCount = dao.getTotalCount()
                val alreadyDone = allCount - total

                // ── Pass 1: 未检测 ROI 的媒体 ──────────────
                val needRoi = unlabeledMedia.filter { dao.getFaceRoiResult(it.id) == null }
                var pass1Processed = 0

                if (needRoi.isNotEmpty()) {
                    _progress.value = TagScanProgress(alreadyDone, allCount, PipelineStage.FACE_ROI)
                    Log.i(TAG, "Incremental Pass 1: ${needRoi.size} media need face detection")

                    for (entity in needRoi) {
                        if (!isActive) break
                        if (!guardCheck()) break

                        try {
                            val result = pipeline.stage1WithEmbeddings(
                                uri = entity.uri,
                                lensFacing = CameraSelector.LENS_FACING_BACK,
                                mediaId = entity.id
                            )

                            if (result.faceRoiJson != null) {
                                dao.updateFaceRoiResult(entity.id, result.faceRoiJson, true)
                            }

                            for (embedding in result.embeddings) {
                                personDao.insertEmbedding(
                                    FaceEmbeddingEntity(
                                        mediaId = entity.id,
                                        personId = null,
                                        embedding = floatArrayToByteArray(embedding)
                                    )
                                )
                            }

                            pass1Processed++
                            val current = alreadyDone + pass1Processed
                            _progress.value = TagScanProgress(current, allCount, PipelineStage.FACE_ROI)
                            progressCallback(current, allCount)
                            delay(getThrottleMs())
                        } catch (e: Exception) {
                            Log.w(TAG, "Incremental Pass 1 failed for media ${entity.id}: ${e.message}")
                        }
                    }

                    // ── Pass 2: 增量 DBSCAN ────────────────
                    try {
                        runDbscanClustering(dao)
                        Log.i(TAG, "Incremental Pass 2 completed")
                    } catch (e: Exception) {
                        Log.w(TAG, "Incremental Pass 2 failed: ${e.message}")
                    }
                }

                if (!isActive) return@launch

                // ── Pass 3: Qwen 标签生成 ────────────────
                val needTagging = dao.getMediaWithFaceRoiWithoutLabels()
                if (needTagging.isNotEmpty()) {
                    val cappedTagging = if (needTagging.size > maxPhotos) {
                        Log.i(TAG, "Incremental Pass 3: capping at $maxPhotos/${needTagging.size} to prevent overheating")
                        needTagging.take(maxPhotos)
                    } else {
                        needTagging
                    }
                    _progress.value = TagScanProgress(alreadyDone + pass1Processed, allCount, PipelineStage.QWEN_TAGGING)
                    Log.i(TAG, "Incremental Pass 3: ${cappedTagging.size} media need Qwen tagging")

                    var pass3Processed = 0
                    for (entity in cappedTagging) {
                        if (!isActive) break
                        if (!guardCheck()) break

                        try {
                            val faceRoiJson = dao.getFaceRoiResult(entity.id)
                            val normalized = pipeline.stage3QwenTagging(entity.uri, faceRoiJson)

                            val unified = UnifiedTagResult(
                                scene = normalized.scene,
                                activity = normalized.activity,
                                objects = normalized.objects,
                                tags = normalized.tags,
                                qwenSummary = normalized.summary
                            )
                            dao.updateLabels(entity.id, unifiedTagToJson(unified))

                            pass3Processed++
                            val current = alreadyDone + pass1Processed + pass3Processed
                            _progress.value = TagScanProgress(current, allCount, PipelineStage.QWEN_TAGGING)
                            progressCallback(current, allCount)
                            delay(getThrottleMs())

                            // 批次冷却
                            if (pass3Processed % BATCH_SIZE == 0 && pass3Processed < cappedTagging.size) {
                                Log.i(TAG, "Incremental Pass 3 batch cooldown after $pass3Processed/${cappedTagging.size}")
                                delay(BATCH_COOLDOWN_MS)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Incremental Pass 3 failed for media ${entity.id}: ${e.message}")
                        }
                    }
                }

                val finalDone = alreadyDone + pass1Processed
                _progress.value = TagScanProgress(finalDone, allCount, PipelineStage.COMPLETE)
                Log.i(TAG, "Incremental scan completed: P1=$pass1Processed, total=${finalDone}/$allCount")
            } finally {
                _isScanning.value = false
                unloadLlm()
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  独立阶段扫描（分阶段批量控制）
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 1 独立执行] 全量人脸检测 + Embedding 提取
     *
     * - 遍历所有媒体，重新执行人脸检测和 embedding 提取
     * - faceRoiResult 写入 media_assets 表（供 Pass 3 使用）
     * - embedding 写入 face_embeddings 表（personId=null，供 Pass 2 聚类）
     * - 注意：不会清除旧 embedding，多次执行会产生重复数据
     */
    fun scanPass1(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Scan already in progress, ignoring")
            return
        }

        currentJob = scope.launch {
            try {
                _isScanning.value = true
                Log.i(TAG, "=== Pass 1 Only: Face detection + Embedding ===")

                // Pass 1 使用 InsightFace + MobileFaceNet，由 Pipeline 内部懒加载
                val dao = db.mediaDao()

                // 清理旧 embedding，确保全量重提取
                personDao.clearAllEmbeddings()
                personDao.clearAllPersons()

                val allMedia = dao.getAllMediaNow()
                val total = allMedia.size
                var processed = 0

                _progress.value = TagScanProgress(0, total, PipelineStage.FACE_ROI)

                for (entity in allMedia) {
                    if (!isActive) break
                    if (!guardCheck()) break

                    try {
                        val result = pipeline.stage1WithEmbeddings(
                            uri = entity.uri,
                            lensFacing = CameraSelector.LENS_FACING_BACK,
                            mediaId = entity.id
                        )

                        if (result.faceRoiJson != null) {
                            dao.updateFaceRoiResult(entity.id, result.faceRoiJson, true)
                        }
                        for (embedding in result.embeddings) {
                            personDao.insertEmbedding(
                                FaceEmbeddingEntity(
                                    mediaId = entity.id,
                                    personId = null,
                                    embedding = floatArrayToByteArray(embedding)
                                )
                            )
                        }

                        processed++
                        _progress.value = TagScanProgress(processed, total, PipelineStage.FACE_ROI)
                        progressCallback(processed, total)
                        delay(getThrottleMs())
                    } catch (e: Exception) {
                        Log.w(TAG, "Pass 1 failed for media ${entity.id}: ${e.message}")
                        processed++
                    }
                }

                _progress.value = TagScanProgress(processed, total, PipelineStage.COMPLETE)
                Log.i(TAG, "=== Pass 1 Only completed: $processed/$total ===")
            } finally {
                _isScanning.value = false
            }
        }
    }

    /**
     * [Pass 2 独立执行] DBSCAN 全局聚类
     *
     * 清除旧的聚类结果，基于 face_embeddings 表中**所有** embedding
     * 重新执行 DBSCAN 全量聚类。
     *
     * 与 [scanAll] 内部 Pass 2 的区别：
     * - scanAll 的 Pass 2 仅聚类 Pass 1 刚生成的新 embedding
     * - 本方法重置所有分配后，对所有 embedding 重新聚类
     *
     * 前置条件：face_embeddings 表中已有 embedding（需先执行 Pass 1）
     */
    fun scanPass2(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Scan already in progress, ignoring")
            return
        }

        currentJob = scope.launch {
            try {
                _isScanning.value = true
                Log.i(TAG, "=== Pass 2 Only: Full re-clustering ===")

                val dao = db.mediaDao()

                // 清除旧聚类结果，准备重聚类（保留 hasFace 不变）
                // 注意：先重置 embedding 再删除 persons，利用 SET_NULL 外键约束
                // 避免由 cascade 或并发问题造成 embedding 意外丢失
                personDao.resetAllEmbeddingAssignments()
                personDao.clearAllPersons()

                val allEmbeddings = personDao.getAllEmbeddingCount()
                _progress.value = TagScanProgress(0, allEmbeddings, PipelineStage.FACE_CLUSTER)

                try {
                    runDbscanClustering(dao)
                    val afterPersons = personDao.getAllPersons().size
                    val afterEmbeddings = personDao.getAllEmbeddingCount()
                    Log.i(TAG, "Pass 2 Only completed: $afterPersons persons from $afterEmbeddings embeddings")
                    _lastScanMessage.value = "聚类完成: $afterPersons 个人物簇 (共 $afterEmbeddings 个 embedding)"
                } catch (e: Exception) {
                    Log.w(TAG, "Pass 2 Only failed: ${e.message}")
                    _lastScanMessage.value = "聚类失败: ${e.message}"
                }

                _progress.value = TagScanProgress(allEmbeddings, allEmbeddings, PipelineStage.COMPLETE)
            } finally {
                _isScanning.value = false
            }
        }
    }

    /**
     * [Pass 3 独立执行] 仅进行 Qwen 图像理解标签生成
     *
     * - 遍历所有有 faceRoi 但无 labels 的媒体
     * - 调用 Qwen 多模态模型生成标签
     */
    fun scanPass3(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Scan already in progress, ignoring")
            return
        }

        currentJob = scope.launch {
            try {
                _isScanning.value = true
                Log.i(TAG, "=== Pass 3 Only: Qwen tagging ===")

                if (!ensureModelLoaded()) return@launch

                val dao = db.mediaDao()
                val needTagging = dao.getMediaWithFaceRoiWithoutLabels()
                val total = needTagging.size
                var processed = 0

                _progress.value = TagScanProgress(0, total, PipelineStage.QWEN_TAGGING)

                for (entity in needTagging) {
                    if (!isActive) break
                    if (!guardCheck()) break

                    try {
                        val faceRoiJson = dao.getFaceRoiResult(entity.id)
                        val normalized = pipeline.stage3QwenTagging(entity.uri, faceRoiJson)

                        val unified = UnifiedTagResult(
                            scene = normalized.scene,
                            activity = normalized.activity,
                            objects = normalized.objects,
                            tags = normalized.tags,
                            qwenSummary = normalized.summary
                        )
                        dao.updateLabels(entity.id, unifiedTagToJson(unified))

                        processed++
                        _progress.value = TagScanProgress(processed, total, PipelineStage.QWEN_TAGGING)
                        progressCallback(processed, total)
                        delay(getThrottleMs())

                        // 批次冷却
                        if (processed % BATCH_SIZE == 0 && processed < total) {
                            Log.i(TAG, "Pass 3 batch cooldown after $processed/$total")
                            delay(BATCH_COOLDOWN_MS)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Pass 3 failed for media ${entity.id}: ${e.message}")
                        processed++
                    }
                }

                _progress.value = TagScanProgress(processed, total, PipelineStage.COMPLETE)
                Log.i(TAG, "=== Pass 3 Only completed: $processed/$total ===")
            } finally {
                _isScanning.value = false
                unloadLlm()
            }
        }
    }

    /**
     * [Pass 3 重新生成] 清空已有标签后全量重标
     *
     * - 调用 [resetAllLabels] 清空所有已有标签
     * - 然后对**所有**有 faceRoi 的媒体重新运行 Qwen 标签生成
     * - 适用于用户更新受控词表或 Prompt 后需要刷新标签
     */
    fun scanPass3Full(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Scan already in progress, ignoring")
            return
        }

        currentJob = scope.launch {
            try {
                _isScanning.value = true
                Log.i(TAG, "=== Pass 3 Full Regeneration started ===")

                if (!ensureModelLoaded()) return@launch

                val dao = db.mediaDao()

                // 1. 清空所有已标签，使 getMediaWithFaceRoiWithoutLabels() 返回全部
                Log.i(TAG, "Resetting all labels for full regeneration...")
                dao.resetAllLabels()

                // 2. 现在所有有 faceRoi 的媒体都变回"无标签"状态
                val needTagging = dao.getMediaWithFaceRoiWithoutLabels()
                val total = needTagging.size
                var processed = 0

                _progress.value = TagScanProgress(0, total, PipelineStage.QWEN_TAGGING)
                Log.i(TAG, "Pass 3 Full: $total media need tagging")

                for (entity in needTagging) {
                    if (!isActive) break
                    if (!guardCheck()) break

                    try {
                        val faceRoiJson = dao.getFaceRoiResult(entity.id)
                        val normalized = pipeline.stage3QwenTagging(entity.uri, faceRoiJson)

                        val unified = UnifiedTagResult(
                            scene = normalized.scene,
                            activity = normalized.activity,
                            objects = normalized.objects,
                            tags = normalized.tags,
                            qwenSummary = normalized.summary
                        )
                        dao.updateLabels(entity.id, unifiedTagToJson(unified))

                        processed++
                        _progress.value = TagScanProgress(processed, total, PipelineStage.QWEN_TAGGING)
                        progressCallback(processed, total)
                        delay(getThrottleMs())

                        // 批次冷却
                        if (processed % BATCH_SIZE == 0 && processed < total) {
                            Log.i(TAG, "Pass 3 Full batch cooldown after $processed/$total")
                            delay(BATCH_COOLDOWN_MS)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Pass 3 Full failed for media ${entity.id}: ${e.message}")
                        processed++
                    }
                }

                _progress.value = TagScanProgress(processed, total, PipelineStage.COMPLETE)
                Log.i(TAG, "=== Pass 3 Full regeneration completed: $processed/$total ===")
                _lastScanMessage.value = "Pass 3 重新生成完成: $processed/$total 张"
            } finally {
                _isScanning.value = false
                unloadLlm()
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  守卫检查
    // ═══════════════════════════════════════════════════

    /** 电池/热状态守卫检查 */
    private suspend fun guardCheck(): Boolean {
        when (guard()) {
            GuardResult.ABORT -> {
                Log.i(TAG, "Guard ABORT")
                return false
            }
            GuardResult.PAUSE -> {
                Log.d(TAG, "Guard PAUSE, extended throttle (${getThrottleMs()}ms)")
                delay(getThrottleMs())
                // PAUSE 不跳过当前照片，仅增加节流
            }
            GuardResult.ALLOW -> { /* continue */ }
        }
        return true
    }

    // ═══════════════════════════════════════════════════
    //  Pass 2: DBSCAN 全局聚类
    // ═══════════════════════════════════════════════════

    /**
     * 基于 face_embeddings 表执行 DBSCAN 全局聚类
     */
    private suspend fun runDbscanClustering(dao: com.mamba.picme.data.local.MediaDao) {
        val unassigned = personDao.getUnassignedEmbeddings()
        if (unassigned.isEmpty()) {
            Log.i(TAG, "DBSCAN: no unassigned embeddings, skipping")
            return
        }

        // 按 mediaId 分组
        val embeddingsMap = mutableMapOf<Long, MutableList<FloatArray>>()
        for (emb in unassigned) {
            val feature = byteArrayToFloatArray(emb.embedding)
            embeddingsMap.getOrPut(emb.mediaId) { mutableListOf() }.add(feature)
        }

        if (embeddingsMap.size < 2) {
            // 仅一张照片有 face：直接建单簇
            if (embeddingsMap.size == 1) {
                val singleMediaId = embeddingsMap.keys.first()
                val personId = personDao.insertPerson(
                    com.mamba.picme.data.local.entity.PersonEntity(
                        faceCount = embeddingsMap[singleMediaId]!!.size,
                        coverMediaId = singleMediaId
                    )
                )
                dao.updateFaceId(singleMediaId, personId.toString())
                personDao.assignEmbeddingByMediaId(mediaId = singleMediaId, personId = personId)
                Log.i(TAG, "DBSCAN: single media with faces -> personId=$personId")
            }
            return
        }

        // 展平索引
        val flatIndex = mutableListOf<Pair<Long, Int>>()
        for ((mediaId, faceEmbs) in embeddingsMap) {
            for (i in faceEmbs.indices) {
                flatIndex.add(mediaId to i)
            }
        }

        // DBSCAN
        var clusters = dbscanCluster(embeddingsMap, flatIndex, ClusteringConfig.DBSCAN_EPS, ClusteringConfig.DBSCAN_MIN_PTS)
        Log.i(TAG, "DBSCAN: ${clusters.size} clusters from ${flatIndex.size} face embeddings")

        // 验证簇内部一致性，分裂不健康的簇
        clusters = validateAndSplitClusters(clusters, embeddingsMap)

        // 分配 personId
        val sorted = clusters.entries
            .filter { it.key != -1 }
            .sortedByDescending { it.value.size }

        var assignedCount = 0
        for ((index, entry) in sorted.withIndex()) {
            val mediaIds = entry.value.map { it.first }.distinct()
            val totalFaces = entry.value.size
            val personId = personDao.insertPerson(
                com.mamba.picme.data.local.entity.PersonEntity(
                    faceCount = totalFaces,
                    coverMediaId = mediaIds.firstOrNull()
                )
            )
            for ((mid, _) in entry.value) {
                dao.updateFaceId(mid, personId.toString())
                assignedCount++
            }
            // 给簇内所有 media 的 embedding 赋 personId
            for (mid in mediaIds) {
                personDao.assignEmbeddingByMediaId(mediaId = mid, personId = personId)
            }
        }

        val noiseCount = clusters[-1]?.size ?: 0
        Log.i(TAG, "DBSCAN done: $assignedCount media clustered into ${sorted.size} persons, $noiseCount noise")
    }

    /**
     * 验证簇内部一致性：计算簇内所有点对的平均余弦相似度。
     * 低于 ClusteringConfig.CLUSTER_COHESION_MIN 则用更严格的 eps 递归分裂。
     */
    private fun validateAndSplitClusters(
        clusters: Map<Int, List<Pair<Long, Int>>>,
        embeddings: Map<Long, List<FloatArray>>
    ): Map<Int, List<Pair<Long, Int>>> {
        val result = mutableMapOf<Int, List<Pair<Long, Int>>>()
        for ((clusterId, members) in clusters) {
            if (clusterId == -1 || members.size <= 2) {
                result[clusterId] = members
                continue
            }
            val sampleSize = minOf(members.size, 20)
            var totalSim = 0f
            var pairCount = 0
            for (i in 0 until sampleSize) {
                for (j in i + 1 until sampleSize) {
                    val embI = embeddings[members[i].first]?.getOrNull(members[i].second) ?: continue
                    val embJ = embeddings[members[j].first]?.getOrNull(members[j].second) ?: continue
                    totalSim += 1f - cosineDistance(embI, embJ)
                    pairCount++
                }
            }
            if (pairCount == 0) {
                result[clusterId] = members
                continue
            }
            val avgSimilarity = totalSim / pairCount
            Log.d(TAG, "Cluster $clusterId (${members.size} faces) avg similarity: ${String.format("%.3f", avgSimilarity)}")
            if (avgSimilarity < ClusteringConfig.CLUSTER_COHESION_MIN) {
                Log.w(TAG, "Cluster $clusterId cohesion too low (${String.format("%.3f", avgSimilarity)}), splitting")
                val subClusters = dbscanCluster(embeddings, members, ClusteringConfig.DBSCAN_EPS * 0.7f, ClusteringConfig.DBSCAN_MIN_PTS)
                var newId = clusterId * 1000
                for ((_, subMembers) in subClusters) {
                    result[newId++] = subMembers
                }
            } else {
                result[clusterId] = members
            }
        }
        return result
    }

    /** DBSCAN 核心算法 */
    private fun dbscanCluster(
        embeddings: Map<Long, List<FloatArray>>,
        flatIndex: List<Pair<Long, Int>>,
        eps: Float,
        minPts: Int
    ): Map<Int, List<Pair<Long, Int>>> {
        val n = flatIndex.size
        val labels = IntArray(n) { 0 }
        var clusterId = 0

        for (i in 0 until n) {
            if (labels[i] != 0) continue

            val centerEmb = embeddings[flatIndex[i].first]?.getOrNull(flatIndex[i].second) ?: continue
            val neighbors = mutableListOf<Int>()
            for (j in 0 until n) {
                if (i == j) continue
                val otherEmb = embeddings[flatIndex[j].first]?.getOrNull(flatIndex[j].second) ?: continue
                if (cosineDistance(centerEmb, otherEmb) <= eps) {
                    neighbors.add(j)
                }
            }

            if (neighbors.size < minPts) {
                labels[i] = -1
                continue
            }

            clusterId++
            labels[i] = clusterId
            val seedSet = neighbors.toMutableList()

            var idx = 0
            while (idx < seedSet.size) {
                val q = seedSet[idx]
                if (labels[q] == -1) labels[q] = clusterId
                if (labels[q] == 0) {
                    labels[q] = clusterId
                    val qEmb = embeddings[flatIndex[q].first]?.getOrNull(flatIndex[q].second) ?: run { idx++; continue }
                    val qn = mutableListOf<Int>()
                    for (j in 0 until n) {
                        if (q == j) continue
                        val otherEmb = embeddings[flatIndex[j].first]?.getOrNull(flatIndex[j].second) ?: continue
                        if (cosineDistance(qEmb, otherEmb) <= eps) {
                            qn.add(j)
                        }
                    }
                    if (qn.size >= minPts) {
                        for (ni in qn) {
                            if (ni !in seedSet) seedSet.add(ni)
                        }
                    }
                }
                idx++
            }
        }

        val result = mutableMapOf<Int, MutableList<Pair<Long, Int>>>()
        for (i in 0 until n) {
            val l = labels[i]
            result.getOrPut(l) { mutableListOf() }.add(flatIndex[i])
        }
        return result
    }

    /** 余弦距离: 1 - cosine_similarity，范围 [0, 2] */
    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val similarity = dot / (sqrt(normA) * sqrt(normB))
        return (1f - similarity).coerceAtLeast(0f)
    }

    // ═══════════════════════════════════════════════════
    //  序列化辅助
    // ═══════════════════════════════════════════════════

    private fun floatArrayToByteArray(array: FloatArray): ByteArray {
        val bytes = ByteArray(array.size * 4)
        for (i in array.indices) {
            val bits = java.lang.Float.floatToRawIntBits(array[i])
            bytes[i * 4] = (bits shr 24).toByte()
            bytes[i * 4 + 1] = (bits shr 16).toByte()
            bytes[i * 4 + 2] = (bits shr 8).toByte()
            bytes[i * 4 + 3] = bits.toByte()
        }
        return bytes
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            val bits = ((bytes[i * 4].toInt() and 0xFF) shl 24) or
                    ((bytes[i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i * 4 + 3].toInt() and 0xFF)
            floats[i] = java.lang.Float.intBitsToFloat(bits)
        }
        return floats
    }

    private fun unifiedTagToJson(result: UnifiedTagResult): String {
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

    private suspend fun ensureModelLoaded(): Boolean {
        val engine = AgentOrchestrator.getInstance(context).getLlmEngine()

        if (!engine.isModelAvailable(MODEL_KEY, context)) {
            Log.w(TAG, "Model not downloaded: $MODEL_KEY")
            return false
        }

        // 如果模型已加载（可能残留前序扫描状态），先清空 KV cache
        if (engine.isLoaded) {
            Log.i(TAG, "Model already loaded, trimming memory to clear stale state")
            engine.trimMemory()
            return true
        }

        // OpenCL GPU 优先 → CPU 降级
        // 由 MNN native 层自行判断 OpenCL 是否可用（不依赖 Java 层 System.loadLibrary）
        Log.i(TAG, "Loading LLM model with OpenCL (GPU): $MODEL_KEY")
        val openclResult = engine.loadModel(MODEL_KEY, useOpencl = true)
        if (openclResult.isSuccess) {
            Log.i(TAG, "Model loaded with OpenCL (GPU) acceleration")
            return true
        }
        Log.w(TAG, "OpenCL load failed: ${openclResult.exceptionOrNull()?.message}, " +
            "falling back to CPU")

        val cpuResult = engine.loadModel(MODEL_KEY, useOpencl = false)
        return if (cpuResult.isSuccess) {
            Log.i(TAG, "Model loaded with CPU")
            true
        } else {
            Log.w(TAG, "CPU load failed: ${cpuResult.exceptionOrNull()?.message}")
            false
        }
    }

    /**
     * 卸载 LLM 模型释放 ~4GB 内存
     *
     * 扫描完成后调用，防止后台服务持续占用内存和散热资源。
     * 使用 [LocalLlmEngine.trimMemory] 清理 KV cache 并释放模型权重。
     */
    private fun unloadLlm() {
        try {
            val engine = AgentOrchestrator.getInstance(context).getLlmEngine()
            if (engine.isLoaded) {
                Log.i(TAG, "Unloading LLM model to free memory")
                engine.trimMemory()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unload LLM: ${e.message}")
        }
    }
}
