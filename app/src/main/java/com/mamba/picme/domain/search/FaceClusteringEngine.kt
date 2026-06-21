package com.mamba.picme.domain.search

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 人脸几何特征向量
 *
 * 所有距离值归一化到 [0,1]，对尺度不敏感。
 */
data class FaceFeatureVector(
    /** 眼距 / 脸宽（scaled: 0-1） */
    val eyeDistanceRatio: Float,
    /** 鼻子到嘴距离 / 脸高（scaled: 0-1） */
    val noseToMouthRatio: Float,
    /** 脸宽高比 */
    val faceAspectRatio: Float,
    /** 左眼相对脸中心位置 x [0,1] */
    val leftEyeX: Float,
    /** 左眼相对脸中心位置 y [0,1] */
    val leftEyeY: Float,
    /** 右眼相对脸中心位置 x [0,1] */
    val rightEyeX: Float,
    /** 右眼相对脸中心位置 y [0,1] */
    val rightEyeY: Float,
    /** 嘴相对脸中心位置 y [0,1] */
    val mouthY: Float,
    /** 头部左右转动偏角 */
    val headYaw: Float,
    /** 头部平面旋转 */
    val headRoll: Float
) {
    /**
     * 计算与另一特征的欧氏距离
     * 各维度加权：位置特征权重高，角度特征权重低
     */
    fun distanceTo(other: FaceFeatureVector): Float {
        val wEyeDist = 3f
        val wNoseMouth = 2f
        val wAspect = 1f
        val wLeftEye = 2f
        val wRightEye = 2f
        val wMouth = 2f
        val wYaw = 0.5f
        val wRoll = 0.5f

        val sum = wEyeDist * (eyeDistanceRatio - other.eyeDistanceRatio).pow(2) +
                wNoseMouth * (noseToMouthRatio - other.noseToMouthRatio).pow(2) +
                wAspect * (faceAspectRatio - other.faceAspectRatio).pow(2) +
                wLeftEye * ((leftEyeX - other.leftEyeX).pow(2) + (leftEyeY - other.leftEyeY).pow(2)) +
                wRightEye * ((rightEyeX - other.rightEyeX).pow(2) + (rightEyeY - other.rightEyeY).pow(2)) +
                wMouth * (mouthY - other.mouthY).pow(2) +
                wYaw * (headYaw - other.headYaw).pow(2) +
                wRoll * (headRoll - other.headRoll).pow(2)

        return sqrt(sum / (wEyeDist + wNoseMouth + wAspect + wLeftEye * 2 + wRightEye * 2 + wMouth + wYaw + wRoll))
    }
}

/**
 * DNA 特征 + DBSCAN 人脸聚类引擎
 *
 * Phase 1：基于面部几何特征（眼距、鼻嘴距、脸宽高比、五官位置）的轻量聚类。
 * 使用 DBSCAN 算法：无需预设簇数，能自动识别噪声点。
 *
 * Phase 2（可选）：引入 MobileFaceNet TFLite embedding 提升精度。
 */
object FaceClusteringEngine {

    private data class IndexedFeature(
        val mediaId: Long,
        val feature: FaceFeatureVector
    )

    /**
     * DBSCAN 聚类
     *
     * @param features 媒体ID → 特征向量
     * @param eps 邻域半径（越小分簇越细，推荐 0.2-0.35）
     * @param minPts 核心点最少邻居数
     * @return Map<clusterId, List<mediaId>>，-1 表示噪声
     */
    fun cluster(
        features: Map<Long, FaceFeatureVector>,
        eps: Float = 0.28f,
        minPts: Int = 1
    ): Map<Int, List<Long>> {
        if (features.isEmpty()) return emptyMap()

        val indexed = features.map { IndexedFeature(it.key, it.value) }
        val n = indexed.size
        val labels = IntArray(n) { 0 } // 0=未分类, -1=噪声, 1+=簇ID
        var clusterId = 0

        for (i in 0 until n) {
            if (labels[i] != 0) continue // 已分类

            val neighbors = findNeighbors(indexed, i, eps)
            if (neighbors.size < minPts) {
                labels[i] = -1 // 噪声
                continue
            }

            clusterId++
            labels[i] = clusterId
            val seedSet = neighbors.toMutableList()
            seedSet.remove(i)

            var seedIdx = 0
            while (seedIdx < seedSet.size) {
                val q = seedSet[seedIdx]
                if (labels[q] == -1) {
                    labels[q] = clusterId // 噪声点属于该簇
                }
                if (labels[q] != 0) {
                    seedIdx++
                    continue // 已分类
                }
                labels[q] = clusterId
                val qNeighbors = findNeighbors(indexed, q, eps)
                if (qNeighbors.size >= minPts) {
                    for (nIdx in qNeighbors) {
                        if (nIdx !in seedSet && labels[nIdx] != clusterId) {
                            seedSet.add(nIdx)
                        }
                    }
                }
                seedIdx++
            }
        }

        // 组装结果
        val result = mutableMapOf<Int, MutableList<Long>>()
        for (i in 0 until n) {
            val label = labels[i]
            result.getOrPut(label) { mutableListOf() }.add(indexed[i].mediaId)
        }

        // 噪声点标记为 -1
        val noise = result.remove(-1) ?: emptyList()
        if (noise.isNotEmpty()) {
            result[-1] = noise.toMutableList()
        }

        return result
    }

    /**
     * 查找指定点的近邻索引
     */
    private fun findNeighbors(
        indexed: List<IndexedFeature>,
        centerIdx: Int,
        eps: Float
    ): List<Int> {
        val center = indexed[centerIdx].feature
        val neighbors = mutableListOf<Int>()
        for (i in indexed.indices) {
            if (i == centerIdx) {
                neighbors.add(i) // 包含自身
                continue
            }
            if (center.distanceTo(indexed[i].feature) <= eps) {
                neighbors.add(i)
            }
        }
        return neighbors
    }
}
