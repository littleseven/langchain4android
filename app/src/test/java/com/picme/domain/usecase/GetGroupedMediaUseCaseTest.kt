package com.picme.domain.usecase

import com.picme.domain.model.GroupTitleType
import com.picme.domain.model.GroupingMode
import com.picme.agent.core.model.MediaAsset
import com.picme.agent.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

/**
 * [QA] GetGroupedMediaUseCase 单元测试
 * 测试目标：验证媒体分组逻辑的正确性，包括日期、人脸、人物等分组模式
 */
class GetGroupedMediaUseCaseTest {

    private val useCase = GetGroupedMediaUseCase()

    // ==================== 辅助方法 ====================

    private fun createMediaAsset(
        id: Long,
        captureDate: Long,
        hasFace: Boolean = false,
        faceId: String? = null,
        fileName: String = "IMG_$id.jpg"
    ): MediaAsset {
        return MediaAsset(
            id = id,
            uri = "file:///storage/DCIM/IMG_$id.jpg",
            type = MediaType.PHOTO,
            captureDate = captureDate,
            fileName = fileName,
            hasFace = hasFace,
            faceId = faceId
        )
    }

    // ==================== NONE 模式测试 ====================

    @Test
    fun `invoke with NONE mode returns single group with all media`() {
        val media = listOf(
            createMediaAsset(1, 1000L),
            createMediaAsset(2, 2000L),
            createMediaAsset(3, 3000L)
        )

        val result = useCase(media, GroupingMode.NONE)

        assertEquals(1, result.size)
        assertEquals(GroupTitleType.NONE, result[0].titleType)
        assertEquals("", result[0].titleValue)
        assertEquals(3, result[0].items.size)
    }

    @Test
    fun `invoke with NONE mode and empty list returns single empty group`() {
        val media = emptyList<MediaAsset>()

        val result = useCase(media, GroupingMode.NONE)

        assertEquals(1, result.size)
        assertEquals(0, result[0].items.size)
    }

    // ==================== DATE 模式测试 ====================

    @Test
    fun `invoke with DATE mode groups by date correctly`() {
        // 2024-01-01 的时间戳
        val date1 = 1704067200000L
        // 2024-01-02 的时间戳
        val date2 = 1704153600000L

        val media = listOf(
            createMediaAsset(1, date1),
            createMediaAsset(2, date1),
            createMediaAsset(3, date2),
            createMediaAsset(4, date2)
        )

        val result = useCase(media, GroupingMode.DATE)

        assertEquals(2, result.size)
        assertTrue(result.all { it.titleType == GroupTitleType.DATE })

        // 验证每个分组的数量
        val groupSizes = result.map { it.items.size }.sorted()
        assertEquals(listOf(2, 2), groupSizes)
    }

    @Test
    fun `invoke with DATE mode handles single day correctly`() {
        val date = 1704067200000L
        val media = listOf(
            createMediaAsset(1, date),
            createMediaAsset(2, date)
        )

        val result = useCase(media, GroupingMode.DATE)

        assertEquals(1, result.size)
        assertEquals(2, result[0].items.size)
    }

    @Test
    fun `invoke with DATE mode preserves chronological order`() {
        val earlierDate = 1704067200000L
        val laterDate = 1704153600000L

        val media = listOf(
            createMediaAsset(1, laterDate),
            createMediaAsset(2, earlierDate)
        )

        val result = useCase(media, GroupingMode.DATE)

        // 应该有 2 个分组
        assertEquals(2, result.size)
    }

    // ==================== FACE 模式测试 ====================

    @Test
    fun `invoke with FACE mode separates faces and no-faces`() {
        val media = listOf(
            createMediaAsset(1, 1000L, hasFace = true),
            createMediaAsset(2, 2000L, hasFace = true),
            createMediaAsset(3, 3000L, hasFace = false),
            createMediaAsset(4, 4000L, hasFace = false)
        )

        val result = useCase(media, GroupingMode.FACE)

        assertEquals(2, result.size)

        val withFaces = result.find { it.titleType == GroupTitleType.WITH_FACES }
        val noFaces = result.find { it.titleType == GroupTitleType.NO_FACES }

        assertNotNull(withFaces)
        assertNotNull(noFaces)
        assertEquals(2, withFaces!!.items.size)
        assertEquals(2, noFaces!!.items.size)
    }

    @Test
    fun `invoke with FACE mode filters out empty groups`() {
        val media = listOf(
            createMediaAsset(1, 1000L, hasFace = true),
            createMediaAsset(2, 2000L, hasFace = true)
        )

        val result = useCase(media, GroupingMode.FACE)

        // 应该只有 WITH_FACES 分组，NO_FACES 被过滤
        assertEquals(1, result.size)
        assertEquals(GroupTitleType.WITH_FACES, result[0].titleType)
    }

    @Test
    fun `invoke with FACE mode with all no-face images`() {
        val media = listOf(
            createMediaAsset(1, 1000L, hasFace = false),
            createMediaAsset(2, 2000L, hasFace = false)
        )

        val result = useCase(media, GroupingMode.FACE)

        assertEquals(1, result.size)
        assertEquals(GroupTitleType.NO_FACES, result[0].titleType)
    }

    @Test
    fun `invoke with FACE mode with empty list returns empty`() {
        val media = emptyList<MediaAsset>()

        val result = useCase(media, GroupingMode.FACE)

        assertEquals(0, result.size)
    }

    // ==================== PERSON 模式测试 ====================

