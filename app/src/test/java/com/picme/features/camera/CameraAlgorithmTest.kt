package com.picme.features.camera

import com.picme.beauty.api.BeautySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.camera.core.CameraSelector

/**
 * [QA] 相机算法纯函数单元测试
 *
 * 整合内容（原 CameraFrameAnalyzerTest + AspectRatioAndFaceWarpTest 比例常量部分）：
 *
 * 1. [averagePoint]                - 坐标列表均值计算
 * 2. [resampleContourPoints]       - 轮廓点等间距重采样
 * 3. [resolveNextBeautySettings]   - 美颜参数启用/禁用自动联动（CameraScreenActions）
 * 4. [nextLensFacing]              - 前/后置摄像头切换（CameraScreenActions）
 * 5. [toCameraAspectRatio]         - 内部比例常量 → CameraX 比例常量映射
 * 6. AspectRatio / CameraAspectRatio 常量唯一性与约定值
 *
 * 注意：
 * - PointF 在 JVM 单元测试中 .x/.y 始终为 0（Android Stub），
 *   因此 averagePoint / resampleContourPoints 测试改用内部 Pt 数据类建模。
 * - 算法逻辑与 CameraFrameAnalyzer.kt 私有实现保持同构，生产代码改动须同步此处。
 */
class CameraAlgorithmTest {

    // ================================================================
    // 辅助数据类型（JVM 兼容替代 PointF）
    // ================================================================

    private data class Pt(val x: Float, val y: Float)

    private val LIP_CONTOUR_POINT_COUNT = 20

    // ================================================================
    // averagePoint 等效实现
    // ================================================================

    private fun averagePoint(points: List<Pt>): Pt? {
        if (points.isEmpty()) return null
        val sumX = points.sumOf { it.x.toDouble() }.toFloat()
        val sumY = points.sumOf { it.y.toDouble() }.toFloat()
        return Pt(sumX / points.size, sumY / points.size)
    }

    // ================================================================
    // resampleContourPoints 等效实现
    // ================================================================

    private fun resampleContourPoints(points: List<Pt>): List<Pt> {
        if (points.size < 2 || LIP_CONTOUR_POINT_COUNT <= 1) return points

        val source = points + points.first()

        val cumulative = ArrayList<Float>(source.size)
        cumulative.add(0f)
        for (index in 1 until source.size) {
            val dx = source[index].x - source[index - 1].x
            val dy = source[index].y - source[index - 1].y
            cumulative.add(cumulative.last() + kotlin.math.sqrt(dx * dx + dy * dy))
        }

        val totalLength = cumulative.last()
        if (totalLength <= 0.0001f) {
            return List(LIP_CONTOUR_POINT_COUNT) { points.first() }
        }

        val step = totalLength / LIP_CONTOUR_POINT_COUNT
        val result = ArrayList<Pt>(LIP_CONTOUR_POINT_COUNT)
        var segmentIndex = 1

        for (sampleIndex in 0 until LIP_CONTOUR_POINT_COUNT) {
            val targetDistance = sampleIndex * step
            while (segmentIndex < cumulative.size - 1 && cumulative[segmentIndex] < targetDistance) {
                segmentIndex++
            }
            val prevDistance = cumulative[segmentIndex - 1]
            val nextDistance = cumulative[segmentIndex]
            val range = (nextDistance - prevDistance).coerceAtLeast(0.0001f)
            val t = ((targetDistance - prevDistance) / range).coerceIn(0f, 1f)
            val start = source[segmentIndex - 1]
            val end = source[segmentIndex]
            result.add(Pt(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t))
        }
        return result
    }

    // ================================================================
    // §1 averagePoint() 测试
    // ================================================================

    @Test
    fun `averagePoint - empty list returns null`() {
        assertTrue("Empty list should return null", averagePoint(emptyList()) == null)
    }

    @Test
    fun `averagePoint - single point returns that point`() {
        val result = averagePoint(listOf(Pt(3f, 7f)))!!
        assertEquals("X should equal single point", 3f, result.x, 0.001f)
        assertEquals("Y should equal single point", 7f, result.y, 0.001f)
    }

    @Test
    fun `averagePoint - two symmetric points returns center`() {
        val result = averagePoint(listOf(Pt(-4f, 0f), Pt(4f, 0f)))!!
        assertEquals("X average should be 0", 0f, result.x, 0.001f)
        assertEquals("Y average should be 0", 0f, result.y, 0.001f)
    }

    @Test
    fun `averagePoint - three points computes correct mean`() {
        val result = averagePoint(listOf(Pt(0f, 0f), Pt(3f, 6f), Pt(6f, 3f)))!!
        assertEquals("X mean should be 3", 3f, result.x, 0.001f)
        assertEquals("Y mean should be 3", 3f, result.y, 0.001f)
    }

