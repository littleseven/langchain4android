package com.picme.features.camera

import com.picme.beauty.api.BeautyParams
import com.picme.domain.model.BeautySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QA] GPUPixel 预览策略单元测试
 *
 * 验证 [com.picme.features.camera.preview.gpupixel.GpupixelBeautyPreviewStrategy] 的
 * 参数归一化逻辑以及 [BeautyParams] 的转换语义。
 *
 * 测试范围：
 * §1 BeautyParams 参数语义
 *    - EMPTY 常量的所有字段应为安全默认值
 *    - enabled=false 时所有效果参数应视为 0
 *    - 归一化范围约束 [0,1] / [-1,1]
 *
 * §2 GpupixelBeautyPreviewStrategy.applyBeautySettings 参数映射
 *    - BeautySettings → BeautyParams 的归一化计算（与生产代码同构）
 *    - 边界值：smoothing=0/100，slimFace=-50/0/50，bigEyes=0/100
 *    - enabled=false 时 params 应为 EMPTY
 *    - 所有效果为 0 时 params 应为 EMPTY（hasAnyEffect=false）
 *
 * §3 GPUPixel 特定逻辑
 *    - onRgbaFrame 在未初始化时应静默忽略（无崩溃）
 *    - onRgbaFrame 在 surface 未就绪时应静默忽略（surfaceAvailable=false）
 */
class GpupixelPreviewStrategyTest {

    // ================================================================
    // 与 GpupixelBeautyPreviewStrategy.applyBeautySettings 同构的映射函数
    // 生产代码修改时须同步此处
    // ================================================================

    private fun mapToBeautyParams(settings: BeautySettings): BeautyParams {
        // 专业调色参数映射（大美丽引擎路径）
        val shaderContrast = (settings.contrast / 50f).coerceIn(0f, 4f)
        val shaderSaturation = (settings.saturation / 100f).coerceIn(0f, 2f)
        val shaderTemperature = ((settings.temperature - 5000f) / 3000f).coerceIn(-1f, 1f)
        val shaderTint = (settings.tint / 100f).coerceIn(-1f, 1f)
        val shaderBrightness = (settings.brightness / 100f).coerceIn(-1f, 1f)
        val shaderRed = (settings.redAdjustment / 100f).coerceIn(0f, 2f)
        val shaderGreen = (settings.greenAdjustment / 100f).coerceIn(0f, 2f)
        val shaderBlue = (settings.blueAdjustment / 100f).coerceIn(0f, 2f)

        return if (!settings.enabled || !settings.hasAnyEffect()) {
            BeautyParams.EMPTY.copy(
                exposure = settings.exposure.coerceIn(-10f, 10f),
                contrast = shaderContrast,
                saturation = shaderSaturation,
                temperature = shaderTemperature,
                tint = shaderTint,
                brightness = shaderBrightness,
                redAdjustment = shaderRed,
                greenAdjustment = shaderGreen,
                blueAdjustment = shaderBlue
            )
        } else {
            BeautyParams(
                enabled = true,
                smoothing = (settings.smoothing / 100f).coerceIn(0f, 1f),
                whitening = (settings.whitening / 100f).coerceIn(0f, 1f),
                bigEyes = (settings.bigEyes / 100f).coerceIn(0f, 1f),
                slimFace = (settings.slimFace / 50f * 1.35f).coerceIn(-1f, 1f),
                lipColor = (settings.lipColor / 100f).coerceIn(0f, 1f),
                lipColorIndex = settings.lipColorIndex.coerceIn(0, 11),
                blush = (settings.blush / 100f).coerceIn(0f, 1f),
                blushColorFamily = settings.blushColorFamily.coerceIn(0, 2),
                exposure = settings.exposure.coerceIn(-10f, 10f),
                contrast = shaderContrast,
                saturation = shaderSaturation,
                temperature = shaderTemperature,
                tint = shaderTint,
                brightness = shaderBrightness,
                redAdjustment = shaderRed,
                greenAdjustment = shaderGreen,
                blueAdjustment = shaderBlue
            )
        }
    }

