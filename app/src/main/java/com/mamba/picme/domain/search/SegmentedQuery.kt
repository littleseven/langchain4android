package com.mamba.picme.domain.search

/**
 * 分段后的查询
 */
data class SegmentedQuery(
    val original: String,
    val segments: List<Segment>
) {
    val explicitSegments: List<Segment> =
        segments.filter { it.type.isExplicit }

    val contentSegments: List<Segment> =
        segments.filter { it.type.isContent }

    val hasExplicit: Boolean =
        explicitSegments.isNotEmpty()

    val hasContent: Boolean =
        contentSegments.isNotEmpty()

    val isEmpty: Boolean =
        segments.isEmpty() || segments.all { it.type == SegmentType.UNKNOWN }
}
