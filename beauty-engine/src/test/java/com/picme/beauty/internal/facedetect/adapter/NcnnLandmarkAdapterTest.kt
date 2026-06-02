package com.picme.beauty.internal.facedetect.adapter

import com.picme.beauty.api.facedetect.FaceDetectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NcnnLandmarkAdapter remap 表和坐标转换测试
 *
 * 验证 NCNN 原始 106 点 → 统一 106 标准的映射正确性，
 * 以及前置/后置摄像头的镜像处理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NcnnLandmarkAdapterTest {

    private val adapter = NcnnLandmarkAdapter()

    @Test
    fun detectionSource_isNcnn() {
        assertEquals(FaceDetectionSource.NCNN, adapter.detectionSource)
    }

    /**
     * 验证 remap 表的 identity 映射情况。
     *
     * 当前 remap 表中有 2 个已知的 identity 映射（unifiedIdx=28→ncnnIdx=28，
     * unifiedIdx=83→ncnnIdx=83），这是从视觉检查中手动建立映射表时的遗留。
     *
     * 此测试记录这些已知情况，如果 identity 映射数量增加，则表明 remap 表被错误修改。
     */
    @Test
    fun fullRemap_identityMappingsAreKnownAndStable() {
        // 构造输入：每个点的 x = ncnnIdx, y = 0
        val native1 = FloatArray(106 * 2) { idx ->
            val pointIdx = idx / 2
            if (idx % 2 == 0) pointIdx.toFloat() else 0f
        }

        val result = adapter.adapt(native1, lensFacing = 1)
        assertTrue(result.isSuccess)
        val unified = result.getOrThrow()

        val identityMappings = mutableListOf<Int>()
        for (unifiedIdx in 0 until 106) {
            val outputX = unified[unifiedIdx * 2]
            if (kotlin.math.abs(outputX - unifiedIdx) < 0.001f) {
                identityMappings.add(unifiedIdx)
            }
        }

        // 使用第二组输入交叉验证（offset 1000）
        val native2 = FloatArray(106 * 2) { idx ->
            val pointIdx = idx / 2
            if (idx % 2 == 0) (pointIdx + 1000).toFloat() else 0f
        }
        val result2 = adapter.adapt(native2, lensFacing = 1)
        val unified2 = result2.getOrThrow()

        val identityMappings2 = mutableListOf<Int>()
        for (unifiedIdx in 0 until 106) {
            val outputX = unified2[unifiedIdx * 2]
            if (kotlin.math.abs(outputX - (unifiedIdx + 1000)) < 0.001f) {
                identityMappings2.add(unifiedIdx)
            }
        }

        // 两组输入应发现相同的 identity 映射位置
        assertEquals(
            "Identity mappings should be consistent across different inputs",
            identityMappings,
            identityMappings2
        )

        // 记录当前已知的 identity 映射数量（2 个）
        // 如果数量变化，说明 remap 表被修改，需要人工复核
        assertEquals(
            "Known identity mappings in FULL_REMAP: unifiedIdx=28→28, unifiedIdx=83→83. " +
                "If count changed, remap table may have been incorrectly modified. " +
                "Found identity mappings at: $identityMappings",
            2,
            identityMappings.size
        )
    }

    @Test
    fun adapt_backCamera_preservesXCoordinate() {
        // 构造 106 个唯一点，每个点的 x = ncnnIdx, y = ncnnIdx
        val native = FloatArray(106 * 2) { idx ->
            val pointIdx = idx / 2
            pointIdx.toFloat()
        }

        val result = adapter.adapt(native, lensFacing = 1) // BACK = 1

        assertTrue("Result should be success", result.isSuccess)
        val unified = result.getOrThrow()

        // 验证：对于每个 unifiedIdx，输出中的 x 值应该出现在输入中（即等于某个 ncnnIdx）
        // 并且每个输入的 x 值应该恰好出现一次
        val outputXValues = (0 until 106).map { unified[it * 2] }.toSortedSet()
        val expectedXValues = (0 until 106).map { it.toFloat() }.toSortedSet()
        assertEquals(
            "Back camera: all output x values should be a permutation of input x values",
            expectedXValues,
            outputXValues
        )

        // y 坐标也应该保持不变
        val outputYValues = (0 until 106).map { unified[it * 2 + 1] }.toSortedSet()
        val expectedYValues = (0 until 106).map { it.toFloat() }.toSortedSet()
        assertEquals(
            "Back camera: y coordinates should be preserved",
            expectedYValues,
            outputYValues
        )
    }

    @Test
    fun adapt_frontCamera_mirrorsXCoordinate() {
        // 构造输入：x = ncnnIdx / 106.0（归一化到 0-1）
        val native = FloatArray(106 * 2) { idx ->
            val pointIdx = idx / 2
            if (idx % 2 == 0) pointIdx / 106f else pointIdx / 106f
        }

        val result = adapter.adapt(native, lensFacing = 0) // FRONT = 0

        assertTrue("Result should be success", result.isSuccess)
        val unified = result.getOrThrow()

        // 验证：每个输出 x 都是某个 (1 - inputX) 的值
        val expectedMirroredX = (0 until 106).map { 1f - it / 106f }.toSortedSet()
        val outputXValues = (0 until 106).map { unified[it * 2] }.toSortedSet()
        assertEquals(
            "Front camera: x coordinates should be mirrored (1 - x)",
            expectedMirroredX,
            outputXValues
        )
    }

    @Test
    fun adapt_frontCamera_yCoordinateUnchanged() {
        val native = FloatArray(106 * 2) { idx ->
            val pointIdx = idx / 2
            if (idx % 2 == 0) pointIdx / 106f else pointIdx / 106f
        }

        val result = adapter.adapt(native, lensFacing = 0)
        val unified = result.getOrThrow()

        // y 坐标应该和后置摄像头一样（未改变）
        val expectedY = (0 until 106).map { it / 106f }.toSortedSet()
        val outputYValues = (0 until 106).map { unified[it * 2 + 1] }.toSortedSet()
        assertEquals(
            "Front camera: y coordinate should not be mirrored",
            expectedY,
            outputYValues
        )
    }

    @Test
    fun adapt_inputTooSmall_returnsFailure() {
        val native = FloatArray(100) // 远小于 212
        val result = adapter.adapt(native, lensFacing = 1)
        assertFalse("Result should be failure for undersized input", result.isSuccess)
    }

    @Test
    fun adapt_resultHasCorrectSize() {
        val native = FloatArray(106 * 2) { it / 212f }
        val result = adapter.adapt(native, lensFacing = 1)
        assertTrue(result.isSuccess)
        assertEquals("Output should have exactly 212 floats", 212, result.getOrThrow().size)
    }

    @Test
    fun adapt_exactSizeInput_succeeds() {
        val native = FloatArray(212) { it / 212f }
        val result = adapter.adapt(native, lensFacing = 1)
        assertTrue("Result should be success for exactly-sized input", result.isSuccess)
    }

    @Test
    fun adapt_largerThanMinimumInput_succeeds() {
        val native = FloatArray(300) { it / 300f }
        val result = adapter.adapt(native, lensFacing = 1)
        assertTrue("Result should be success for oversized input", result.isSuccess)
    }

    @Test
    fun adapt_remapProducesUniqueMapping() {
        // 构造两个输入，只有第 42 个 ncnnIdx 不同
        val native1 = FloatArray(106 * 2) { 0.5f }
        native1[42 * 2] = 0.1f
        native1[42 * 2 + 1] = 0.2f

        val native2 = FloatArray(106 * 2) { 0.5f }
        native2[42 * 2] = 0.9f
        native2[42 * 2 + 1] = 0.8f

        val result1 = adapter.adapt(native1, lensFacing = 1)
        val result2 = adapter.adapt(native2, lensFacing = 1)
        val unified1 = result1.getOrThrow()
        val unified2 = result2.getOrThrow()

        // 找到两个输出中不同的 unifiedIdx
        var diffCount = 0
        for (unifiedIdx in 0 until 106) {
            if (unified1[unifiedIdx * 2] != unified2[unifiedIdx * 2] ||
                unified1[unifiedIdx * 2 + 1] != unified2[unifiedIdx * 2 + 1]) {
                diffCount++
            }
        }

        // 只有一个 ncnnIdx 不同，所以应该恰好有一个 unifiedIdx 不同
        // 但由于前置/后置选择不影响这个测试（都是后置），所以 diffCount 应该为 1
        assertEquals(
            "Only one ncnn point changed, so exactly one unified point should differ",
            1,
            diffCount
        )
    }

    @Test
    fun adapt_zeroValues_preserved() {
        // 全零输入，验证后置摄像头输出也是全零
        val native = FloatArray(212) { 0f }
        val result = adapter.adapt(native, lensFacing = 1)
        assertTrue(result.isSuccess)
        val unified = result.getOrThrow()
        for (i in unified.indices) {
            val actual: Float = unified[i]
            assertEquals("Zero input should produce zero output at index $i", 0.0f, actual, 0.0001f)
        }
    }

    @Test
    fun adapt_zeroValues_frontCamera_producesOnesForX() {
        // 全零输入，前置摄像头 x 应变为 1.0 (1 - 0)
        val native = FloatArray(212) { 0f }
        val result = adapter.adapt(native, lensFacing = 0)
        assertTrue(result.isSuccess)
        val unified = result.getOrThrow()
        for (unifiedIdx in 0 until 106) {
            val actualX: Float = unified[unifiedIdx * 2]
            val actualY: Float = unified[unifiedIdx * 2 + 1]
            assertEquals("Front camera: x should be 1.0 at unifiedIdx=$unifiedIdx", 1.0f, actualX, 0.0001f)
            assertEquals("Front camera: y should be 0.0 at unifiedIdx=$unifiedIdx", 0.0f, actualY, 0.0001f)
        }
    }
}
