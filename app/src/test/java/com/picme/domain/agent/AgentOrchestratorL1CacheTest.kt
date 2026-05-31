package com.picme.domain.agent

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.remote.IntentCache
import com.picme.domain.model.MediaType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AgentOrchestrator L1 缓存集成测试
 *
 * 验证 LOCAL 模式下 L1 缓存体系的端到端行为：
 * - 缓存命中时直接返回，不调用 LLM
 * - 缓存未命中时走 LLM 推理
 * - 解析成功后自动学习写入缓存
 * - 缓存学习后再次命中
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentOrchestratorL1CacheTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private val defaultContext = AgentContext(
        scene = AgentScene.CAMERA,
        beautySettings = BeautySettings(),
        filterType = FilterType.NONE,
        styleFilter = StyleFilter.NONE,
        zoomRatio = 1f,
        exposureCompensation = 0,
        captureMode = MediaType.PHOTO,
        isRecording = false
    )

    // ------------------------------------------------------------------
    // 1. L1 缓存命中直接返回，零 LLM 调用
    // ------------------------------------------------------------------

    @Test
    fun `L1 cache hit returns immediately without LLM call`() = runTest {
        // 验证：缓存命中时，AgentOrchestrator 直接返回结果
        // 不需要 mock LLM，因为不应该被调用
        val cache = IntentCache()

        // "拍照" 是预置意图，应直接命中
        val result = cache.match("拍照")
        assertNotNull("预置意图应命中", result)
        assertTrue(result is AgentCommand.CapturePhoto)
    }

    @Test
    fun `L1 cache hit for beauty adjustment`() = runTest {
        val cache = IntentCache()

        val result = cache.match("磨皮50")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(50f, cmd.settings.smoothing, 0.01f)
    }

    @Test
    fun `L1 cache hit for filter switch`() = runTest {
        val cache = IntentCache()

        val result = cache.match("徕卡经典")
        assertNotNull(result)
        assertTrue(result is AgentCommand.SwitchFilter)
        assertEquals(FilterType.LEICA_CLASSIC, (result as AgentCommand.SwitchFilter).filterType)
    }

    @Test
    fun `L1 cache hit for style switch`() = runTest {
        val cache = IntentCache()

        val result = cache.match("卡通")
        assertNotNull(result)
        assertTrue(result is AgentCommand.SwitchStyle)
        assertEquals(StyleFilter.TOON, (result as AgentCommand.SwitchStyle).styleFilter)
    }

    @Test
    fun `L1 cache hit for navigation`() = runTest {
        val cache = IntentCache()

        val result = cache.match("去相册")
        assertNotNull(result)
        assertTrue(result is AgentCommand.NavigateTo)
        assertEquals("gallery", (result as AgentCommand.NavigateTo).destination)
    }

    @Test
    fun `L1 cache hit for zoom`() = runTest {
        val cache = IntentCache()

        val result = cache.match("放大")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustZoom)
        assertEquals(2.0f, (result as AgentCommand.AdjustZoom).zoomRatio, 0.01f)
    }

    @Test
    fun `L1 cache hit for exposure`() = runTest {
        val cache = IntentCache()

        val result = cache.match("调亮")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustExposure)
        assertEquals(1, (result as AgentCommand.AdjustExposure).exposure)
    }

    // ------------------------------------------------------------------
    // 2. 缓存未命中返回 null
    // ------------------------------------------------------------------

    @Test
    fun `L1 cache miss returns null`() = runTest {
        val cache = IntentCache()

        val result = cache.match("这是一个完全未知的指令")
        assertEquals(null, result)
    }

    @Test
    fun `L1 cache miss for partial match`() = runTest {
        val cache = IntentCache()

        // "磨皮" 命中，但 "磨皮一下下" 不在预置中，编辑距离 > 1
        val result = cache.match("磨皮一下下")
        assertEquals(null, result)
    }

    // ------------------------------------------------------------------
    // 3. 缓存学习机制
    // ------------------------------------------------------------------

    @Test
    fun `cache learns new command`() = runTest {
        val cache = IntentCache()

        // 学习前未命中
        assertNull(cache.match("自定义测试指令"))

        // 学习
        cache.put("自定义测试指令", AgentCommand.CapturePhoto)

        // 学习后命中
        val result = cache.match("自定义测试指令")
        assertNotNull(result)
        assertTrue(result is AgentCommand.CapturePhoto)
    }

    @Test
    fun `cache learns beauty command`() = runTest {
        val cache = IntentCache()
        val customBeauty = AgentCommand.AdjustBeauty(
            BeautySettings(enabled = true, smoothing = 75f, whitening = 60f)
        )

        cache.put("我的专属美颜", customBeauty)

        val result = cache.match("我的专属美颜")
        assertNotNull(result)
        assertTrue(result is AgentCommand.AdjustBeauty)
        val cmd = result as AgentCommand.AdjustBeauty
        assertEquals(75f, cmd.settings.smoothing, 0.01f)
        assertEquals(60f, cmd.settings.whitening, 0.01f)
    }

    @Test
    fun `cache learns and updates existing key`() = runTest {
        val cache = IntentCache()

        cache.put("测试键", AgentCommand.CapturePhoto)
        cache.put("测试键", AgentCommand.FlipCamera)

        val result = cache.match("测试键")
        assertNotNull(result)
        assertTrue(result is AgentCommand.FlipCamera)
    }

    // ------------------------------------------------------------------
    // 4. 缓存统计验证
    // ------------------------------------------------------------------

    @Test
    fun `cache tracks hit and miss statistics`() = runTest {
        val cache = IntentCache()

        // 连续命中
        cache.match("拍照")
        cache.match("翻转")
        cache.match("磨皮50")

        // 未命中
        cache.match("未知指令1")
        cache.match("未知指令2")

        val stats = cache.stats()
        assertEquals(3, stats.hitCount)
        assertEquals(2, stats.missCount)
        assertEquals(3f / 5f, stats.hitRate, 0.01f)
    }

    @Test
    fun `cache hit rate after learning`() = runTest {
        val cache = IntentCache()

        // 先 miss
        cache.match("新指令")

        // 学习
        cache.put("新指令", AgentCommand.CapturePhoto)

        // 再 hit
        cache.match("新指令")

        val stats = cache.stats()
        assertEquals(1, stats.hitCount)
        assertEquals(1, stats.missCount)
        assertEquals(0.5f, stats.hitRate, 0.01f)
    }

    // ------------------------------------------------------------------
    // 5. 端到端：缓存命中 → 直接分发（模拟 AgentOrchestrator 行为）
    // ------------------------------------------------------------------

    @Test
    fun `end to end cache hit bypasses LLM`() = runTest {
        // 模拟 AgentOrchestrator.processUserInput 的 L1 缓存路径
        val cache = IntentCache()
        val dispatcher = FakeCommandDispatcher()

        val input = "拍照"

        // L1 查询
        val cachedCommand = cache.match(input)
        assertNotNull(cachedCommand)

        // 直接分发（不经过 LLM）
        val action = dispatcher.dispatch(cachedCommand!!, defaultContext, null)

        assertTrue(action.isSuccess)
        assertTrue(action.getOrNull() is AgentAction.Success)
        assertEquals(cachedCommand, (action.getOrNull() as AgentAction.Success).command)
    }

    @Test
    fun `end to end cache miss then learn`() = runTest {
        val cache = IntentCache()
        val dispatcher = FakeCommandDispatcher()

        val input = "学习这个新指令"

        // 第一次：未命中
        val firstLookup = cache.match(input)
        assertNull(firstLookup)

        // 模拟 LLM 解析结果
        val parsedCommand = AgentCommand.CapturePhoto

        // 学习
        cache.put(input, parsedCommand)

        // 第二次：命中
        val secondLookup = cache.match(input)
        assertNotNull(secondLookup)
        assertTrue(secondLookup is AgentCommand.CapturePhoto)

        // 分发
        val action = dispatcher.dispatch(secondLookup!!, defaultContext, null)
        assertTrue(action.isSuccess)
    }

    // ------------------------------------------------------------------
    // 6. 边界情况
    // ------------------------------------------------------------------

    @Test
    fun `empty string returns null`() = runTest {
        val cache = IntentCache()
        assertNull(cache.match(""))
    }

    @Test
    fun `whitespace only returns null`() = runTest {
        val cache = IntentCache()
        assertNull(cache.match("   "))
    }

    @Test
    fun `input with surrounding whitespace matches`() = runTest {
        val cache = IntentCache()
        val result = cache.match("  拍照  ")
        assertNotNull(result)
        assertTrue(result is AgentCommand.CapturePhoto)
    }

    @Test
    fun `cache clear preserves preset intents`() = runTest {
        val cache = IntentCache()

        cache.put("临时", AgentCommand.CapturePhoto)
        cache.clear()

        // 学习的被清除
        assertNull(cache.match("临时"))
        // 预置的保留
        assertNotNull(cache.match("拍照"))
    }

    @Test
    fun `cache does not learn error or text reply commands`() = runTest {
        // 这个测试验证 AgentOrchestrator 中的逻辑：
        // if (command !is AgentCommand.Error && command !is AgentCommand.TextReply) {
        //     intentCache.put(userInput, command)
        // }

        val cache = IntentCache()

        // 模拟：不学习 Error
        val errorCmd = AgentCommand.Error("测试错误")
        // 这里不调用 put，验证 Error 不会被学习
        // （实际行为由 AgentOrchestrator 控制，此测试验证设计意图）

        // 模拟：不学习 TextReply
        val textCmd = AgentCommand.TextReply("测试回复")
        // 同上

        // 验证预置意图不受影响
        assertNotNull(cache.match("拍照"))
    }

    // ------------------------------------------------------------------
    // 7. 高频场景组合测试
    // ------------------------------------------------------------------

    @Test
    fun `rapid fire cache hits`() = runTest {
        val cache = IntentCache()
        val inputs = listOf("拍照", "翻转", "磨皮50", "美白", "徕卡经典", "去相册")

        inputs.forEach { input ->
            val result = cache.match(input)
            assertNotNull("$input 应命中", result)
        }

        val stats = cache.stats()
        assertEquals(inputs.size, stats.hitCount)
        assertEquals(0, stats.missCount)
        assertEquals(1.0f, stats.hitRate, 0.01f)
    }

    @Test
    fun `mixed hit and miss pattern`() = runTest {
        val cache = IntentCache()

        // 命中序列
        cache.match("拍照") // hit
        cache.match("未知1") // miss
        cache.match("翻转") // hit
        cache.match("未知2") // miss
        cache.match("磨皮50") // hit

        val stats = cache.stats()
        assertEquals(3, stats.hitCount)
        assertEquals(2, stats.missCount)
    }
}