    @Test
    fun `averagePoint - all identical points returns that point`() {
        val result = averagePoint(List(10) { Pt(5f, 5f) })!!
        assertEquals("X should be 5", 5f, result.x, 0.001f)
        assertEquals("Y should be 5", 5f, result.y, 0.001f)
    }

    @Test
    fun `averagePoint - large dataset maintains precision`() {
        val count = 1000
        val points = (0 until count).map { i -> Pt(i.toFloat(), i.toFloat()) }
        val result = averagePoint(points)!!
        val expected = (count - 1) / 2f  // 0..999 平均 = 499.5
        assertEquals("X mean should be $expected", expected, result.x, 0.1f)
        assertEquals("Y mean should be $expected", expected, result.y, 0.1f)
    }

    @Test
    fun `averagePoint - negative coordinates handled correctly`() {
        val result = averagePoint(listOf(Pt(-10f, -20f), Pt(10f, 20f)))!!
        assertEquals("X average of symmetric negatives should be 0", 0f, result.x, 0.001f)
        assertEquals("Y average of symmetric negatives should be 0", 0f, result.y, 0.001f)
    }

    // ================================================================
    // §2 resampleContourPoints() 测试
    // ================================================================

    @Test
    fun `resampleContourPoints - single point returns original (too few to resample)`() {
        val points = listOf(Pt(1f, 1f))
        assertEquals("Single point list should not be resampled", points, resampleContourPoints(points))
    }