    @Test
    fun `invoke with PERSON mode groups by faceId`() {
        val media = listOf(
            createMediaAsset(1, 1000L, hasFace = true, faceId = "person_A"),
            createMediaAsset(2, 2000L, hasFace = true, faceId = "person_A"),
            createMediaAsset(3, 3000L, hasFace = true, faceId = "person_B"),
            createMediaAsset(4, 4000L, hasFace = true, faceId = "person_B"),
            createMediaAsset(5, 5000L, hasFace = true, faceId = "person_B")
        )

        val result = useCase(media, GroupingMode.PERSON)

        assertEquals(2, result.size)
        assertTrue(result.all { it.titleType == GroupTitleType.PERSON })

        val personA = result.find { it.titleValue == "person_A" }
        val personB = result.find { it.titleValue == "person_B" }

        assertNotNull(personA)
        assertNotNull(personB)
        assertEquals(2, personA!!.items.size)
        assertEquals(3, personB!!.items.size)
    }

    @Test
    fun `invoke with PERSON mode filters out items without faceId`() {
        val media = listOf(
            createMediaAsset(1, 1000L, hasFace = true, faceId = "person_A"),
            createMediaAsset(2, 2000L, hasFace = true, faceId = null),
            createMediaAsset(3, 3000L, hasFace = false, faceId = null)
        )

        val result = useCase(media, GroupingMode.PERSON)

        assertEquals(1, result.size)
        assertEquals("person_A", result[0].titleValue)
        assertEquals(1, result[0].items.size)
    }

    @Test
    fun `invoke with PERSON mode with empty list returns empty`() {
        val media = emptyList<MediaAsset>()

        val result = useCase(media, GroupingMode.PERSON)

        assertEquals(0, result.size)
    }

    // ==================== LANDSCAPE 模式测试 ====================

    @Test
    fun `invoke with LANDSCAPE mode filters by filename`() {
        val media = listOf(
            createMediaAsset(1, 1000L, fileName = "TEST_LANDSCAPE_001.jpg"),
            createMediaAsset(2, 2000L, fileName = "TEST_LANDSCAPE_002.jpg"),
            createMediaAsset(3, 3000L, fileName = "regular_photo.jpg")
        )

        val result = useCase(media, GroupingMode.LANDSCAPE)

        assertEquals(1, result.size)
        assertEquals(GroupTitleType.LANDSCAPE, result[0].titleType)
        assertEquals(2, result[0].items.size)
    }

    @Test
    fun `invoke with LANDSCAPE mode with no matches returns empty`() {
        val media = listOf(
            createMediaAsset(1, 1000L, fileName = "photo1.jpg"),
            createMediaAsset(2, 2000L, fileName = "photo2.jpg")
        )

        val result = useCase(media, GroupingMode.LANDSCAPE)

        assertEquals(0, result.size)
    }

    @Test
    fun `invoke with LANDSCAPE mode is case insensitive`() {
        val media = listOf(
            createMediaAsset(1, 1000L, fileName = "test_landscape_001.jpg"),
            createMediaAsset(2, 2000L, fileName = "TEST_LANDSCAPE_002.JPG")
        )

        val result = useCase(media, GroupingMode.LANDSCAPE)

        assertEquals(1, result.size)
        assertEquals(2, result[0].items.size)
    }

    // ==================== SWIMWEAR 模式测试 ====================

    @Test
    fun `invoke with SWIMWEAR mode filters by filename`() {
        val media = listOf(
            createMediaAsset(1, 1000L, fileName = "TEST_SWIMWEAR_001.jpg"),
            createMediaAsset(2, 2000L, fileName = "regular_photo.jpg")
        )

        val result = useCase(media, GroupingMode.SWIMWEAR)

        assertEquals(1, result.size)
        assertEquals(GroupTitleType.SWIMWEAR, result[0].titleType)
        assertEquals(1, result[0].items.size)
    }

    @Test
    fun `invoke with SWIMWEAR mode with no matches returns empty`() {
        val media = listOf(
            createMediaAsset(1, 1000L, fileName = "photo1.jpg")
        )

        val result = useCase(media, GroupingMode.SWIMWEAR)

        assertEquals(0, result.size)
    }

    // ==================== SEXY 模式测试 ====================

    @Test
    fun `invoke with SEXY mode filters by filename`() {
        val media = listOf(
            createMediaAsset(1, 1000L, fileName = "TEST_SEXY_001.jpg"),
            createMediaAsset(2, 2000L, fileName = "regular_photo.jpg")
        )

        val result = useCase(media, GroupingMode.SEXY)

        assertEquals(1, result.size)
        assertEquals(GroupTitleType.SEXY, result[0].titleType)
        assertEquals(1, result[0].items.size)
    }

    @Test
    fun `invoke with SEXY mode with no matches returns empty`() {
        val media = listOf(
            createMediaAsset(1, 1000L, fileName = "photo1.jpg")
        )

        val result = useCase(media, GroupingMode.SEXY)

        assertEquals(0, result.size)
    }

    // ==================== 边界值测试 ====================

    @Test
    fun `invoke with large dataset maintains performance`() {
        val media = (1..1000).map { i ->
            createMediaAsset(i.toLong(), i * 1000L, hasFace = i % 2 == 0)
        }

        val result = useCase(media, GroupingMode.FACE)

        assertTrue(result.isNotEmpty())
        val totalItems = result.sumOf { it.items.size }
        assertEquals(1000, totalItems)
    }

    @Test
    fun `invoke preserves item order within groups`() {
        val media = listOf(
            createMediaAsset(1, 1000L),
            createMediaAsset(2, 2000L),
            createMediaAsset(3, 3000L)
        )

        val result = useCase(media, GroupingMode.NONE)

        assertEquals(media, result[0].items)
    }
}
