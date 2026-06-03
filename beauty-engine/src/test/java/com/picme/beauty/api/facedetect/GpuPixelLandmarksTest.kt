package com.picme.beauty.api.facedetect

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GpuPixelLandmarks 数据转换测试
 *
 * 验证 FloatArray → GpuPixelLandmarks 的转换正确性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpuPixelLandmarksTest {

    @Test
    fun defaultInstance_hasNoFace() {
        val defaults = GpuPixelLandmarks()
        assertFalse("Default hasFace should be false", defaults.hasFace)
        assertEquals("Default points should be empty", 0, defaults.points.size)
        assertEquals("Default rawPoints should be empty", 0, defaults.rawPoints.size)
    }

    @Test
    fun fromFloatArray_extractsFirst106Points() {
        val floats = FloatArray(106 * 2) { idx ->
            val pointIdx = idx / 2
            if (idx % 2 == 0) pointIdx / 106f else pointIdx / 106f
        }

        val result = GpuPixelLandmarks.fromFloatArray(floats)

        assertTrue("hasFace should be true", result.hasFace)
        assertEquals("Should extract exactly 106 points", 106, result.rawPoints.size / 2)

        // 验证前几个点的坐标
        assertEquals("Point 0 x", 0f / 106f, result.rawPoints[0], 0.0001f)
        assertEquals("Point 0 y", 0f / 106f, result.rawPoints[1], 0.0001f)
        assertEquals("Point 1 x", 1f / 106f, result.rawPoints[2], 0.0001f)
        assertEquals("Point 1 y", 1f / 106f, result.rawPoints[3], 0.0001f)
    }

    @Test
    fun fromFloatArray_ignoresExtraPoints() {
        val floats = FloatArray(150 * 2) { idx ->
            val pointIdx = idx / 2
            if (idx % 2 == 0) pointIdx / 150f else pointIdx / 150f
        }

        val result = GpuPixelLandmarks.fromFloatArray(floats)

        assertTrue("hasFace should be true", result.hasFace)
        assertEquals("Should extract only 106 points", 106, result.rawPoints.size / 2)
    }

    @Test
    fun fromFloatArray_nullInput_returnsEmpty() {
        val result = GpuPixelLandmarks.fromFloatArray(null)
        assertFalse("hasFace should be false for null", result.hasFace)
        assertEquals("points should be empty for null", 0, result.points.size)
    }

    @Test
    fun fromFloatArray_emptyInput_returnsEmpty() {
        val result = GpuPixelLandmarks.fromFloatArray(FloatArray(0))
        assertFalse("hasFace should be false for empty", result.hasFace)
        assertEquals("points should be empty for empty", 0, result.points.size)
    }

    @Test
    fun fromFloatArray_partialInput_extractsAvailablePoints() {
        // 只提供 50 个点（100 个 float）
        val floats = FloatArray(50 * 2) { idx ->
            val pointIdx = idx / 2
            if (idx % 2 == 0) pointIdx / 50f else pointIdx / 50f
        }

        val result = GpuPixelLandmarks.fromFloatArray(floats)

        assertTrue("hasFace should be true", result.hasFace)
        assertEquals("Should extract 50 available points", 50, result.rawPoints.size / 2)
    }

    @Test
    fun fromFloatArray_oddLength_ignoresLastFloat() {
        // 提供 213 个 float（106.5 个点），应只取前 106 个点
        val floats = FloatArray(213) { it / 213f }

        val result = GpuPixelLandmarks.fromFloatArray(floats)

        assertTrue("hasFace should be true", result.hasFace)
        assertEquals("Should extract 106 complete points", 106, result.rawPoints.size / 2)
    }

    @Test
    fun fromFloatArray_setsHasFaceTrue() {
        val floats = FloatArray(10 * 2) { it / 20f }
        val result = GpuPixelLandmarks.fromFloatArray(floats)
        assertTrue("hasFace should be true for valid input", result.hasFace)
    }

    @Test
    fun fromFloatArray_singlePoint() {
        val floats = floatArrayOf(0.5f, 0.6f)
        val result = GpuPixelLandmarks.fromFloatArray(floats)

        assertTrue("hasFace should be true", result.hasFace)
        assertEquals("Should have 1 point", 1, result.rawPoints.size / 2)
        assertEquals("Point x", 0.5f, result.rawPoints[0], 0.0001f)
        assertEquals("Point y", 0.6f, result.rawPoints[1], 0.0001f)
    }

    @Test
    fun fromFloatArray_preservesCoordinateValues() {
        // 构造特定坐标的输入
        val floats = FloatArray(106 * 2)
        for (i in 0 until 106) {
            floats[i * 2] = i * 0.001f
            floats[i * 2 + 1] = i * 0.002f
        }

        val result = GpuPixelLandmarks.fromFloatArray(floats)

        for (i in 0 until 106) {
            assertEquals("Point $i x", i * 0.001f, result.rawPoints[i * 2], 0.0001f)
            assertEquals("Point $i y", i * 0.002f, result.rawPoints[i * 2 + 1], 0.0001f)
        }
    }

    @Test
    fun fromFloatArray_zeroCoordinates() {
        val floats = FloatArray(106 * 2) { 0f }
        val result = GpuPixelLandmarks.fromFloatArray(floats)

        assertTrue("hasFace should be true", result.hasFace)
        for (i in 0 until 106) {
            assertEquals("Point $i x should be 0", 0f, result.rawPoints[i * 2], 0.0001f)
            assertEquals("Point $i y should be 0", 0f, result.rawPoints[i * 2 + 1], 0.0001f)
        }
    }
}
