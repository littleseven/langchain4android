package com.picme.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * [QA] BeautySettings 单元测试
 * 测试目标：验证美颜设置数据类的默认值和状态判断逻辑
 */
class BeautySettingsTest {

    // ==================== 默认值测试 ====================

    @Test
    fun `default BeautySettings has all values at zero or false`() {
        val settings = BeautySettings()

        assertFalse(settings.enabled)
        assertEquals(0f, settings.smoothing, 0.001f)
        assertEquals(0f, settings.whitening, 0.001f)
        assertEquals(0f, settings.slimFace, 0.001f)
        assertEquals(0f, settings.bigEyes, 0.001f)
        assertEquals(0f, settings.lipColor, 0.001f)
        assertEquals(0, settings.lipColorIndex)
        assertEquals(0f, settings.blush, 0.001f)
        assertEquals(0f, settings.eyebrow, 0.001f)
        assertEquals(0f, settings.bodyEnhancement, 0.001f)
        assertEquals(0f, settings.legExtension, 0.001f)
    }

    @Test
    fun `BeautySettings with custom values`() {
        val settings = BeautySettings(
            enabled = true,
            smoothing = 50f,
            whitening = 30f,
            slimFace = 20f,
            bigEyes = 40f,
            lipColor = 60f,
            lipColorIndex = 5,
            blush = 25f,
            eyebrow = 35f,
            bodyEnhancement = 15f,
            legExtension = 30f
        )

        assertTrue(settings.enabled)
        assertEquals(50f, settings.smoothing, 0.001f)
        assertEquals(30f, settings.whitening, 0.001f)
        assertEquals(20f, settings.slimFace, 0.001f)
        assertEquals(40f, settings.bigEyes, 0.001f)
        assertEquals(60f, settings.lipColor, 0.001f)
        assertEquals(5, settings.lipColorIndex)
        assertEquals(25f, settings.blush, 0.001f)
        assertEquals(35f, settings.eyebrow, 0.001f)
        assertEquals(15f, settings.bodyEnhancement, 0.001f)
        assertEquals(30f, settings.legExtension, 0.001f)
    }

    // ==================== hasAnyEffect() 测试 ====================

    @Test
    fun `hasAnyEffect returns false for default settings`() {
        val settings = BeautySettings()

        assertFalse(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when smoothing is set`() {
        val settings = BeautySettings(smoothing = 1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when whitening is set`() {
        val settings = BeautySettings(whitening = 1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when slimFace is positive`() {
        val settings = BeautySettings(slimFace = 1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when slimFace is negative`() {
        val settings = BeautySettings(slimFace = -1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when bigEyes is set`() {
        val settings = BeautySettings(bigEyes = 1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when lipColor is set`() {
        val settings = BeautySettings(lipColor = 1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when blush is set`() {
        val settings = BeautySettings(blush = 1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when eyebrow is set`() {
        val settings = BeautySettings(eyebrow = 1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when bodyEnhancement is positive`() {
        val settings = BeautySettings(bodyEnhancement = 1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when bodyEnhancement is negative`() {
        val settings = BeautySettings(bodyEnhancement = -1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when legExtension is set`() {
        val settings = BeautySettings(legExtension = 1f)

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns false when only lipColorIndex is set`() {
        // lipColorIndex 不影响 hasAnyEffect 结果
        val settings = BeautySettings(lipColorIndex = 5)

        assertFalse(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns false when only enabled is true`() {
        // enabled 标志不影响 hasAnyEffect 结果
        val settings = BeautySettings(enabled = true)

        assertFalse(settings.hasAnyEffect())
    }

    // ==================== 边界值测试 ====================

    @Test
    fun `hasAnyEffect with smoothing at boundary values`() {
        assertFalse(BeautySettings(smoothing = 0f).hasAnyEffect())
        assertTrue(BeautySettings(smoothing = 0.1f).hasAnyEffect())
        assertTrue(BeautySettings(smoothing = 100f).hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect with slimFace at boundary values`() {
        assertFalse(BeautySettings(slimFace = 0f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = 0.1f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = -0.1f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = 50f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = -50f).hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect with multiple effects`() {
        val settings = BeautySettings(
            smoothing = 10f,
            whitening = 20f,
            bigEyes = 30f
        )

        assertTrue(settings.hasAnyEffect())
    }

    // ==================== 数据类特性测试 ====================

    @Test
    fun `BeautySettings is immutable`() {
        val settings1 = BeautySettings(smoothing = 50f)
        val settings2 = settings1.copy(whitening = 30f)

        // 原始对象不变
        assertEquals(0f, settings1.whitening, 0.001f)
        assertEquals(50f, settings1.smoothing, 0.001f)

        // 新对象包含修改
        assertEquals(50f, settings2.smoothing, 0.001f)
        assertEquals(30f, settings2.whitening, 0.001f)
    }

    @Test
    fun `BeautySettings equality`() {
        val settings1 = BeautySettings(smoothing = 50f, whitening = 30f)
        val settings2 = BeautySettings(smoothing = 50f, whitening = 30f)
        val settings3 = BeautySettings(smoothing = 50f, whitening = 40f)

        assertEquals(settings1, settings2)
        assertNotEquals(settings1, settings3)
    }

    @Test
    fun `BeautySettings hashCode consistency`() {
        val settings1 = BeautySettings(smoothing = 50f, whitening = 30f)
        val settings2 = BeautySettings(smoothing = 50f, whitening = 30f)

        assertEquals(settings1.hashCode(), settings2.hashCode())
    }
}
