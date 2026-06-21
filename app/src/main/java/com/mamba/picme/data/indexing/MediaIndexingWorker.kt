package com.mamba.picme.data.indexing

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 媒体元数据索引器
 *
 * 后台扫描未索引的图片（indexedAt IS NULL），
 * 提取 ML Kit 标签、OCR 文字、GPS 位置并写入 Room DB。
 *
 * 批量处理（每批 20 张），支持断点续扫（indexedAt 字段保证）。
 * 使用协程后台执行，不阻塞主线程。
 */
class MediaIndexingWorker(private val context: Context) {

    companion object {
        private const val TAG = "PicMe:MediaIndex"
        private const val BATCH_SIZE = 20
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    /** 索引是否正在运行 */
    val isRunning: Boolean
        get() = currentJob?.isActive == true

    /**
     * 启动后台索引（如已有任务运行则忽略）
     */
    fun start() {
        if (currentJob?.isActive == true) {
            Logger.d(TAG, "Indexing already in progress, skipping")
            return
        }
        currentJob = scope.launch {
            Logger.i(TAG, "Indexing started")
            doIndex()
            Logger.i(TAG, "Indexing completed")
        }
    }

    /**
     * 取消正在运行的索引
     */
    fun cancel() {
        currentJob?.cancel()
        Logger.i(TAG, "Indexing cancelled")
    }

    private suspend fun doIndex() {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()
        val extractor = MetadataExtractor(context)

        try {
            var indexedCount = 0
            var cancelled = false
            while (!cancelled) {
                val batch = dao.getUnindexedMedia().take(BATCH_SIZE)
                if (batch.isEmpty()) {
                    Logger.i(TAG, "All media indexed, total: $indexedCount")
                    break
                }

                for (entity in batch) {
                    // 检查是否被取消
                    if (currentJob?.isActive != true) {
                        cancelled = true
                        break
                    }

                    try {
                        val uri = Uri.parse(entity.uri)
                        val inputImage = InputImage.fromFilePath(context, uri)
                        val result = extractor.extract(uri, inputImage)

                        dao.updateIndexResult(
                            mediaId = entity.id,
                            labels = result.labelsJson,
                            ocrText = result.ocrText,
                            latitude = result.latitude,
                            longitude = result.longitude,
                            locationName = result.locationName,
                            indexedAt = System.currentTimeMillis()
                        )
                        indexedCount++
                    } catch (e: Exception) {
                        Logger.w(TAG, "Index failed for media ${entity.id}: ${e.message}")
                        // 标记为已尝试，避免死循环
                        dao.updateIndexResult(
                            mediaId = entity.id,
                            labels = entity.labels,
                            ocrText = entity.ocrText,
                            latitude = entity.latitude,
                            longitude = entity.longitude,
                            locationName = entity.locationName,
                            indexedAt = -1L
                        )
                    }
                }

                Logger.d(TAG, "Batch complete: $indexedCount indexed")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Indexing failed", e)
        } finally {
            extractor.close()
        }
    }
}
