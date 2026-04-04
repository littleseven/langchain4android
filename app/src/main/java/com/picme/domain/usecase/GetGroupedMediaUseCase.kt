package com.picme.domain.usecase

import com.picme.domain.model.GroupTitleType
import com.picme.domain.model.GroupedMedia
import com.picme.domain.model.GroupingMode
import com.picme.domain.model.MediaAsset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GetGroupedMediaUseCase {

    operator fun invoke(media: List<MediaAsset>, mode: GroupingMode): List<GroupedMedia> {
        return when (mode) {
            GroupingMode.NONE -> listOf(
                GroupedMedia(
                    titleType = GroupTitleType.NONE,
                    titleValue = "",
                    items = media
                )
            )

            GroupingMode.DATE -> {
                val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                media.groupBy { mediaItem -> dateFormatter.format(Date(mediaItem.captureDate)) }
                    .map { entry ->
                        GroupedMedia(
                            titleType = GroupTitleType.DATE,
                            titleValue = entry.key,
                            items = entry.value
                        )
                    }
            }

            GroupingMode.FACE -> {
                val withFaces = media.filter { mediaItem -> mediaItem.hasFace }
                val noFaces = media.filter { mediaItem -> !mediaItem.hasFace }

                listOf(
                    GroupedMedia(
                        titleType = GroupTitleType.WITH_FACES,
                        titleValue = "",
                        items = withFaces
                    ),
                    GroupedMedia(
                        titleType = GroupTitleType.NO_FACES,
                        titleValue = "",
                        items = noFaces
                    )
                ).filter { group -> group.items.isNotEmpty() }
            }

            GroupingMode.PERSON -> {
                media.filter { mediaItem -> mediaItem.hasFace && mediaItem.faceId != null }
                    .groupBy { mediaItem -> mediaItem.faceId ?: "" }
                    .map { entry ->
                        GroupedMedia(
                            titleType = GroupTitleType.PERSON,
                            titleValue = entry.key,
                            items = entry.value
                        )
                    }
            }

            GroupingMode.LANDSCAPE -> {
                val landscapes = media.filter { mediaItem ->
                    mediaItem.fileName.contains("TEST_LANDSCAPE", ignoreCase = true)
                }
                if (landscapes.isNotEmpty()) {
                    listOf(
                        GroupedMedia(
                            titleType = GroupTitleType.LANDSCAPE,
                            titleValue = "",
                            items = landscapes
                        )
                    )
                } else {
                    emptyList()
                }
            }

            GroupingMode.SWIMWEAR -> {
                val swimwearItems = media.filter { mediaItem ->
                    mediaItem.fileName.contains("TEST_SWIMWEAR", ignoreCase = true)
                }
                if (swimwearItems.isNotEmpty()) {
                    listOf(
                        GroupedMedia(
                            titleType = GroupTitleType.SWIMWEAR,
                            titleValue = "",
                            items = swimwearItems
                        )
                    )
                } else {
                    emptyList()
                }
            }

            GroupingMode.SEXY -> {
                val sexyItems = media.filter { mediaItem ->
                    mediaItem.fileName.contains("TEST_SEXY", ignoreCase = true)
                }
                if (sexyItems.isNotEmpty()) {
                    listOf(
                        GroupedMedia(
                            titleType = GroupTitleType.SEXY,
                            titleValue = "",
                            items = sexyItems
                        )
                    )
                } else {
                    emptyList()
                }
            }
        }
    }
}
