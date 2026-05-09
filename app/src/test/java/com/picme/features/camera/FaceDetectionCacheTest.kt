package com.picme.features.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FaceDetectionCache 缓存逻辑测试
 *
 * 验证时间敏感的缓存行为、defensive copy 和过期逻辑。
 */
class FaceDetectionCacheTest {

    @Test
    fun updateThenGet_returnsSameValues() {
        FaceDetectionCache.clear()
        val landmarks = FloatArray(212) { it / 212f }

        FaceDetectionCache.updateLandmarks106(landmarks)
        val cached = FaceDetectionCache.getCachedLandmarks106()

        assertNotNull("Cached value should not be null", cached)
        assertArrayEquals("Cached values should match original", landmarks, cached, 0.0001f)
    }

    @Test
    fun get_returnsDefensiveCopy() {
        FaceDetectionCache.clear()
        val landmarks = FloatArray(212) { it / 212f }

        FaceDetectionCache.updateLandmarks106(landmarks)
        val cached1 = FaceDetectionCache.getCachedLandmarks106()!!
        val cached2 = FaceDetectionCache.getCachedLandmarks106()!!

        // 修改第一个返回的副本
        cached1[0] = 999f

        // 第二个副本和缓存都应不受影响
        assertEquals("Second copy should not be affected", 0f, cached2[0], 0.0001f)

        val cached3 = FaceDetectionCache.getCachedLandmarks106()!!
        assertEquals("Cache should not be affected by modifying returned copy", 0f, cached3[0], 0.0001f)
    }

    @Test
    fun update_overwritesPreviousValue() {
        FaceDetectionCache.clear()
        val first = FloatArray(212) { 0.1f }
        val second = FloatArray(212) { 0.9f }

        FaceDetectionCache.updateLandmarks106(first)
        FaceDetectionCache.updateLandmarks106(second)

        val cached = FaceDetectionCache.getCachedLandmarks106()
        assertNotNull(cached)
        assertEquals("Cache should contain second value", 0.9f, cached!![0], 0.0001f)
    }

    @Test
    fun get_afterClear_returnsNull() {
        FaceDetectionCache.clear()
        val landmarks = FloatArray(212) { it / 212f }

        FaceDetectionCache.updateLandmarks106(landmarks)
        FaceDetectionCache.clear()

        val cached = FaceDetectionCache.getCachedLandmarks106()
        assertNull("Cache should be null after clear", cached)
    }

    @Test
    fun get_withoutUpdate_returnsNull() {
        FaceDetectionCache.clear()
        val cached = FaceDetectionCache.getCachedLandmarks106()
        assertNull("Cache should be null without update", cached)
    }

    @Test
    fun isValid_afterUpdate_returnsTrue() {
        FaceDetectionCache.clear()
        val landmarks = FloatArray(212) { it / 212f }

        FaceDetectionCache.updateLandmarks106(landmarks)
        assertTrue("Cache should be valid immediately after update", FaceDetectionCache.isValid())
    }

    @Test
    fun isValid_afterClear_returnsFalse() {
        FaceDetectionCache.clear()
        assertFalse("Cache should be invalid after clear", FaceDetectionCache.isValid())
    }

    @Test
    fun isValid_withoutUpdate_returnsFalse() {
        FaceDetectionCache.clear()
        assertFalse("Cache should be invalid without update", FaceDetectionCache.isValid())
    }

    @Test
    fun get_withEmptyArray_returnsValue() {
        FaceDetectionCache.clear()
        val empty = FloatArray(0)

        FaceDetectionCache.updateLandmarks106(empty)
        val cached = FaceDetectionCache.getCachedLandmarks106()

        assertNotNull("Cache should store even empty array", cached)
        assertEquals("Cached array should be empty", 0, cached!!.size)
    }

    @Test
    fun update_copiesInputArray() {
        FaceDetectionCache.clear()
        val landmarks = FloatArray(212) { it / 212f }

        FaceDetectionCache.updateLandmarks106(landmarks)
        landmarks[0] = 999f // 修改原始数组

        val cached = FaceDetectionCache.getCachedLandmarks106()!!
        assertEquals("Cache should contain copied value, not reference", 0f, cached[0], 0.0001f)
    }

    @Test
    fun isValid_afterUpdateThenClearThenUpdate_returnsTrue() {
        FaceDetectionCache.clear()
        FaceDetectionCache.updateLandmarks106(FloatArray(212) { 0.1f })
        FaceDetectionCache.clear()
        FaceDetectionCache.updateLandmarks106(FloatArray(212) { 0.2f })

        assertTrue("Cache should be valid after re-update", FaceDetectionCache.isValid())
        val cached = FaceDetectionCache.getCachedLandmarks106()
        assertNotNull(cached)
        assertEquals("Cache should contain latest value", 0.2f, cached!![0], 0.0001f)
    }

    @Test
    fun clear_isIdempotent() {
        FaceDetectionCache.clear()
        FaceDetectionCache.clear() // 不应抛异常
        FaceDetectionCache.clear()

        assertFalse("Cache should remain invalid", FaceDetectionCache.isValid())
        assertNull("Cache should remain null", FaceDetectionCache.getCachedLandmarks106())
    }

    @Test
    fun multipleSequentialUpdates_allValid() {
        FaceDetectionCache.clear()

        for (i in 0..5) {
            FaceDetectionCache.updateLandmarks106(FloatArray(212) { i / 10f })
            assertTrue("Cache should be valid after update $i", FaceDetectionCache.isValid())
            val cached = FaceDetectionCache.getCachedLandmarks106()!!
            assertEquals("Cache should contain value $i", i / 10f, cached[0], 0.0001f)
        }
    }
}
