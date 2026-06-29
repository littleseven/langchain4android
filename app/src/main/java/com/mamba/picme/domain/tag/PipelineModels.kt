package com.mamba.picme.domain.tag

import android.graphics.RectF

/**
 * Stage 1 产出：人脸 ROI 检测结果（轻量版，无关键点）
 */
data class Stage1Result(
    val hasFace: Boolean,
    val faceCount: Int = 0,
    val roiRects: List<RectF> = emptyList()
) {
    val isSelfie: Boolean get() = faceCount == 1

    /**
     * 合影判定策略：
     * - 有效人脸数 >= 2 即识别为合影
     * - 有效人脸定义：已通过 detectFacesOnly 过滤掉面积 < 3% 图片总面积的小脸/误检
     *
     * 此逻辑与 FaceDetectorManager.detectFacesOnly 中的过滤策略一致。
     */
    val isGroupPhoto: Boolean get() = faceCount >= 2

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Stage1Result
        return hasFace == other.hasFace &&
                faceCount == other.faceCount &&
                roiRects == other.roiRects
    }

    override fun hashCode(): Int {
        var result = hasFace.hashCode()
        result = 31 * result + faceCount
        result = 31 * result + roiRects.hashCode()
        return result
    }
}

/**
 * Stage 2 产出：每张人脸的嵌入结果
 */
data class FaceEmbeddingOutput(
    val mediaId: Long,
    val embedding: FloatArray,
    val personId: Long?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceEmbeddingOutput
        return mediaId == other.mediaId &&
                embedding.contentEquals(other.embedding) &&
                personId == other.personId
    }

    override fun hashCode(): Int {
        var result = mediaId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (personId?.hashCode() ?: 0)
        return result
    }
}

/**
 * Stage 2 聚类结果：所有检测到的人脸及其归属
 */
data class Stage2Result(
    val faceEmbeddings: List<FaceEmbeddingOutput>,
    val personIds: List<Long>
)

/**
 * Stage 3 Qwen 输出的原始标签（反序列化用）
 */
data class QwenTags(
    val scene: String = "",
    val activity: String = "",
    val objects: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String = ""
)

/**
 * 规范化后的标签（后处理后写入数据库）
 */
data class QwenTagsNormalized(
    val scene: String,
    val activity: String,
    val objects: List<String>,
    val tags: List<String>,
    val summary: String,
    val nonStandard: List<String> = emptyList()
)

/**
 * 最终写入 MediaAsset.labels 的 JSON 结构
 */
data class UnifiedTagResult(
    val face: FaceTagInfo = FaceTagInfo(),
    val scene: String = "",
    val activity: String = "",
    val objects: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val qwenSummary: String = ""
)

data class FaceTagInfo(
    val count: Int = 0,
    val selfie: Boolean = false,
    val groupPhoto: Boolean = false,
    val personIds: List<Long> = emptyList()
)

/**
 * 管道处理阶段
 */
enum class PipelineStage {
    /** Pass 1: 人脸 ROI + 人脸 Embedding + MobileCLIP 语义编码（已内联合并） */
    FACE_ROI,
    /** Pass 2: 全局 DBSCAN 聚类 */
    FACE_CLUSTER,
    /** Pass 3: Qwen 图像理解标签生成 */
    QWEN_TAGGING,
    /**
     * MobileCLIP 语义编码（保留枚举值以兼容历史任务/单独重编码场景）。
     * 注意：常规扫描已将该阶段内联到 [FACE_ROI]。
     */
    MOBILE_CLIP,
    COMPLETE
}

/**
 * 管道处理阶段（3-Pass 混合模型细化阶段名）
 */
enum class PassStage {
    /** Pass 1: 人脸检测 + 人脸 Embedding + MobileCLIP 语义编码（已内联合并） */
    FACE_DETECTION,
    /** Pass 2: 全局 DBSCAN 聚类 */
    DBSCAN_CLUSTERING,
    /** Pass 3: Qwen 图像理解标签生成 */
    QWEN_TAGGING,
    /**
     * MobileCLIP 语义编码（保留枚举值以兼容历史任务/单独重编码场景）。
     * 注意：常规扫描已将该阶段内联到 [FACE_DETECTION]。
     */
    MOBILE_CLIP_ENCODING,
    COMPLETE
}

/**
 * 扫描进度
 */
data class TagScanProgress(
    val processed: Int,
    val total: Int,
    val currentStage: PipelineStage = PipelineStage.FACE_ROI,
    val currentItem: Int = 0
)

/**
 * 3-Pass 混合模型扫描进度
 */
data class HybridScanProgress(
    val pass: PassStage = PassStage.FACE_DETECTION,
    val processed: Int = 0,
    val total: Int = 0,
    val currentStage: PipelineStage = PipelineStage.FACE_ROI
)

/**
 * Stage 1 结果持久化 JSON 的数据结构
 */
data class FaceRoiPersist(
    val hasFace: Boolean,
    val faceCount: Int,
    val isSelfie: Boolean,
    val isGroupPhoto: Boolean
)

/**
 * [Pass 1] 单张照片的人脸检测 + Embedding 提取结果
 */
data class Stage1WithEmbeddingsResult(
    /** faceRoi JSON（null = 解码失败） */
    val faceRoiJson: String?,
    /** 每张人脸的 512 维 embedding */
    val embeddings: List<FloatArray>
)

/**
 * 人脸 Embedding 及其关联信息（用于 Pass 1→Pass 2 桥接）
 */
data class FaceEmbeddingBatch(
    val mediaId: Long,
    val faceIdx: Int,
    val embedding: FloatArray
)