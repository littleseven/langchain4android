package com.picme.beauty.api

import com.picme.beauty.api.BeautySettings
import org.junit.Assert.*
import org.junit.Test

/**
 * [QA] BeautySettings 单元测试
 * 测试目标：验证美颜设置数据类的默认值和状态判断逻辑
 */
class BeautySettingsTest {

    // ==================== 默认值测试 ====================

    @Test
    fun `default BeautySettings has correct default values`() {
        val settings = BeautySettings()

        assertFalse(settings.enabled)
        assertEquals(0f, settings.smoothing, 0.001f)
        assertEquals(0f, settings.whitening, 0.001f)
        assertEquals(0f, settings.slimFace, 0.001f)
        assertEquals(0f, settings.bigEyes, 0.001f)
        assertEquals(BeautySettings.DEFAULT_LIP_COLOR, settings.lipColor, 0.001f)
        assertEquals(0, settings.lipColorIndex)
        assertEquals(BeautySettings.DEFAULT_BLUSH, settings.blush, 0.001f)
        assertEquals(0, settings.blushColorFamily)
        assertEquals(BeautySettings.DEFAULT_EYEBROW, settings.eyebrow, 0.001f)
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

        // 默认所有美颜参数为 0，hasAnyEffect 应该返回 false
        assertFalse(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns false when only enabled is true and others are zero`() {
        // 手动设置为零值（覆盖默认值）
        val settings = BeautySettings(
            enabled = true,
            lipColor = 0f,
            blush = 0f,
            eyebrow = 0f
        )

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
    fun `hasAnyEffect returns false when only lipColorIndex is set and other effects are zero`() {
        // lipColorIndex 不影响 hasAnyEffect 结果
        val settings = BeautySettings(
            lipColorIndex = 5,
            lipColor = 0f,
            blush = 0f,
            eyebrow = 0f
        )

        assertFalse(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns false when only enabled is true with zeroed defaults`() {
        // enabled 标志不影响 hasAnyEffect 结果
        val settings = BeautySettings(
            enabled = true,
            lipColor = 0f,
            blush = 0f,
            eyebrow = 0f
        )

        assertFalse(settings.hasAnyEffect())
    }

    // ==================== 边界值测试 ====================

    @Test
    fun `hasAnyEffect with smoothing at boundary values`() {
        // 需要显式设置其他默认值为0
        assertFalse(BeautySettings(smoothing = 0f, lipColor = 0f, blush = 0f, eyebrow = 0f).hasAnyEffect())
        assertTrue(BeautySettings(smoothing = 0.1f, lipColor = 0f, blush = 0f, eyebrow = 0f).hasAnyEffect())
        assertTrue(BeautySettings(smoothing = 100f, lipColor = 0f, blush = 0f, eyebrow = 0f).hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect with slimFace at boundary values`() {
        assertFalse(BeautySettings(slimFace = 0f, lipColor = 0f, blush = 0f, eyebrow = 0f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = 0.1f, lipColor = 0f, blush = 0f, eyebrow = 0f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = -0.1f, lipColor = 0f, blush = 0f, eyebrow = 0f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = 50f, lipColor = 0f, blush = 0f, eyebrow = 0f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = -50f, lipColor = 0f, blush = 0f, eyebrow = 0f).hasAnyEffect())
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

    // ==================== 新增参数 hasAnyEffect() 测试 ====================

    @Test
    fun `hasAnyEffect returns true when colorFilter is not NONE`() {
        val settings = BeautySettings(
            colorFilter = FilterType.LEICA_CLASSIC,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when styleFilter is not NONE`() {
        val settings = BeautySettings(
            styleFilter = StyleFilter.TOON,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when exposure is non-zero`() {
        val settings = BeautySettings(
            exposure = 1f,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when contrast is not default`() {
        val settings = BeautySettings(
            contrast = 60f,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when saturation is not default`() {
        val settings = BeautySettings(
            saturation = 90f,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when temperature is not default`() {
        val settings = BeautySettings(
            temperature = 5500f,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when tint is non-zero`() {
        val settings = BeautySettings(
            tint = 10f,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when brightness is non-zero`() {
        val settings = BeautySettings(
            brightness = 10f,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when redAdjustment is not default`() {
        val settings = BeautySettings(
            redAdjustment = 110f,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when greenAdjustment is not default`() {
        val settings = BeautySettings(
            greenAdjustment = 110f,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns true when blueAdjustment is not default`() {
        val settings = BeautySettings(
            blueAdjustment = 110f,
            lipColor = 0f, blush = 0f, eyebrow = 0f
        )
        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns false when all color grade params are at default`() {
        val settings = BeautySettings(
            lipColor = 0f, blush = 0f, eyebrow = 0f,
            colorFilter = FilterType.NONE,
            styleFilter = StyleFilter.NONE,
            exposure = 0f, contrast = 50f, saturation = 100f,
            temperature = 5000f, tint = 0f, brightness = 0f,
            redAdjustment = 100f, greenAdjustment = 100f, blueAdjustment = 100f
        )
        assertFalse(settings.hasAnyEffect())
    }
}
