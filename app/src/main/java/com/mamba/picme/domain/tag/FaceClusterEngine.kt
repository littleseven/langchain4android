package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.data.indexing.MnnEmbeddingExtractor
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.PersonEntity
import java.io.File
import kotlin.math.sqrt

/**
 * 人脸聚类引擎
 *
 * 负责 MobileFaceNet 特征提取（Stage 2a/2b）和增量化余弦距离聚类（Stage 2c）。
 *
 * ## 实现状态 (2026-06-24)
 * - **MobileFaceNet 特征提取**：已集成 [MnnEmbeddingExtractor]，
 *   使用 MNN 加载 w600k_mbf.mnn 模型提取 512 维 embedding。
 *   模型缺失时降级为零向量（聚类不生效）。
 * - **聚类算法**：增量式余弦距离匹配已实现。
 *
 * @param context Android Context（用于 Room 数据库访问和模型目录）
 */
class FaceClusterEngine(private val context: Context) {

    companion object {
        private const val TAG = "FaceClusterEngine"

        /** MobileFaceNet 标准输入尺寸 */
        const val FACE_INPUT_SIZE = 112

        /** 特征向量维度 */
        const val EMBEDDING_DIM = 512

        /** 余弦相似度阈值：高于此值归入已有簇 */
        const val COSINE_THRESHOLD = 0.65f

        /** 增量积累达到此数量后触发全量 DBSCAN 重聚 */
        const val RE_CLUSTER_THRESHOLD = 100

        /** 未分配人脸的 personId 标记 */
        const val UNASSIGNED_ID: Long = -1
    }

    private val personDao = AppDatabase.getDatabase(context).personDao()

    /** MobileFaceNet 嵌入提取器（懒加载，模型缺失时为 null） */
    private val embeddingExtractor: MnnEmbeddingExtractor? by lazy {
        val modelDir = ModelPathConfig.getModelDir(context, "picme-face-embedding-mnn")
        val modelFile = File(modelDir, "w600k_mbf.mnn")
        val extractor = MnnEmbeddingExtractor(modelFile)
        if (extractor.isModelReady && extractor.initialize()) {
            Log.i(TAG, "MobileFaceNet model loaded: ${modelFile.absolutePath}")
            extractor
        } else {
            Log.w(TAG, "MobileFaceNet model NOT found at ${modelFile.absolutePath}, face clustering will NOT work. Download w600k_mbf.mnn to enable.")
            null
        }
    }

    /** 嵌入提取器是否可用 */
    val isEmbeddingAvailable: Boolean
        get() = embeddingExtractor != null

    /**
     * 提取人脸特征向量
     *
     * 从原始图片中裁剪人脸 ROI、缩放到 112×112，
     * 通过 MobileFaceNet MNN 模型提取 512 维 L2 归一化 embedding。
     *
     * 模型缺失时返回零向量（聚类将退化为全量新建簇）。
     *
     * @param bitmap 原始图片
     * @param roi 人脸 ROI 区域（像素坐标）
     * @param landmarks106 Stage 1 输出的 106 点归一化坐标（当前未用于对齐，保留接口兼容性）
     * @return 512 维特征向量（L2 归一化后的真实 embedding，或零向量）
     */
    suspend fun extractFeature(
        bitmap: Bitmap,
        roi: RectF,
        landmarks106: FloatArray
    ): FloatArray {
        val extractor = embeddingExtractor
        if (extractor == null) {
            Log.d(TAG, "extractFeature: no model, returning zero vector. roi=$roi")
            return FloatArray(EMBEDDING_DIM) { 0f }
        }

        return try {
            // 1. 安全裁剪人脸 ROI（带 20% 边距扩展）
            val marginW = (roi.width() * 0.2f).toInt().coerceAtLeast(10)
            val marginH = (roi.height() * 0.2f).toInt().coerceAtLeast(10)
            val cropX = roi.left.toInt().minus(marginW).coerceIn(0, bitmap.width)
            val cropY = roi.top.toInt().minus(marginH).coerceIn(0, bitmap.height)
            val cropW = (roi.width().toInt() + marginW * 2).coerceAtMost(bitmap.width - cropX)
            val cropH = (roi.height().toInt() + marginH * 2).coerceAtMost(bitmap.height - cropY)

            if (cropW <= 0 || cropH <= 0) {
                Log.w(TAG, "extractFeature: invalid crop region, returning zero vector")
                return FloatArray(EMBEDDING_DIM) { 0f }
            }

            val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
            val faceBitmap = Bitmap.createScaledBitmap(cropped, FACE_INPUT_SIZE, FACE_INPUT_SIZE, true)
            if (faceBitmap !== cropped) cropped.recycle()

            // 2. MNN 推理提取 embedding
            val embedding = extractor.extractEmbedding(faceBitmap)
            faceBitmap.recycle()

            if (embedding != null) {
                Log.d(TAG, "extractFeature: extracted ${embedding.size}-dim embedding, norm=${sqrt(embedding.map { it*it }.sum().toDouble())}")
                embedding
            } else {
                Log.w(TAG, "extractFeature: MNN inference returned null, falling back to zero vector")
                FloatArray(EMBEDDING_DIM) { 0f }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractFeature: failed with exception, falling back to zero vector", e)
            FloatArray(EMBEDDING_DIM) { 0f }
        }
    }

    /**
     * 匹配已有聚类簇
     *
     * 算法：计算新特征向量与所有已有簇质心的余弦相似度，
     * 若最高相似度 > COSINE_THRESHOLD，则归入该簇；否则返回 null 表示需要新建簇。
     *
     * @param feature 512 维特征向量
     * @return 匹配到的 personId，null 表示需要新建簇
     */
    suspend fun matchCluster(feature: FloatArray): Long? {
        val persons = personDao.getAllPersons()
        if (persons.isEmpty()) return null

        var bestPersonId: Long? = null
        var bestSimilarity = COSINE_THRESHOLD

        for (person in persons) {
            val centroid = getPersonCentroid(person.personId) ?: continue
            val similarity = cosineSimilarity(feature, centroid)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestPersonId = person.personId
            }
        }

        return bestPersonId
    }

