package com.picme.core.common

import org.junit.Assert.*
import org.junit.Test

/**
 * [QA] DuplicateImageDetector 单元测试
 * 测试目标：验证 MD5 计算、感知哈希、汉明距离计算的正确性
 */
class DuplicateImageDetectorTest {

    // ==================== MD5 计算测试 ====================

    @Test
    fun `calculateMD5 with same content returns same hash`() {
        // 创建临时文件并写入相同内容
        val tempFile1 = createTempFileWithContent("test content")
        val tempFile2 = createTempFileWithContent("test content")

        val hash1 = DuplicateImageDetector.calculateMD5(tempFile1)
        val hash2 = DuplicateImageDetector.calculateMD5(tempFile2)

        assertNotNull(hash1)
        assertNotNull(hash2)
        assertEquals(hash1, hash2)
        assertEquals(32, hash1?.length) // MD5 长度为 32 个十六进制字符

        tempFile1.delete()
        tempFile2.delete()
    }

    @Test
    fun `calculateMD5 with different content returns different hash`() {
        val tempFile1 = createTempFileWithContent("content A")
        val tempFile2 = createTempFileWithContent("content B")

        val hash1 = DuplicateImageDetector.calculateMD5(tempFile1)
        val hash2 = DuplicateImageDetector.calculateMD5(tempFile2)

        assertNotNull(hash1)
        assertNotNull(hash2)
        assertNotEquals(hash1, hash2)

        tempFile1.delete()
        tempFile2.delete()
    }

    @Test
    fun `calculateMD5 with nonexistent file returns null`() {
        val nonexistentFile = java.io.File("/nonexistent/path/file.txt")

        val hash = DuplicateImageDetector.calculateMD5(nonexistentFile)

        assertNull(hash)
    }

    // ==================== 汉明距离测试 ====================

    @Test
    fun `hammingDistance with same hash returns 0`() {
        val hash = 0x123456789ABCDEF0L

        val distance = DuplicateImageDetector.hammingDistance(hash, hash)

        assertEquals(0, distance)
    }

    @Test
    fun `hammingDistance with one bit different returns 1`() {
        val hash1 = 0x0000000000000000L
        val hash2 = 0x0000000000000001L

        val distance = DuplicateImageDetector.hammingDistance(hash1, hash2)

        assertEquals(1, distance)
    }

    @Test
    fun `hammingDistance with all bits different returns 64`() {
        val hash1 = 0x0000000000000000L
        val hash2 = -1L // 0xFFFFFFFFFFFFFFFF in two's complement

        val distance = DuplicateImageDetector.hammingDistance(hash1, hash2)

        assertEquals(64, distance)
    }

    @Test
    fun `hammingDistance is symmetric`() {
        val hash1 = 0x123456789ABCDEF0L
        val hash2 = 0x0FEDCBA987654321L

        val distance1 = DuplicateImageDetector.hammingDistance(hash1, hash2)
        val distance2 = DuplicateImageDetector.hammingDistance(hash2, hash1)

        assertEquals(distance1, distance2)
    }

    // ==================== 图片相似度判断测试 ====================

    @Test
    fun `areImagesSimilar with distance 0 returns true`() {
        val hash1 = 0x123456789ABCDEF0L
        val hash2 = 0x123456789ABCDEF0L

        val similar = DuplicateImageDetector.areImagesSimilar(hash1, hash2)

        assertTrue(similar)
    }

    @Test
    fun `areImagesSimilar with distance 5 returns true`() {
        val hash1 = 0x0000000000000000L
        val hash2 = 0x000000000000001FL // 5 bits different

        val similar = DuplicateImageDetector.areImagesSimilar(hash1, hash2)

        assertTrue(similar)
    }

    @Test
    fun `areImagesSimilar with distance 6 returns false`() {
        val hash1 = 0x0000000000000000L
        val hash2 = 0x000000000000003FL // 6 bits different

        val similar = DuplicateImageDetector.areImagesSimilar(hash1, hash2)

        assertFalse(similar)
    }

    @Test
    fun `areImagesSimilar with custom threshold`() {
        val hash1 = 0x0000000000000000L
        val hash2 = 0x00000000000000FFL // 8 bits different

        // 默认阈值 5，应该不相似
        assertFalse(DuplicateImageDetector.areImagesSimilar(hash1, hash2))

        // 自定义阈值 10，应该相似
        assertTrue(DuplicateImageDetector.areImagesSimilar(hash1, hash2, threshold = 10))
    }

    // ==================== DuplicateGroup 数据类测试 ====================

    @Test
    fun `DuplicateGroup creation with exact duplicate`() {
        val files = listOf(
            java.io.File("/path/to/file1.jpg"),
            java.io.File("/path/to/file2.jpg")
        )
        val group = DuplicateImageDetector.DuplicateGroup(
            hash = "abc123",
            files = files,
            isExactDuplicate = true
        )

        assertEquals("abc123", group.hash)
        assertEquals(2, group.files.size)
        assertTrue(group.isExactDuplicate)
    }

    @Test
    fun `DuplicateGroup creation with similar images`() {
        val files = listOf(
            java.io.File("/path/to/file1.jpg"),
            java.io.File("/path/to/file2.jpg")
        )
        val group = DuplicateImageDetector.DuplicateGroup(
            hash = "def456",
            files = files,
            isExactDuplicate = false
        )

        assertEquals("def456", group.hash)
        assertEquals(2, group.files.size)
        assertFalse(group.isExactDuplicate)
    }

    // ==================== 辅助方法 ====================

    private fun createTempFileWithContent(content: String): java.io.File {
        val tempFile = java.io.File.createTempFile("test", ".txt")
        tempFile.writeText(content)
        return tempFile
    }
}
