package com.picme.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * [QA] MediaAsset 单元测试
 * 测试目标：验证媒体资源数据类的属性和行为
 */
class MediaAssetTest {

    // ==================== 基本属性测试 ====================

    @Test
    fun `MediaAsset creation with all properties`() {
        val asset = MediaAsset(
            id = 123L,
            uri = "file:///storage/DCIM/IMG_001.jpg",
            type = MediaType.PHOTO,
            captureDate = 1234567890L,
            fileName = "IMG_001.jpg",
            duration = null,
            hasFace = false,
            faceId = null,
            source = "camera"
        )

        assertEquals(123L, asset.id)
        assertEquals("file:///storage/DCIM/IMG_001.jpg", asset.uri)
        assertEquals(MediaType.PHOTO, asset.type)
        assertEquals(1234567890L, asset.captureDate)
        assertEquals("IMG_001.jpg", asset.fileName)
        assertNull(asset.duration)
        assertFalse(asset.hasFace)
        assertNull(asset.faceId)
        assertEquals("camera", asset.source)
    }

    @Test
    fun `MediaAsset for video has duration`() {
        val videoAsset = MediaAsset(
            id = 456L,
            uri = "file:///storage/DCIM/VID_001.mp4",
            type = MediaType.VIDEO,
            captureDate = 1234567890L,
            fileName = "VID_001.mp4",
            duration = 60000L, // 1 minute
            hasFace = false,
            faceId = null,
            source = "camera"
        )

        assertEquals(MediaType.VIDEO, videoAsset.type)
        assertEquals(60000L, videoAsset.duration)
    }

    // ==================== MediaType 枚举测试 ====================

    @Test
    fun `MediaType has all expected values`() {
        val types = MediaType.values()

        assertEquals(5, types.size)
        assertTrue(types.contains(MediaType.PHOTO))
        assertTrue(types.contains(MediaType.VIDEO))
        assertTrue(types.contains(MediaType.PORTRAIT))
        assertTrue(types.contains(MediaType.PRO))
        assertTrue(types.contains(MediaType.DOCUMENT))
    }

    @Test
    fun `MediaType PHOTO properties`() {
        val type = MediaType.PHOTO
        assertEquals("PHOTO", type.name)
    }

    @Test
    fun `MediaType VIDEO properties`() {
        val type = MediaType.VIDEO
        assertEquals("VIDEO", type.name)
    }

    @Test
    fun `MediaType PORTRAIT properties`() {
        val type = MediaType.PORTRAIT
        assertEquals("PORTRAIT", type.name)
    }

    @Test
    fun `MediaType PRO properties`() {
        val type = MediaType.PRO
        assertEquals("PRO", type.name)
    }

    @Test
    fun `MediaType DOCUMENT properties`() {
        val type = MediaType.DOCUMENT
        assertEquals("DOCUMENT", type.name)
    }

    // ==================== 数据类特性测试 ====================

    @Test
    fun `MediaAsset equality`() {
        val asset1 = MediaAsset(
            id = 123L,
            uri = "file:///test.jpg",
            type = MediaType.PHOTO,
            captureDate = 1000L,
            fileName = "test.jpg"
        )
        val asset2 = MediaAsset(
            id = 123L,
            uri = "file:///test.jpg",
            type = MediaType.PHOTO,
            captureDate = 1000L,
            fileName = "test.jpg"
        )
        val asset3 = MediaAsset(
            id = 456L,
            uri = "file:///other.jpg",
            type = MediaType.PHOTO,
            captureDate = 1000L,
            fileName = "other.jpg"
        )

        assertEquals(asset1, asset2)
        assertNotEquals(asset1, asset3)
    }

