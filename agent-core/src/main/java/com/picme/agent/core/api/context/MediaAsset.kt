package com.picme.agent.core.api.context

/**
 * 媒体资源基础模型
 */
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

enum class MediaType {
    PHOTO,
    VIDEO,
    DOCUMENT
}
