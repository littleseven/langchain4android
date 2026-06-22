package com.mamba.picme.data.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.data.local.AppDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 人脸聚类 Worker
 *
 * 流程:
 * 1. ML Kit Face Detection → 人脸 ROI
 * 2. MobileFaceNet (ONNX Runtime) → 512维 embedding
 * 3. DBSCAN 聚类（embedding 空间）→ faceId
 */
class FaceClusteringWorker(private val context: Context) {

    companion object {
        private const val TAG = "PicMe:FaceCluster"
        private const val FACE_INPUT_SIZE = 112
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    val isRunning: Boolean
        get() = currentJob?.isActive == true

    fun start() {
        if (currentJob?.isActive == true) {
            Logger.d(TAG, "Clustering already in progress")
            return
        }
        currentJob = scope.launch {
            Logger.i(TAG, "Face clustering started")
            doCluster()
            Logger.i(TAG, "Face clustering completed")
        }
    }

    private suspend fun doCluster() {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()

        // Step 0: 尝试加载 MobileFaceNet MNN 模型（可选，用于 embedding + 聚类）
        val modelDir = ModelPathConfig.getModelDir(context, "picme-face-embedding-mnn")
        val embedder = MnnEmbeddingExtractor(File(modelDir, "w600k_mbf.mnn"))
        val hasEmbeddingModel = embedder.isModelReady && embedder.initialize()

        if (!hasEmbeddingModel) {
            Logger.w(TAG, "Face embedding model not found at ${modelDir}/w600k_mbf.mnn")
            Logger.w(TAG, "Will run face detection only (hasFace=true). Download model for person clustering.")
        }

        // Step 1: 人脸检测（ML Kit，CPU）—— 无论是否有 embedding 模型都会执行
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .build()
        )

        try {
            val allMedia = dao.getAllMediaNow()
            Logger.i(TAG, "Processing ${allMedia.size} photos for face detection" +
                if (hasEmbeddingModel) " + clustering" else " (detection only)")

            val embeddings = mutableMapOf<Long, FloatArray>()
            var faceCount = 0

            for (entity in allMedia) {
                if (!currentJob?.isActive!!) break

                val uri = Uri.parse(entity.uri)
                val original = loadBitmap(uri) ?: continue

                try {
                    // 人脸检测（始终执行）
                    val inputImage = InputImage.fromBitmap(original, 0)
                    val faces = com.google.android.gms.tasks.Tasks.await(
                        faceDetector.process(inputImage)
                    )

                    if (faces.isEmpty()) {
                        continue
                    }
                    faceCount++

                    // 设置 hasFace，使"人物"搜索立即可用
                    if (!entity.hasFace) {
                        dao.updateHasFace(entity.id, true)
                    }

                    // 如果有 embedding 模型，提取特征用于聚类
                    if (hasEmbeddingModel) {
                        val face = faces[0]
                        val faceBbox = face.boundingBox

                        val crop = safeCropFace(original, faceBbox)
                        if (crop != null) {
                            val emb = embedder.extractEmbedding(crop)
                            if (emb != null) {
                                embeddings[entity.id] = emb
                            }
                            crop.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to process ${entity.id}: ${e.message}")
                } finally {
                    original.recycle()
                }
            }

            Logger.i(TAG, "Face detection done: $faceCount faces found" +
                if (hasEmbeddingModel) ", ${embeddings.size} embeddings extracted" else "")

            // Step 2: DBSCAN 聚类（仅在有 embedding 模型时执行）
            if (!hasEmbeddingModel || embeddings.size < 2) {
                if (hasEmbeddingModel && embeddings.size == 1) {
                    Logger.i(TAG, "Only 1 embedding, assigning single cluster")
                    for ((mediaId) in embeddings) {
                        dao.updateFaceId(mediaId, "1")
                    }
                }
                // 模型不可用或 embedding 不足：搜索"人物"已可用（hasFace 已设置）
                return
            }

            val clusters = clusterEmbeddings(embeddings)
            Logger.i(TAG, "DBSCAN: ${clusters.size} clusters")

            var assignedCount = 0
            val sorted = clusters.entries
                .filter { it.key != -1 }
                .sortedByDescending { it.value.size }

            for ((index, entry) in sorted.withIndex()) {
                val clusterId = (index + 1).toString()
                for (mediaId in entry.value) {
                    dao.updateFaceId(mediaId, clusterId)
                    assignedCount++
                }
            }

            val noiseCount = clusters[-1]?.size ?: 0
            Logger.i(TAG, "Face clustering done: $assignedCount clustered, $noiseCount unclustered")
        } catch (e: Exception) {
            Logger.e(TAG, "Face clustering failed", e)
        } finally {
            faceDetector.close()
            if (hasEmbeddingModel) {
                embedder.close()
            }
        }
    }

    /**
     * DBSCAN 聚类（embedding 空间）
     */
    private fun clusterEmbeddings(
        embeddings: Map<Long, FloatArray>,
        eps: Float = 0.55f,
        minPts: Int = 1
    ): Map<Int, List<Long>> {
        val ids = embeddings.keys.toList()
        val n = ids.size
        val labels = IntArray(n) { 0 }
        var clusterId = 0

        for (i in 0 until n) {
            if (labels[i] != 0) continue

            val neighbors = findNeighborIndices(embeddings, ids, i, eps)
            if (neighbors.size < minPts) {
                labels[i] = -1
                continue
            }

            clusterId++
            labels[i] = clusterId
            val seedSet = neighbors.toMutableList()
            seedSet.remove(i)

            var idx = 0
            while (idx < seedSet.size) {
                val q = seedSet[idx]
                if (labels[q] == -1) labels[q] = clusterId
                if (labels[q] == 0) {
                    labels[q] = clusterId
                    val qn = findNeighborIndices(embeddings, ids, q, eps)
                    if (qn.size >= minPts) {
                        for (ni in qn) {
                            if (ni !in seedSet) seedSet.add(ni)
                        }
                    }
                }
                idx++
            }
        }

        val result = mutableMapOf<Int, MutableList<Long>>()
        for (i in 0 until n) {
            val l = labels[i]
            result.getOrPut(l) { mutableListOf() }.add(ids[i])
        }
        result.remove(-1)?.let { result[-1] = it }
        return result
    }

    private fun findNeighborIndices(
        embeddings: Map<Long, FloatArray>,
        ids: List<Long>,
        centerIdx: Int,
        eps: Float
    ): List<Int> {
        val centerEmb = embeddings[ids[centerIdx]] ?: return listOf(centerIdx)
        val neighbors = mutableListOf(centerIdx)
        for (i in ids.indices) {
            if (i == centerIdx) continue
            val other = embeddings[ids[i]] ?: continue
            if (cosineDistance(centerEmb, other) <= eps) {
                neighbors.add(i)
            }
        }
        return neighbors
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
        val similarity = dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
        return (1f - similarity).coerceAtLeast(0f)
    }

    /**
     * 安全裁剪人脸区域（扩大 20% 边界）
     */
    private fun safeCropFace(bitmap: Bitmap, rect: android.graphics.Rect): Bitmap? {
        val marginW = (rect.width() * 0.2f).toInt().coerceAtLeast(10)
        val marginH = (rect.height() * 0.2f).toInt().coerceAtLeast(10)

        val x = (rect.left - marginW).coerceIn(0, bitmap.width)
        val y = (rect.top - marginH).coerceIn(0, bitmap.height)
        val w = (rect.width() + marginW * 2).coerceAtMost(bitmap.width - x)
        val h = (rect.height() + marginH * 2).coerceAtMost(bitmap.height - y)

        if (w <= 0 || h <= 0) return null

        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
        return Bitmap.createScaledBitmap(cropped, FACE_INPUT_SIZE, FACE_INPUT_SIZE, true).also {
            if (it !== cropped) cropped.recycle()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 4
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load bitmap: $uri", e)
            null
        }
    }
}
