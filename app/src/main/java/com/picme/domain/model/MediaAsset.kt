package com.picme.domain.model

enum class MediaType {
    PHOTO, VIDEO, PORTRAIT, PRO
}

data class MediaAsset(
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