    /**
     * 创建新聚类簇
     *
     * @param feature 512 维特征向量（作为新簇的初始质心）
     * @param mediaId 媒体文件 ID
     * @return 新创建的 personId
     */
    suspend fun createCluster(feature: FloatArray, mediaId: Long): Long {
        val person = PersonEntity(
            faceCount = 1,
            coverMediaId = mediaId
        )
        val personId = personDao.insertPerson(person)
        Log.d(TAG, "Created new cluster: personId=$personId")

        // 写入首个 embedding
        val embeddingEntity = FaceEmbeddingEntity(
            mediaId = mediaId,
            personId = personId,
            embedding = floatArrayToByteArray(feature)
        )
        personDao.insertEmbedding(embeddingEntity)

        return personId
    }

    /**
     * 将新特征归入已有簇
     */
    suspend fun addToCluster(personId: Long, feature: FloatArray, mediaId: Long) {
        // 写入 embedding
        val embeddingEntity = FaceEmbeddingEntity(
            mediaId = mediaId,
            personId = personId,
            embedding = floatArrayToByteArray(feature)
        )
        personDao.insertEmbedding(embeddingEntity)

        // 更新人脸计数
        personDao.incrementFaceCount(personId)
        Log.d(TAG, "Added to cluster: personId=$personId, mediaId=$mediaId")
    }

    /**
     * 合并两个簇（将 personB 的所有 embedding 转移到 personA，删除 personB）
     */
    suspend fun mergeClusters(personA: Long, personB: Long) {
        val embeddingsB = personDao.getEmbeddingsByPerson(personB)
        for (embedding in embeddingsB) {
            personDao.assignEmbedding(embedding.embeddingId, personA)
        }

        val countB = embeddingsB.size
        // 更新 personA faceCount
        repeat(countB) { personDao.incrementFaceCount(personA) }

        // 删除 personB
        personDao.unlinkEmbeddings(personB)
        personDao.deletePerson(personB)

        Log.d(TAG, "Merged clusters: $personB -> $personA, ${countB} embeddings moved")
    }

    /**
     * 获取某个簇的质心特征向量（所有 embedding 的算术平均值）
     */
    private suspend fun getPersonCentroid(personId: Long): FloatArray? {
        val embeddings = personDao.getEmbeddingsByPerson(personId)
        if (embeddings.isEmpty()) return null

        val centroid = FloatArray(EMBEDDING_DIM)
        for (entity in embeddings) {
            val feature = byteArrayToFloatArray(entity.embedding)
            for (i in 0 until EMBEDDING_DIM) {
                centroid[i] += feature[i]
            }
        }
        for (i in 0 until EMBEDDING_DIM) {
            centroid[i] /= embeddings.size.toFloat()
        }
        return centroid
    }

    /**
     * 计算两个向量的余弦相似度 [0, 1]
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA.toDouble()) * sqrt(normB.toDouble())
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

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
}