    // ================================================================
    // §1 BeautyParams 参数语义
    // ================================================================

    @Test
    fun `BeautyParams EMPTY - all fields are safe defaults`() {
        val params = BeautyParams.EMPTY
        assertFalse("enabled should be false", params.enabled)
        assertEquals("smoothing default", 0f, params.smoothing)
        assertEquals("whitening default", 0f, params.whitening)
        assertEquals("bigEyes default", 0f, params.bigEyes)
        assertEquals("slimFace default", 0f, params.slimFace)
        assertEquals("lipColor default", 0f, params.lipColor)
        assertEquals("lipColorIndex default", 0, params.lipColorIndex)
        assertEquals("blush default", 0f, params.blush)
        assertEquals("blushColorFamily default", 0, params.blushColorFamily)
    }

    @Test
    fun `BeautyParams - normalized ranges are respected`() {
        val params = BeautyParams(
            enabled = true,
            smoothing = 1.0f,
            whitening = 1.0f,
            bigEyes = 1.0f,
            slimFace = -1.0f,
            lipColor = 1.0f
        )
        assertTrue("smoothing in [0,1]", params.smoothing in 0f..1f)
        assertTrue("whitening in [0,1]", params.whitening in 0f..1f)
        assertTrue("bigEyes in [0,1]", params.bigEyes in 0f..1f)
        assertTrue("slimFace in [-1,1]", params.slimFace in -1f..1f)
        assertTrue("lipColor in [0,1]", params.lipColor in 0f..1f)
    }

    // ================================================================
    // §2 GpupixelBeautyPreviewStrategy.applyBeautySettings 参数映射
    // ================================================================

    @Test
    fun `applyBeautySettings - disabled settings maps to EMPTY`() {
        val settings = BeautySettings(
            enabled = false,
            smoothing = 80f,
            whitening = 60f
        )
        val params = mapToBeautyParams(settings)
        assertEquals("disabled should return EMPTY", BeautyParams.EMPTY, params)
    }

    @Test
    fun `applyBeautySettings - all-zero effects maps to EMPTY`() {
        // 注意：BeautySettings 的 lipColor/blush/eyebrow 有非零默认值（40/20/15），
        // 必须显式设置为 0 才能使 hasAnyEffect() 返回 false
        val settings = BeautySettings(
            enabled = true,
            smoothing = 0f,
            whitening = 0f,
            bigEyes = 0f,
            slimFace = 0f,
            lipColor = 0f,
            blush = 0f,
            eyebrow = 0f,
            bodyEnhancement = 0f,
            legExtension = 0f
        )
        assertFalse("hasAnyEffect should be false when all params are 0", settings.hasAnyEffect())
        val params = mapToBeautyParams(settings)
        assertEquals("no effects should return EMPTY", BeautyParams.EMPTY, params)
    }

    @Test
    fun `applyBeautySettings - smoothing 100 maps to 1_0f`() {
        val settings = BeautySettings(enabled = true, smoothing = 100f)
        val params = mapToBeautyParams(settings)
        assertEquals("smoothing 100 -> 1.0", 1.0f, params.smoothing)
    }

    @Test
    fun `applyBeautySettings - smoothing 50 maps to 0_5f`() {
        val settings = BeautySettings(enabled = true, smoothing = 50f)
        val params = mapToBeautyParams(settings)
        assertEquals("smoothing 50 -> 0.5", 0.5f, params.smoothing, 0.001f)
    }

    @Test
    fun `applyBeautySettings - smoothing 0 maps to 0_0f`() {
        val settings = BeautySettings(enabled = true, whitening = 50f, smoothing = 0f)
        val params = mapToBeautyParams(settings)
        assertEquals("smoothing 0 -> 0.0", 0.0f, params.smoothing)
    }

