package com.picme.domain.usecase

import com.picme.domain.model.MediaAsset
import com.picme.features.gallery.GroupingMode
import com.picme.features.gallery.MediaGroup
import java.text.SimpleDateFormat
import java.util.*

class GetGroupedMediaUseCase {
    operator fun invoke(media: List<MediaAsset>, mode: GroupingMode): List<MediaGroup> {
        return when (mode) {
            GroupingMode.NONE -> listOf(MediaGroup("", media))
            GroupingMode.DATE -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                media.groupBy { sdf.format(Date(it.captureDate)) }
                    .map { MediaGroup(it.key, it.value) }
            }
            GroupingMode.FACE -> {
                val hasFace = media.filter { it.hasFace }
                val noFace = media.filter { !it.hasFace }
                listOf(
                    MediaGroup("With Faces", hasFace),
                    MediaGroup("No Faces", noFace)
                ).filter { it.items.isNotEmpty() }
            }
            GroupingMode.PERSON -> {
                media.groupBy { it.faceId ?: "Unknown" }
                    .map { MediaGroup(if (it.key == "Unknown") "Unknown" else "Person Group ${it.key}", it.value) }
            }
        }
    }
}
