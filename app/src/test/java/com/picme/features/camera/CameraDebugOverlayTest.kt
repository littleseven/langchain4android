package com.picme.features.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QA] CameraDebugOverlay ContentArea 计算逻辑单元测试
 *
 * 背景：
 * 4:3 / 16:9 比例切换后，PreviewView 使用 FIT_CENTER 模式，画面内容不再填满
 * 整个 Canvas。调试 UI 必须基于「内容区域」而非「全 Canvas」进行绘制，否则会
 * 因黑边偏移导致眼点标记与实际预览对不上。
 *
 * 此文件测试的是 CameraDebugOverlay 中的内容区域（ContentArea）计算逻辑的纯数学部分，
 * 与 Compose Canvas 解耦，以便在 JVM 单元测试中运行。
 *
 * 测试策略：
 * 1. RATIO_FULL（16:9）→ FILL_CENTER，ContentArea = 全 Canvas，无偏移
 * 2. RATIO_4_3 → FIT_CENTER，竖屏：内容宽高比(3/4) < Canvas 宽高比(9/16)，
 *    左右填满，上下有黑边 → 验证 contentOffsetY > 0
 * 3. RATIO_16_9 → FIT_CENTER，内容宽高比(9/16) = Canvas 宽高比(9/16)，
 *    无黑边 → 验证 offsets 均为 0
 * 4. toCanvasPoint 偏移验证：归一化中心点在内容区域中映射到实际像素坐标
 * 5. 黑边高度精确量化（与 CoordinateMappingValidationTest TC10 呼应）
 */
class CameraDebugOverlayTest {

    /**
     * 内容区域计算结果
     */
    data class ContentArea(
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float
    )

    /**
     * 与 CameraDebugOverlay.kt 内容区域计算同构的纯函数
     *
     * @param canvasWidth   Canvas 宽（PreviewView 物理宽度，px）
     * @param canvasHeight  Canvas 高（PreviewView 物理高度，px）
     * @param aspectRatio   拍摄比例常量（AspectRatio.RATIO_FULL / RATIO_4_3 / RATIO_16_9）
     */
    private fun computeContentArea(
        canvasWidth: Float,
        canvasHeight: Float,
        aspectRatio: Int
    ): ContentArea {
        if (aspectRatio == AspectRatio.RATIO_FULL) {
            return ContentArea(0f, 0f, canvasWidth, canvasHeight)
        }

        val imageContentAspect = when (aspectRatio) {
            AspectRatio.RATIO_4_3 -> 3f / 4f   // 竖屏 4:3：内容宽/高 = 3/4
            AspectRatio.RATIO_16_9 -> 9f / 16f  // 竖屏 16:9：内容宽/高 = 9/16
            else -> canvasWidth / canvasHeight
        }
        val canvasAspect = canvasWidth / canvasHeight

        return if (imageContentAspect < canvasAspect) {
            // 内容比 Canvas 更窄：上下填满，左右有黑边
            val contentHeight = canvasHeight
            val contentWidth = canvasHeight * imageContentAspect
            ContentArea(
                offsetX = (canvasWidth - contentWidth) / 2f,
                offsetY = 0f,
                width = contentWidth,
                height = contentHeight
            )
        } else {
            // 内容比 Canvas 更宽或等宽：左右填满，上下有黑边
            val contentWidth = canvasWidth
            val contentHeight = canvasWidth / imageContentAspect
            ContentArea(
                offsetX = 0f,
                offsetY = (canvasHeight - contentHeight) / 2f,
                width = contentWidth,
                height = contentHeight
            )
        }
    }

    /**
     * 将归一化坐标映射到 Canvas 像素坐标（与 CameraDebugOverlay.toCanvasPoint 同构）
     */
    private fun toCanvasPoint(
        normX: Float,
        normY: Float,
        contentArea: ContentArea
    ): Pair<Float, Float> {
        val x = contentArea.offsetX + normX.coerceIn(0f, 1f) * contentArea.width
        val y = contentArea.offsetY + normY.coerceIn(0f, 1f) * contentArea.height
        return Pair(x, y)
    }

    // ================================================================
    // RATIO_FULL（16:9 全屏）测试
    // ================================================================

