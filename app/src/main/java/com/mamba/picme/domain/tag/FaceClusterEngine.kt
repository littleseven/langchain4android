package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.PersonEntity
import kotlin.math.sqrt

/**
 * 人脸聚类引擎
 *
 * 负责 MobileFaceNet 特征提取（Stage 2a/2b）和增量化余弦距离聚类（Stage 2c）。
 *
 * ## 实现状态
 * - **MobileFaceNet 特征提取**：当前为占位实现（返回随机特征向量），
 *   实际模型接入需通过 JNI 桥接到 MNN 推理引擎。
 * - **聚类算法**：增量式余弦距离匹配已实现。
 *
 * @param context Android Context（用于 Room 数据库访问）
 */
class FaceClusterEngine(private val context: Context) {

    companion object {
        private const val TAG = "FaceClusterEngine"

        /** MobileFaceNet 标准输入尺寸 */
        const val FACE_INPUT_SIZE = 112

        /** 特征向量维度 */
        const val EMBEDDING_DIM = 512

        /** 余弦相似度阈值：高于此值归入已有簇 */
        const val COSINE_THRESHOLD = 0.6f

        /** 增量积累达到此数量后触发全量 DBSCAN 重聚 */
        const val RE_CLUSTER_THRESHOLD = 100

        /** 未分配人脸的 personId 标记 */
        const val UNASSIGNED_ID: Long = -1
    }

    private val personDao = AppDatabase.getDatabase(context).personDao()

    /**
     * 提取人脸特征向量
     *
     * ## 当前实现
     * 返回零向量占位。实际 MobileFaceNet 接入后需替换为：
     * 1. 用 landmarks106 做仿射对齐 → 112×112 标准化人脸图
     * 2. MobileFaceNet 推理 → 512 维特征向量
     *
     * @param bitmap 原始图片
     * @param roi 人脸 ROI 区域（像素坐标）
     * @param landmarks106 Stage 1 输出的 106 点归一化坐标
     * @return 512 维特征向量
     */
    suspend fun extractFeature(
        bitmap: Bitmap,
        roi: RectF,
        landmarks106: FloatArray
    ): FloatArray {
        // TODO: Phase 2 - 接入 MobileFaceNet 模型
        // 1. 用 landmarks106 做仿射变换对齐人脸
        // 2. 裁剪并缩放到 112×112
        // 3. MobileFaceNet MNN 推理 → 512 维输出

        Log.d(TAG, "extractFeature: placeholder, roi=$roi, landmarkCount=${landmarks106.size}")
        return FloatArray(EMBEDDING_DIM) { 0f }
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
