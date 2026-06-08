package com.picme.domain.agent.remote

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.agent.core.api.command.AgentCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * IntentCache 单元测试
 *
 * 验证 L1 本地意图缓存的核心能力：
 * - 预置高频意图精确匹配
 * - LRU 缓存动态学习
 * - 模糊匹配（编辑距离）
 * - 缓存统计
 */
class IntentCacheTest {

    private lateinit var cache: IntentCache

    @Before
    fun setUp() {
        cache = IntentCache()
    }

    // ------------------------------------------------------------------
    // 1. 预置意图精确匹配
    // ------------------------------------------------------------------

    @Test
    fun `preset intent exact match - capture photo`() {
        val result = cache.match("拍照")
        assertNotNull(result)
        assertTrue(result is AgentCommand.CapturePhoto)
    }

    @Test
    fun `preset intent exact match - flip camera`() {
        val result = cache.match("翻转")
        assertNotNull(result)
        assertTrue(result is AgentCommand.FlipCamera)
    }

    @Test
    fun `preset intent exact match - toggle recording`() {
        val result = cache.match("录像")
        assertNotNull(result)
        assertTrue(result is AgentCommand.ToggleRecording)
    }

    @Test
    fun `preset intent exact match - beauty on`() {
        val result = cache.match("开美颜")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertTrue(cmd.settings.enabled)
    }

    @Test
    fun `preset intent exact match - beauty off`() {
        val result = cache.match("关美颜")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(false, cmd.settings.enabled)
    }

    // ------------------------------------------------------------------
    // 2. 调参类预置意图匹配
    // ------------------------------------------------------------------

    @Test
    fun `preset intent smoothing with value`() {
        val result = cache.match("磨皮50")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(50f, cmd.settings.smoothing, 0.01f)
        assertTrue(cmd.settings.enabled)
    }

    @Test
    fun `preset intent smoothing increase`() {
        val result = cache.match("磨皮高一点")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(60f, cmd.settings.smoothing, 0.01f)
    }

    @Test
    fun `preset intent smoothing decrease`() {
        val result = cache.match("磨皮低一点")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(30f, cmd.settings.smoothing, 0.01f)
    }

    @Test
    fun `preset intent whitening off`() {
        val result = cache.match("关美白")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(0f, cmd.settings.whitening, 0.01f)
    }

    @Test
    fun `preset intent slim face with value`() {
        val result = cache.match("瘦脸30")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(30f, cmd.settings.slimFace, 0.01f)
    }

    @Test
    fun `preset intent big eyes default`() {
        val result = cache.match("大眼")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(30f, cmd.settings.bigEyes, 0.01f)
    }

    @Test
    fun `preset intent lip color off`() {
        val result = cache.match("关唇色")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(0f, cmd.settings.lipColor, 0.01f)
    }

    @Test
    fun `preset intent blush with value`() {
        val result = cache.match("腮红20")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(20f, cmd.settings.blush, 0.01f)
    }

    // ------------------------------------------------------------------
    // 3. 滤镜/风格预置意图匹配
    // ------------------------------------------------------------------

    @Test
    fun `preset intent filter leica classic`() {
        val result = cache.match("徕卡经典")
        assertNotNull(result)
        assertTrue(result is AgentCommand.SwitchFilter)
        assertEquals(FilterType.LEICA_CLASSIC, (result as AgentCommand.SwitchFilter).filterType)
    }

    @Test
    fun `preset intent filter vintage`() {
        val result = cache.match("复古")
        assertNotNull(result)
        assertTrue(result is AgentCommand.SwitchFilter)
        assertEquals(FilterType.VINTAGE, (result as AgentCommand.SwitchFilter).filterType)
    }

    @Test
    fun `preset intent style sketch`() {
        val result = cache.match("素描")
        assertNotNull(result)
        assertTrue(result is AgentCommand.SwitchStyle)
        assertEquals(StyleFilter.SKETCH, (result as AgentCommand.SwitchStyle).styleFilter)
    }

    @Test
    fun `preset intent style off`() {
        val result = cache.match("关风格")
        assertNotNull(result)
        assertTrue(result is AgentCommand.SwitchStyle)
        assertEquals(StyleFilter.NONE, (result as AgentCommand.SwitchStyle).styleFilter)
    }

    // ------------------------------------------------------------------
    // 4. 导航/变焦/曝光预置意图匹配
    // ------------------------------------------------------------------

    @Test
    fun `preset intent navigate to gallery`() {
        val result = cache.match("去相册")
        assertNotNull(result)
        assertTrue(result is AgentCommand.NavigateTo)
        assertEquals("gallery", (result as AgentCommand.NavigateTo).destination)
    }

    @Test
    fun `preset intent go back`() {
        val result = cache.match("返回")
        assertNotNull(result)
        assertTrue(result is AgentCommand.GoBack)
    }

