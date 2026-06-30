package com.mamba.picme.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuerySegmenterTest {

    @Test
    fun `segment splits last year march indoor child photo`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("去年3月在室内小孩的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.TIME, "去年3月"),
                Segment(SegmentType.LOCATION, "室内"),
                Segment(SegmentType.PERSON, "小孩"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits beijing park child`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("北京公园里的小孩")

        assertEquals(
            listOf(
                Segment(SegmentType.LOCATION, "北京"),
                Segment(SegmentType.LOCATION, "公园"),
                Segment(SegmentType.PERSON, "小孩")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits cat photo`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("猫的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.OBJECT, "猫"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
    }

    @Test
    fun `segment returns empty for stop words only`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("的照片")
        assertTrue(result.isEmpty)
    }

    @Test
    fun `toFilters converts explicit and content segments`() {
        val segmenter = QuerySegmenter()
        val segmented = SegmentedQuery(
            original = "去年3月在室内小孩",
            segments = listOf(
                Segment(SegmentType.TIME, "去年3月"),
                Segment(SegmentType.LOCATION, "室内"),
                Segment(SegmentType.PERSON, "小孩")
            )
        )

        val (explicit, content) = segmenter.toFilters(segmented)

        assertTrue(explicit.timeRange != null)
        assertEquals(listOf("室内"), explicit.locationKeywords)
        assertEquals(true, explicit.hasFaces)
        assertEquals(listOf("小孩"), explicit.personKeywords)
        assertEquals(listOf("小孩"), content.keywords)
    }

    @Test
    fun `segment returns empty for empty or whitespace input`() {
        val segmenter = QuerySegmenter()

        val emptyResult = segmenter.segment("")
        assertTrue(emptyResult.isEmpty)
        assertEquals(emptyList<Segment>(), emptyResult.segments)

        val whitespaceResult = segmenter.segment("   ")
        assertTrue(whitespaceResult.isEmpty)
        assertEquals(emptyList<Segment>(), whitespaceResult.segments)
    }

    @Test
    fun `segment splits sea sunset into scene segments`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("海边日落")

        assertEquals(
            listOf(
                Segment(SegmentType.SCENE, "海边"),
                Segment(SegmentType.SCENE, "日落")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits last week invoice screenshot`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("上周发票截图")

        assertEquals(
            listOf(
                Segment(SegmentType.TIME, "上周"),
                Segment(SegmentType.OCR, "发票"),
                Segment(SegmentType.OCR, "截图")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits dinner food with unknown filler`() {
        val segmenter = QuerySegmenter()
        val result = segmenter.segment("聚餐时拍的食物")

        assertEquals(
            listOf(
                Segment(SegmentType.ACTIVITY, "聚餐"),
                Segment(SegmentType.UNKNOWN, "时"),
                Segment(SegmentType.OBJECT, "食物")
            ),
            result.segments
        )
    }

    @Test
    fun `custom vocab injection via constructor works`() {
        val segmenter = QuerySegmenter(
            locationVocab = setOf("自定义地点")
        )
        val result = segmenter.segment("自定义地点")

        assertEquals(
            listOf(Segment(SegmentType.LOCATION, "自定义地点")),
            result.segments
        )
    }

    @Test
    fun `toFilters hasFaces is null when no person segment`() {
        val segmenter = QuerySegmenter()
        val segmented = segmenter.segment("猫的照片")

        val (explicit, _) = segmenter.toFilters(segmented)

        assertNull(explicit.hasFaces)
    }
}