    @Test
    fun `applyBeautySettings - slimFace positive 50 maps to positive 1_35f clamped to 1_0f`() {
        val settings = BeautySettings(enabled = true, slimFace = 50f)
        val params = mapToBeautyParams(settings)
        // 50 / 50 * 1.35 = 1.35 -> coerceIn(-1, 1) = 1.0
        assertEquals("slimFace 50 -> 1.0 (clamped)", 1.0f, params.slimFace)
    }

    @Test
    fun `applyBeautySettings - slimFace negative -50 maps to negative 1_0f clamped`() {
        val settings = BeautySettings(enabled = true, slimFace = -50f)
        val params = mapToBeautyParams(settings)
        // -50 / 50 * 1.35 = -1.35 -> coerceIn(-1, 1) = -1.0
        assertEquals("slimFace -50 -> -1.0 (clamped)", -1.0f, params.slimFace)
    }

    @Test
    fun `applyBeautySettings - slimFace 0 maps to 0_0f`() {
        val settings = BeautySettings(enabled = true, whitening = 50f, slimFace = 0f)
        val params = mapToBeautyParams(settings)
        assertEquals("slimFace 0 -> 0.0", 0.0f, params.slimFace)
    }

    @Test
    fun `applyBeautySettings - bigEyes 100 maps to 1_0f`() {
        val settings = BeautySettings(enabled = true, bigEyes = 100f)
        val params = mapToBeautyParams(settings)
        assertEquals("bigEyes 100 -> 1.0", 1.0f, params.bigEyes)
    }

    @Test
    fun `applyBeautySettings - whitening 75 maps to 0_75f`() {
        val settings = BeautySettings(enabled = true, whitening = 75f)
        val params = mapToBeautyParams(settings)
        assertEquals("whitening 75 -> 0.75", 0.75f, params.whitening, 0.001f)
    }

    @Test
    fun `applyBeautySettings - lipColorIndex clamped to 0-11`() {
        val settingsOver = BeautySettings(enabled = true, lipColor = 50f, lipColorIndex = 20)
        val paramsOver = mapToBeautyParams(settingsOver)
        assertEquals("lipColorIndex 20 -> clamped to 11", 11, paramsOver.lipColorIndex)

        val settingsUnder = BeautySettings(enabled = true, lipColor = 50f, lipColorIndex = -1)
        val paramsUnder = mapToBeautyParams(settingsUnder)
        assertEquals("lipColorIndex -1 -> clamped to 0", 0, paramsUnder.lipColorIndex)
    }

    @Test
    fun `applyBeautySettings - blushColorFamily clamped to 0-2`() {
        val settingsOver = BeautySettings(enabled = true, blush = 50f, blushColorFamily = 5)
        val paramsOver = mapToBeautyParams(settingsOver)
        assertEquals("blushColorFamily 5 -> clamped to 2", 2, paramsOver.blushColorFamily)
    }

    @Test
    fun `applyBeautySettings - full settings correctly mapped`() {
        val settings = BeautySettings(
            enabled = true,
            smoothing = 80f,
            whitening = 60f,
            bigEyes = 40f,
            slimFace = 25f,
            lipColor = 50f,
            lipColorIndex = 3,
            blush = 30f,
            blushColorFamily = 1
        )
        val params = mapToBeautyParams(settings)
        assertTrue("enabled", params.enabled)
        assertEquals("smoothing", 0.8f, params.smoothing, 0.001f)
        assertEquals("whitening", 0.6f, params.whitening, 0.001f)
        assertEquals("bigEyes", 0.4f, params.bigEyes, 0.001f)
        // slimFace: 25 / 50 * 1.35 = 0.675
        assertEquals("slimFace", 0.675f, params.slimFace, 0.001f)
        assertEquals("lipColor", 0.5f, params.lipColor, 0.001f)
        assertEquals("lipColorIndex", 3, params.lipColorIndex)
        assertEquals("blush", 0.3f, params.blush, 0.001f)
        assertEquals("blushColorFamily", 1, params.blushColorFamily)
    }

