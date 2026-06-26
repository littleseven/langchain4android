package com.mamba.picme.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mamba.picme.agent.core.model.context.MediaType

@Entity(tableName = "media_assets")
data class MediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val type: MediaType,
    val captureDate: Long,
    val fileName: String,
    val duration: Long? = null,
    val hasFace: Boolean = false,
    val faceId: String? = null,
    val source: String? = null,
    // 元数据索引字段（Phase 1 自然语言搜索）
    val labels: String? = null,           // JSON 数组：["猫","户外","食物"]
    val ocrText: String? = null,          // OCR 提取的文字
    val latitude: Double? = null,         // GPS 纬度
    val longitude: Double? = null,        // GPS 经度
    val locationName: String? = null,     // 逆地理编码地名
    val indexedAt: Long? = null,          // 索引完成时间戳（null=未索引）
    // 人脸 ROI 检测结果 JSON（Stage 1 产出持久化，用于 Pass 1→Pass 3 断点续扫）
    val faceRoiResult: String? = null,

    // 最近一次 TAG 扫描成功时间戳（用于增量去重与避重）
    val lastTagScanAt: Long? = null,

    // 最近一次成功扫描覆盖的 Pass 阶段 JSON，如 {"1":ts,"2":ts,"3":ts}
    val lastTagScanPasses: String? = null
)
