package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 人物实体 —— 人脸聚类后的人物去重表
 *
 * 每个人物对应一个聚类簇，通过 [face_embeddings] 表关联到具体的媒体文件。
 */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true)
    val personId: Long = 0,
    val name: String? = null,
    val coverMediaId: Long? = null,
    val faceCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
