package com.mamba.picme.domain.search

/**
 * 分段后的查询
 */
data class SegmentedQuery(
    val original: String,
    val segments: List<Segment>
) {
    val explicitSegments: List<Segment>
        get() = segments.filter { it.type.isExplicit() }

    val contentSegments: List<Segment>
        get() = segments.filter { it.type.isContent() }

    val hasExplicit: Boolean
        get() = explicitSegments.isNotEmpty()

    val hasContent: Boolean
        get() = contentSegments.isNotEmpty()

    val isEmpty: Boolean
        get() = segments.isEmpty() || segments.all { it.type == SegmentType.UNKNOWN }
}
