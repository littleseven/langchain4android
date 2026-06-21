package com.mamba.picme.domain.model

import com.mamba.picme.agent.core.model.context.MediaAsset

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
    SEXY,
    SEARCH
}

data class GroupedMedia(
    val titleType: GroupTitleType,
    val titleValue: String,
    val items: List<MediaAsset>
)