    @Test
    fun `resampleContourPoints - result always has LIP_CONTOUR_POINT_COUNT points`() {
        val square = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f))
        assertEquals(LIP_CONTOUR_POINT_COUNT, resampleContourPoints(square).size)
    }

    @Test
    fun `resampleContourPoints - Y values are zero on horizontal input line`() {
        val linePoints = (0..10).map { i -> Pt(i * 10f, 0f) }
        val result = resampleContourPoints(linePoints)

        assertTrue("Result should not be empty", result.isNotEmpty())
        result.forEach { point ->
            assertTrue("Point X should be in [0, 100]", point.x >= 0f && point.x <= 100.1f)
            assertEquals("Point Y should be 0 on horizontal line", 0f, point.y, 0.001f)
        }
    }

    @Test
    fun `resampleContourPoints - degenerate case (all same points) returns LIP_CONTOUR_POINT_COUNT repeated points`() {
        val degenerate = List(5) { Pt(42f, 42f) }
        val result = resampleContourPoints(degenerate)
        assertEquals(LIP_CONTOUR_POINT_COUNT, result.size)
        result.forEach { point ->
            assertEquals("Degenerate X should be 42", 42f, point.x, 0.001f)
            assertEquals("Degenerate Y should be 42", 42f, point.y, 0.001f)
        }
    }

    @Test
    fun `resampleContourPoints - first sample starts at first input point`() {
        val square = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f))
        val result = resampleContourPoints(square)
        assertEquals("First resampled X should start at 0", 0f, result.first().x, 0.001f)
        assertEquals("First resampled Y should start at 0", 0f, result.first().y, 0.001f)
    }

    @Test
    fun `resampleContourPoints - circular contour produces points near original circle`() {
        val n = 36
        val radius = 100f
        val circle = (0 until n).map { i ->
            val angle = i * 2 * Math.PI / n
            Pt((radius * kotlin.math.cos(angle)).toFloat(), (radius * kotlin.math.sin(angle)).toFloat())
        }
        val result = resampleContourPoints(circle)
        assertEquals(LIP_CONTOUR_POINT_COUNT, result.size)
        result.forEach { point ->
            val distFromCenter = kotlin.math.sqrt((point.x * point.x + point.y * point.y).toDouble()).toFloat()
            assertEquals("Resampled point should be on circle", radius, distFromCenter, 5f)
        }
    }

    @Test
    fun `resampleContourPoints - square contour has all points on boundary`() {
        val side = 100f
        val square = listOf(Pt(0f, 0f), Pt(side, 0f), Pt(side, side), Pt(0f, side))
        val result = resampleContourPoints(square)
        result.forEach { point ->
            val onBoundary = point.x <= 0.5f || point.y <= 0.5f ||
                point.x >= side - 0.5f || point.y >= side - 0.5f
            assertTrue("Resampled point (${point.x}, ${point.y}) should be on square boundary", onBoundary)
        }
    }

    // ================================================================
    // §3 resolveNextBeautySettings() 测试
    // ================================================================

    @Test
    fun `resolveNextBeautySettings - only toggle changed returns updated as-is`() {
        val current = BeautySettings(enabled = false, smoothing = 50f)
        val updated = current.copy(enabled = true)
        val result = resolveNextBeautySettings(current, updated)
        assertTrue("Only toggle changed: enabled should be true", result.enabled)
        assertEquals("Smoothing should not change", 50f, result.smoothing, 0.001f)
    }

    @Test
    fun `resolveNextBeautySettings - any effect changed and non-zero auto enables`() {
        val current = BeautySettings(enabled = false, smoothing = 0f)
        val updated = current.copy(smoothing = 50f)
        val result = resolveNextBeautySettings(current, updated)
        assertTrue("Should auto-enable when effect is set", result.enabled)
        assertEquals("Smoothing should be 50", 50f, result.smoothing, 0.001f)
    }

    @Test
    fun `resolveNextBeautySettings - all effects zero auto disables`() {
        val current = BeautySettings(enabled = true, smoothing = 50f)
        val updated = BeautySettings(enabled = true, smoothing = 0f, lipColor = 0f, blush = 0f, eyebrow = 0f)
        val result = resolveNextBeautySettings(current, updated)
        assertFalse("Should auto-disable when all effects are zero", result.enabled)
    }

    @Test
    fun `resolveNextBeautySettings - toggle off with effects just disables enabled flag`() {
        val current = BeautySettings(enabled = true, smoothing = 50f)
        val updated = current.copy(enabled = false)
        val result = resolveNextBeautySettings(current, updated)
        assertFalse("Should be disabled", result.enabled)
        assertEquals("Smoothing should be preserved", 50f, result.smoothing, 0.001f)
    }

    // ================================================================
    // §4 nextLensFacing() 测试
    // ================================================================

    @Test
    fun `nextLensFacing - from back returns front`() {
        val result = nextLensFacing(CameraSelector.LENS_FACING_BACK)
        assertEquals("From BACK should return FRONT",
            CameraSelector.LENS_FACING_FRONT, result)
    }

    @Test
    fun `nextLensFacing - from front returns back`() {
        val result = nextLensFacing(CameraSelector.LENS_FACING_FRONT)
        assertEquals("From FRONT should return BACK",
            CameraSelector.LENS_FACING_BACK, result)
    }

    @Test
    fun `nextLensFacing - toggled twice returns original`() {
        val original = CameraSelector.LENS_FACING_BACK
        assertEquals("Double toggle should return original", original, nextLensFacing(nextLensFacing(original)))
    }

    // ================================================================
    // §5 toCameraAspectRatio() 测试
    // ================================================================

    @Test
    fun `toCameraAspectRatio - RATIO_4_3 maps to CameraX RATIO_4_3`() {
        assertEquals(AspectRatio.RATIO_4_3, toCameraAspectRatio(AspectRatio.RATIO_4_3))
    }

    @Test
    fun `toCameraAspectRatio - RATIO_16_9 maps to CameraX RATIO_16_9`() {
        assertEquals(AspectRatio.RATIO_16_9, toCameraAspectRatio(AspectRatio.RATIO_16_9))
    }

    @Test
    fun `toCameraAspectRatio - RATIO_FULL maps to CameraX RATIO_16_9`() {
        assertEquals(AspectRatio.RATIO_16_9, toCameraAspectRatio(AspectRatio.RATIO_FULL))
    }

    @Test
    fun `toCameraAspectRatio - unknown ratio falls back to RATIO_4_3`() {
        assertEquals(AspectRatio.RATIO_4_3, toCameraAspectRatio(999))
    }

    // ================================================================
    // §6 AspectRatio / CameraAspectRatio 常量测试（原 AspectRatioAndFaceWarpTest）
    // ================================================================

    @Test
    fun `AspectRatio constants have distinct values`() {
        val values = setOf(AspectRatio.RATIO_4_3, AspectRatio.RATIO_16_9, AspectRatio.RATIO_FULL)
        assertEquals("All AspectRatio constants should be distinct", 3, values.size)
    }

    @Test
    fun `AspectRatio RATIO_4_3 is 0`() {
        assertEquals("RATIO_4_3 should be 0", 0, AspectRatio.RATIO_4_3)
    }

    @Test
    fun `AspectRatio RATIO_16_9 is 1`() {
        assertEquals("RATIO_16_9 should be 1", 1, AspectRatio.RATIO_16_9)
    }

    @Test
    fun `AspectRatio RATIO_FULL is 2`() {
        assertEquals("RATIO_FULL should be 2", 2, AspectRatio.RATIO_FULL)
    }

    @Test
    fun `CameraAspectRatio enum has three values covering all ratios`() {
        val values = CameraAspectRatio.values()
        assertEquals("CameraAspectRatio should have 3 values", 3, values.size)
        assertTrue(values.contains(CameraAspectRatio.RATIO_4_3))
        assertTrue(values.contains(CameraAspectRatio.RATIO_16_9))
        assertTrue(values.contains(CameraAspectRatio.RATIO_FULL))
    }

    @Test
    fun `CameraAspectRatio names match expected strings`() {
        assertEquals("RATIO_4_3", CameraAspectRatio.RATIO_4_3.name)
        assertEquals("RATIO_16_9", CameraAspectRatio.RATIO_16_9.name)
        assertEquals("RATIO_FULL", CameraAspectRatio.RATIO_FULL.name)
    }
}