    @Test
    fun `MediaAsset copy functionality`() {
        val original = MediaAsset(
            id = 123L,
            uri = "file:///test.jpg",
            type = MediaType.PHOTO,
            captureDate = 1000L,
            fileName = "test.jpg"
        )

        val copy = original.copy(fileName = "renamed.jpg")

        assertEquals(123L, copy.id) // 未修改的字段保持不变
        assertEquals("renamed.jpg", copy.fileName) // 修改的字段已更新
        assertEquals("test.jpg", original.fileName) // 原始对象不变
    }

    @Test
    fun `MediaAsset hashCode consistency`() {
        val asset1 = MediaAsset(
            id = 123L,
            uri = "file:///test.jpg",
            type = MediaType.PHOTO,
            captureDate = 1000L,
            fileName = "test.jpg"
        )
        val asset2 = MediaAsset(
            id = 123L,
            uri = "file:///test.jpg",
            type = MediaType.PHOTO,
            captureDate = 1000L,
            fileName = "test.jpg"
        )

        assertEquals(asset1.hashCode(), asset2.hashCode())
    }

    // ==================== 默认值测试 ====================

    @Test
    fun `MediaAsset with default values`() {
        val asset = MediaAsset(
            uri = "file:///test.jpg",
            type = MediaType.PHOTO,
            captureDate = 1000L,
            fileName = "test.jpg"
        )

        assertEquals(0L, asset.id) // 默认 id
        assertNull(asset.duration) // 默认 duration
        assertFalse(asset.hasFace) // 默认 hasFace
        assertNull(asset.faceId) // 默认 faceId
        assertNull(asset.source) // 默认 source
    }

    // ==================== 边界值测试 ====================

    @Test
    fun `MediaAsset with zero id`() {
        val asset = MediaAsset(
            id = 0L,
            uri = "file:///test.jpg",
            type = MediaType.PHOTO,
            captureDate = 0L,
            fileName = "test.jpg"
        )

        assertEquals(0L, asset.id)
        assertEquals(0L, asset.captureDate)
    }

    @Test
    fun `MediaAsset with very large values`() {
        val asset = MediaAsset(
            id = Long.MAX_VALUE,
            uri = "file:///test.jpg",
            type = MediaType.VIDEO,
            captureDate = Long.MAX_VALUE,
            fileName = "test.jpg",
            duration = Long.MAX_VALUE,
            hasFace = true,
            faceId = "face_".repeat(100),
            source = "source_".repeat(100)
        )

        assertEquals(Long.MAX_VALUE, asset.id)
        assertEquals(Long.MAX_VALUE, asset.captureDate)
        assertEquals(Long.MAX_VALUE, asset.duration)
        assertTrue(asset.hasFace)
    }

    // ==================== 人脸相关属性测试 ====================

    @Test
    fun `MediaAsset with face detection`() {
        val asset = MediaAsset(
            id = 1L,
            uri = "file:///portrait.jpg",
            type = MediaType.PORTRAIT,
            captureDate = 1000L,
            fileName = "portrait.jpg",
            hasFace = true,
            faceId = "face_123",
            source = "portrait_mode"
        )

        assertTrue(asset.hasFace)
        assertEquals("face_123", asset.faceId)
        assertEquals("portrait_mode", asset.source)
    }

    @Test
    fun `MediaAsset without face detection`() {
        val asset = MediaAsset(
            id = 2L,
            uri = "file:///landscape.jpg",
            type = MediaType.PHOTO,
            captureDate = 1000L,
            fileName = "landscape.jpg",
            hasFace = false,
            faceId = null,
            source = "camera"
        )

        assertFalse(asset.hasFace)
        assertNull(asset.faceId)
    }

    // ==================== 文档模式测试 ====================

    @Test
    fun `MediaAsset for document mode`() {
        val asset = MediaAsset(
            id = 3L,
            uri = "file:///document.jpg",
            type = MediaType.DOCUMENT,
            captureDate = 1000L,
            fileName = "document.jpg",
            source = "document_scanner"
        )

        assertEquals(MediaType.DOCUMENT, asset.type)
        assertEquals("document_scanner", asset.source)
    }
}
