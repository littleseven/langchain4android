package com.picme.domain.model

import com.picme.agent.core.api.context.MediaAsset

enum class GroupingMode {
    NONE,
    DATE,
    FACE,
    PERSON,
    LANDSCAPE,
    SWIMWEAR,
    SEXY
}

enum class GroupTitleType {
    NONE,
    DATE,
    WITH_FACES,
    NO_FACES,
    PERSON,
    LANDSCAPE,
    SWIMWEAR,
    SEXY
}

data class GroupedMedia(
    val titleType: GroupTitleType,
    val titleValue: String,
    val items: List<MediaAsset>
)