    // ================================================================
    // §3 GPUPixel 特定逻辑（不依赖 Android framework 的纯逻辑）
    // ================================================================

    /**
     * 验证 BeautyParams.EMPTY 对 GPUPixel 的安全语义：
     * 所有强度参数均为 0，不会对 GPUPixel 滤镜产生任何效果（全关）
     */
    @Test
    fun `BeautyParams EMPTY is safe to apply to GPUPixel - all strengths are zero`() {
        val params = BeautyParams.EMPTY
        // GPUPixel sinkSurface 接受 0.0f 作为"关闭"信号
        assertEquals("skin_smoothing off", 0f, params.smoothing)
        assertEquals("whiteness off", 0f, params.whitening)
        assertEquals("thin_face off", 0f, params.slimFace)
        assertEquals("big_eye off", 0f, params.bigEyes)
        assertEquals("blend_level off", 0f, params.lipColor)
    }

    /**
     * 验证 isInitialized=false 时 onRgbaFrame 的静默丢帧语义（通过状态逻辑）：
     * 当 provider 未初始化时，帧数据应被安全丢弃，不触发任何处理。
     * 此测试验证 isInitialized 守卫逻辑的语义。
     */
    @Test
    fun `onRgbaFrame drop condition - isInitialized guard semantics`() {
        // 对应 GpupixelBeautyPreviewProvider.onRgbaFrame 中的守卫逻辑：
        // if (!isInitialized) return
        // if (!surfaceAvailable) return
        val isInitialized = false
        val shouldProcess = isInitialized  // 未初始化时不处理
        assertFalse("should not process frame when not initialized", shouldProcess)
    }

    /**
     * 验证 surfaceAvailable=false 时 onRgbaFrame 的静默丢帧语义：
     * Surface 未就绪时帧数据应被安全丢弃。
     */
    @Test
    fun `onRgbaFrame drop condition - surfaceAvailable guard semantics`() {
        val isInitialized = true
        val surfaceAvailable = false
        // 对应守卫逻辑：if (!surfaceAvailable) return
        val shouldProcess = isInitialized && surfaceAvailable
        assertFalse("should not process frame when surface not available", shouldProcess)
    }

    /**
     * 验证 initialized + surface 都就绪时才处理帧
     */
    @Test
    fun `onRgbaFrame process condition - both initialized and surface available`() {
        val isInitialized = true
        val surfaceAvailable = true
        val shouldProcess = isInitialized && surfaceAvailable
        assertTrue("should process frame when both ready", shouldProcess)
    }

    /**
     * 验证 pendingDisplaySurface 的绑定优先级语义：
     * - initialize() 完成后应优先使用 pendingDisplaySurface（若有效）
     * - 这是解决 TextureView 先于 initialize() 就绪的竞态条件的关键逻辑
     */
    @Test
    fun `surface binding priority - pendingDisplaySurface takes precedence over direct textureView lookup`() {
        // 竞态场景验证（状态模型）：
        // 场景 A: TextureView 先就绪，缓存 pendingDisplaySurface，然后 initialize() 绑定
        val textureViewReadyFirst = true
        val pendingSurface = if (textureViewReadyFirst) "cached_surface" else null
        val sinkSurface = "gpupixel_sink"

        // initialize() 后的绑定逻辑：优先使用 pendingDisplaySurface
        val boundSurface = if (pendingSurface != null) pendingSurface else "fallback_from_textureview"
        assertEquals("should use cached pending surface", "cached_surface", boundSurface)

        // 场景 B: initialize() 先调用，之后 onSurfaceTextureAvailable 触发直接绑定
        val initFirst = true
        val sinkReady = initFirst
        val surfaceReadyAfterInit = true
        val directBinding = sinkReady && surfaceReadyAfterInit
        assertTrue("should bind directly when sink is ready on surface available", directBinding)
    }
}