    @Test
    fun `RATIO_FULL - content area equals full canvas with zero offsets`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_FULL)

        assertEquals("offsetX should be 0", 0f, area.offsetX, 0.1f)
        assertEquals("offsetY should be 0", 0f, area.offsetY, 0.1f)
        assertEquals("width should equal canvas width", 1080f, area.width, 0.1f)
        assertEquals("height should equal canvas height", 1920f, area.height, 0.1f)
    }

    @Test
    fun `RATIO_FULL - center point maps to canvas center`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_FULL)
        val (px, py) = toCanvasPoint(0.5f, 0.5f, area)

        assertEquals("Center X should be 540", 540f, px, 0.5f)
        assertEquals("Center Y should be 960", 960f, py, 0.5f)
    }

    @Test
    fun `RATIO_FULL - top-left corner maps to origin`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_FULL)
        val (px, py) = toCanvasPoint(0f, 0f, area)

        assertEquals("Top-left X should be 0", 0f, px, 0.1f)
        assertEquals("Top-left Y should be 0", 0f, py, 0.1f)
    }

    // ================================================================
    // RATIO_4_3（4:3 竖屏 → 有上下黑边）测试
    // ================================================================

    @Test
    fun `RATIO_4_3 on 1080x1920 canvas - has non-zero vertical offset (letterbox)`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)

        // 4:3 竖屏：imageContentAspect=3/4=0.75，canvasAspect=1080/1920≈0.5625
        // imageContentAspect(0.75) > canvasAspect(0.5625) → 左右填满，上下有黑边
        assertEquals("offsetX should be 0 (left-right fills canvas)", 0f, area.offsetX, 0.1f)
        assertTrue("offsetY should be > 0 for letterbox", area.offsetY > 0f)
    }

    @Test
    fun `RATIO_4_3 on 1080x1920 canvas - content height is 1080 div (3 div 4)`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)

        // contentHeight = canvasWidth / imageContentAspect = 1080 / 0.75 = 1440
        assertEquals("Content height should be 1440 (4:3 fitted into 1080 wide canvas)",
            1440f, area.height, 1f)
        assertEquals("Content width should be 1080", 1080f, area.width, 1f)
    }

    @Test
    fun `RATIO_4_3 on 1080x1920 canvas - vertical offset equals (1920-1440) div 2`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)

        // offsetY = (1920 - 1440) / 2 = 240
        assertEquals("Vertical offset (letterbox top) should be 240px", 240f, area.offsetY, 1f)
    }

    @Test
    fun `RATIO_4_3 - center point (0_5, 0_5) maps correctly into content area`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)
        // contentOffsetY=240, contentHeight=1440
        // canvasPoint.y = 240 + 0.5 * 1440 = 240 + 720 = 960
        val (px, py) = toCanvasPoint(0.5f, 0.5f, area)

        assertEquals("Center X should be 540", 540f, px, 1f)
        assertEquals("Center Y should be 960 (center of content area)", 960f, py, 1f)
    }

    @Test
    fun `RATIO_4_3 - top of content area (normY=0) maps to offsetY, not canvas top`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)
        val (_, py) = toCanvasPoint(0.5f, 0f, area)

        // normY=0 应映射到 contentOffsetY=240，而非 canvas top=0
        assertEquals("Top of content (normY=0) should map to offsetY=240", 240f, py, 1f)
    }

    @Test
    fun `RATIO_4_3 - bottom of content area (normY=1) maps to offsetY + contentHeight`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)
        val (_, py) = toCanvasPoint(0.5f, 1f, area)

        // normY=1 应映射到 240 + 1440 = 1680
        assertEquals("Bottom of content (normY=1) should map to offsetY+contentHeight=1680",
            1680f, py, 1f)
    }

    @Test
    fun `RATIO_4_3 - black bar height above content is same as below content`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)
        val topBlackBar = area.offsetY
        val bottomBlackBar = 1920f - (area.offsetY + area.height)

        assertEquals("Top and bottom black bar should be equal (centered FIT_CENTER)",
            topBlackBar, bottomBlackBar, 1f)
    }

    // ================================================================
    // RATIO_16_9（16:9 竖屏 → 与全屏等价，无黑边）测试
    // ================================================================

    @Test
    fun `RATIO_16_9 on 1080x1920 canvas - no letterbox (content aspect equals canvas aspect)`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_16_9)

        // 16:9 竖屏：imageContentAspect=9/16=0.5625 = canvasAspect=1080/1920=0.5625
        // imageContentAspect 等于 canvasAspect → else 分支，上下填满
        assertEquals("offsetX should be 0", 0f, area.offsetX, 0.1f)
        assertEquals("offsetY should be 0 (no letterbox in 16:9)", 0f, area.offsetY, 1f)
        assertEquals("Content height should be 1920", 1920f, area.height, 1f)
        assertEquals("Content width should be 1080", 1080f, area.width, 1f)
    }

    @Test
    fun `RATIO_16_9 - center point maps to canvas center (same as RATIO_FULL)`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_16_9)
        val (px, py) = toCanvasPoint(0.5f, 0.5f, area)

        assertEquals("Center X should be 540", 540f, px, 0.5f)
        assertEquals("Center Y should be 960", 960f, py, 0.5f)
    }

    // ================================================================
    // 比例切换后黑边量化对比（复现 Bug 影响）
    // ================================================================

    @Test
    fun `aspect ratio switch - 4x3 has 240px top offset vs 16x9 with 0px offset`() {
        val area43 = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)
        val area169 = computeContentArea(1080f, 1920f, AspectRatio.RATIO_16_9)

        val letterboxDelta = area43.offsetY - area169.offsetY
        assertEquals(
            "4:3 vs 16:9 top letterbox difference should be 240px",
            240f, letterboxDelta, 1f
        )
    }

    @Test
    fun `same normY point in 4x3 vs 16x9 lands at different canvas Y`() {
        val normY = 0.25f // 画面上 1/4 处

        val area43 = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)
        val area169 = computeContentArea(1080f, 1920f, AspectRatio.RATIO_FULL)

        val (_, y43) = toCanvasPoint(0.5f, normY, area43)
        val (_, y169) = toCanvasPoint(0.5f, normY, area169)

        // 4:3 时：y = 240 + 0.25 * 1440 = 240 + 360 = 600
        // Full 时：y = 0 + 0.25 * 1920 = 480
        assertTrue("4:3 upper-quarter point should have higher canvas Y than full",
            y43 > y169)
        assertEquals("4:3 upper-quarter Y should be ~600", 600f, y43, 1f)
        assertEquals("Full upper-quarter Y should be ~480", 480f, y169, 1f)
    }

    @Test
    fun `RATIO_4_3 - eye point vertical separation is maintained in content area`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)

        // 左眼 normY = 0.4375, 人脸中心 normY = 0.5
        val (_, eyeY) = toCanvasPoint(0.5f, 0.4375f, area)
        val (_, centerY) = toCanvasPoint(0.5f, 0.5f, area)

        // 眼睛在人脸上方：eyeY < centerY
        assertTrue("Eye should be above face center in canvas coords", eyeY < centerY)
    }

    // ================================================================
    // 边界与鲁棒性测试
    // ================================================================

    @Test
    fun `toCanvasPoint - clamps normX below 0 to 0`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_FULL)
        val (px, _) = toCanvasPoint(-0.5f, 0.5f, area)
        assertEquals("Negative normX should be clamped to 0", 0f, px, 0.1f)
    }

    @Test
    fun `toCanvasPoint - clamps normX above 1 to 1`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_FULL)
        val (px, _) = toCanvasPoint(1.5f, 0.5f, area)
        assertEquals("normX > 1 should be clamped to canvas right edge", 1080f, px, 0.1f)
    }

    @Test
    fun `toCanvasPoint - clamps normY above 1 to content bottom`() {
        val area = computeContentArea(1080f, 1920f, AspectRatio.RATIO_4_3)
        val (_, py) = toCanvasPoint(0.5f, 1.5f, area)
        // 被 coerceIn(0f, 1f) 后 normY=1，映射到 contentOffsetY + contentHeight = 240 + 1440 = 1680
        assertEquals("normY > 1 should map to content bottom", 1680f, py, 1f)
    }

    @Test
    fun `non-standard canvas size - RATIO_4_3 still computes correct offset`() {
        // 模拟较小屏幕设备：720x1280
        val area = computeContentArea(720f, 1280f, AspectRatio.RATIO_4_3)

        // contentHeight = 720 / (3f/4f) = 960，offsetY = (1280 - 960) / 2 = 160
        assertEquals("Content height for 720 wide 4:3 should be 960", 960f, area.height, 1f)
        assertEquals("Vertical offset for 720x1280 4:3 should be 160", 160f, area.offsetY, 1f)
    }
}

