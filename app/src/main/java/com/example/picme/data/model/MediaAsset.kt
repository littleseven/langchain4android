package com.example.picme.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MediaType {
    PHOTO, VIDEO
}

@Entity(tableName = "media_assets")
data class MediaAsset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val type: MediaType,
    val captureDate: Long,
    val fileName: String,
    val duration: Long? = null // For videos
)