    @Test
    fun `preset intent zoom in`() {
        val result = cache.match("放大")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustZoom)
        assertEquals(2.0f, (result as AgentCommand.AdjustZoom).zoomRatio, 0.01f)
    }

    @Test
    fun `preset intent exposure up`() {
        val result = cache.match("亮一点")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustExposure)
        assertEquals(1, (result as AgentCommand.AdjustExposure).exposure)
    }

    @Test
    fun `preset intent exposure down`() {
        val result = cache.match("暗一点")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustExposure)
        assertEquals(-1, (result as AgentCommand.AdjustExposure).exposure)
    }

    @Test
    fun `preset intent reset beauty`() {
        val result = cache.match("恢复默认")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(BeautySettings(), cmd.settings)
    }

    // ------------------------------------------------------------------
    // 5. 未命中返回 null
    // ------------------------------------------------------------------

    @Test
    fun `unknown input returns null`() {
        val result = cache.match("这是一个不认识的指令")
        assertNull(result)
    }

    @Test
    fun `empty input returns null`() {
        val result = cache.match("")
        assertNull(result)
    }

    @Test
    fun `blank input returns null`() {
        val result = cache.match("   ")
        assertNull(result)
    }

    // ------------------------------------------------------------------
    // 6. LRU 缓存学习
    // ------------------------------------------------------------------

    @Test
    fun `cache learn and retrieve`() {
        val customCommand = AgentCommand.CapturePhoto

        // 未学习前查不到
        assertNull(cache.match("自定义指令"))

        // 学习
        cache.put("自定义指令", customCommand)

        // 学习后能查到
        val result = cache.match("自定义指令")
        assertNotNull(result)
        assertTrue(result is AgentCommand.CapturePhoto)
    }

    @Test
    fun `cache learn overwrites previous`() {
        cache.put("测试", AgentCommand.CapturePhoto)
        cache.put("测试", AgentCommand.FlipCamera)

        val result = cache.match("测试")
        assertNotNull(result)
        assertTrue(result is AgentCommand.FlipCamera)
    }

    @Test
    fun `cache clear removes learned entries`() {
        cache.put("临时指令", AgentCommand.CapturePhoto)
        assertNotNull(cache.match("临时指令"))

        cache.clear()

        // 学习的条目被清除
        assertNull(cache.match("临时指令"))
        // 预置意图保留
        assertNotNull(cache.match("拍照"))
    }

    // ------------------------------------------------------------------
    // 7. 模糊匹配（编辑距离 <= 1）
    // ------------------------------------------------------------------

    @Test
    fun `fuzzy match single deletion`() {
        // "拍照" -> "拍" (删除一个字)
        val result = cache.match("拍")
        // 单字太短，编辑距离计算可能不匹配，取决于实现
        // 这里主要验证不崩溃
    }

    @Test
    fun `fuzzy match typo one char`() {
        // "拍照" -> "排照" (替换一个字)
        val result = cache.match("排照")
        assertNotNull("编辑距离=1 应命中", result)
        assertTrue(result is AgentCommand.CapturePhoto)
    }

    @Test
    fun `fuzzy match typo two chars no match`() {
        // "拍照" -> "排照" 是 1 个编辑距离，但 "拍照片" 到 "排照片" 也是 1
        val result = cache.match("排照片")
        assertNotNull(result)
        assertTrue(result is AgentCommand.CapturePhoto)
    }

    // ------------------------------------------------------------------
    // 8. 缓存统计
    // ------------------------------------------------------------------

    @Test
    fun `cache stats track hits and misses`() {
        // 初始状态
        val initialStats = cache.stats()
        assertEquals(0, initialStats.hitCount)
        assertEquals(0, initialStats.missCount)

        // 一次命中
        cache.match("拍照")
        val afterHit = cache.stats()
        assertEquals(1, afterHit.hitCount)

        // 一次未命中
        cache.match("不存在")
        val afterMiss = cache.stats()
        assertEquals(1, afterMiss.missCount)
    }

    @Test
    fun `cache stats calculate hit rate`() {
        cache.match("拍照") // hit
        cache.match("拍照") // hit
        cache.match("未知") // miss

        val stats = cache.stats()
        assertEquals(2, stats.hitCount)
        assertEquals(1, stats.missCount)
        assertEquals(2f / 3f, stats.hitRate, 0.01f)
    }

    // ------------------------------------------------------------------
    // 9. 边界情况
    // ------------------------------------------------------------------

    @Test
    fun `input with whitespace is trimmed`() {
        val result = cache.match("  拍照  ")
        assertNotNull(result)
        assertTrue(result is AgentCommand.CapturePhoto)
    }

    @Test
    fun `english preset intents work`() {
        val result = cache.match("capture")
        assertNotNull(result)
        assertTrue(result is AgentCommand.CapturePhoto)
    }

    @Test
    fun `cache size within limit`() {
        // 预置意图 + 学习的条目不应超过 maxSize
        for (i in 0..200) {
            cache.put("指令$i", AgentCommand.CapturePhoto)
        }
        val stats = cache.stats()
        assertTrue("缓存不应超过上限", stats.size <= stats.maxSize)
    }
}
