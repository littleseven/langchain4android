package com.picme.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.picme.agent.core.api.context.MediaType

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
    val source: String? = null
)
