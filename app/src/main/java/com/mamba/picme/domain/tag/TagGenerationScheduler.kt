package com.mamba.picme.domain.tag

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.beauty.api.facedetect.FaceDetectorFactory
import com.mamba.picme.data.local.AppDatabase
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

/**
 * 标签生成批处理调度器
 *
 * 管理全量扫描和单张处理的调度，提供进度回调和取消支持。
 *
 * ## 触发方式
 * - [scanAll]：全量扫描所有照片
 * - [processSingle]：处理单张新照片
 * - [cancel]：取消进行中的扫描
 */
class TagGenerationScheduler(private val context: Context) {

    companion object {
        private const val TAG = "TagScheduler"

        /** 单张处理后节流间隔（ms） */
        private const val THROTTLE_MS = 500L

        /** Qwen 模型 ID */
        private const val MODEL_KEY = "qwen3_5_2b"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _progress = MutableStateFlow<TagScanProgress?>(null)
    val progress: StateFlow<TagScanProgress?> = _progress.asStateFlow()

    private val db = AppDatabase.getDatabase(context)
    private val vocab = ControlledVocab.loadFromAssets(context)
    private val normalizer = TagNormalizer(vocab)
    private val faceClusterEngine = FaceClusterEngine(context)

    private val pipeline: TagGenerationPipeline by lazy {
        val faceDetector = FaceDetectorFactory.create(context)
        val llmEngine = AgentOrchestrator.getInstance(context).getLlmEngine()
        TagGenerationPipeline(context, faceDetector, llmEngine, faceClusterEngine, normalizer)
    }

    /** 触发全量扫描 */
    suspend fun scanAll(progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Scan already in progress, ignoring")
            return
        }

        currentJob = scope.launch {
            try {
                _isScanning.value = true
                Log.i(TAG, "Full tag scan started")

                // 加载模型
                if (!ensureModelLoaded()) {
                    Log.w(TAG, "Model not loaded, aborting")
                    return@launch
                }

                val dao = db.mediaDao()
                val allMedia = dao.getAllMediaNow()
                val total = allMedia.size
                Log.i(TAG, "Total media to scan: $total")

                _progress.value = TagScanProgress(0, total, PipelineStage.FACE_ROI)

                var processed = 0
                for (entity in allMedia) {
                    if (!isActive) {
                        Log.i(TAG, "Scan cancelled after $processed/$total")
                        break
                    }

                    try {
                        val resultJson = pipeline.processPhoto(
                            uri = entity.uri,
                            lensFacing = CameraSelector.LENS_FACING_BACK,
                            mediaId = entity.id
                        )

                        if (resultJson.isNotEmpty()) {
                            dao.updateLabels(entity.id, resultJson)
                            dao.updateHasFace(entity.id, resultJson.contains("\"count\":0"))
                        }

                        processed++
                        _progress.value = TagScanProgress(processed, total, PipelineStage.QWEN_TAGGING)
                        progressCallback(processed, total)

                        // 节流
                        delay(THROTTLE_MS)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process media ${entity.id}: ${e.message}")
                    }
                }

                _progress.value = TagScanProgress(processed, total, PipelineStage.COMPLETE)
                Log.i(TAG, "Full tag scan completed: $processed/$total")
            } finally {
                _isScanning.value = false
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

    private suspend fun ensureModelLoaded(): Boolean {
        val engine = AgentOrchestrator.getInstance(context).getLlmEngine()
        if (engine.isLoaded) return true

        if (!engine.isModelAvailable(MODEL_KEY, context)) {
            Log.w(TAG, "Model not downloaded: $MODEL_KEY")
            return false
        }

        Log.i(TAG, "Loading LLM model: $MODEL_KEY")
        val result = engine.loadModel(MODEL_KEY)
        return if (result.isSuccess) {
            Log.i(TAG, "Model loaded successfully")
            true
        } else {
            Log.w(TAG, "Model load failed: ${result.exceptionOrNull()?.message}")
            false
        }
    }
}
