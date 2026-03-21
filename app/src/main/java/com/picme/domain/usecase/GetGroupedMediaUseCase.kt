package com.picme.domain.usecase

import android.content.Context
import com.picme.R
import com.picme.domain.model.MediaAsset
import com.picme.features.gallery.GroupingMode
import com.picme.features.gallery.MediaGroup
import java.text.SimpleDateFormat
import java.util.*

class GetGroupedMediaUseCase(private val context: Context) {
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
                    MediaGroup(context.getString(R.string.with_faces), hasFace),
                    MediaGroup(context.getString(R.string.no_faces), noFace)
                ).filter { it.items.isNotEmpty() }
            }
            GroupingMode.PERSON -> {
                media.filter { it.hasFace && it.faceId != null }
                    .groupBy { it.faceId!! }
                    .map { (faceId, items) ->
                        MediaGroup(context.getString(R.string.person_group, faceId), items)
                    }
            }
            GroupingMode.LANDSCAPE -> {
                // Landscape grouping is currently a placeholder as we don't have scene detection yet
                // For now, it shows nothing as per "不满足聚类的不展示" rule
                emptyList()
            }
        }
    }
}
