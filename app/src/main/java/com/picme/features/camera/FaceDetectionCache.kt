package com.picme.features.camera

import java.util.concurrent.atomic.AtomicReference

/**
 * 人脸检测结果缓存
 *
 * 用于在预览和拍照之间共享人脸检测数据，减少重复检测导致的效果差异。
 * 使用 MediaPipe 106 点格式。
 */
object FaceDetectionCache {
    private val cachedLandmarks106 = AtomicReference<FloatArray?>(null)
    private val lastUpdateTimeMs = java.util.concurrent.atomic.AtomicLong(0L)
    private const val CACHE_VALIDITY_MS = 500L // 缓存有效期 500ms

    /**
     * 更新 MediaPipe 106 点数据缓存
     */
    fun updateLandmarks106(landmarks: FloatArray) {
        cachedLandmarks106.set(landmarks.copyOf())
        lastUpdateTimeMs.set(System.currentTimeMillis())
    }

    /**
     * 获取缓存的 MediaPipe 106 点数据
     * @return 如果缓存有效则返回 106 点 FloatArray，否则返回 null
     */
    fun getCachedLandmarks106(): FloatArray? {
        val now = System.currentTimeMillis()
        return if (now - lastUpdateTimeMs.get() <= CACHE_VALIDITY_MS) {
            cachedLandmarks106.get()?.copyOf()
        } else {
            null
        }
    }

    /**
     * 清除缓存
     */
    fun clear() {
        cachedLandmarks106.set(null)
        lastUpdateTimeMs.set(0L)
    }

    /**
     * 检查缓存是否有效
     */
    fun isValid(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastUpdateTimeMs.get() <= CACHE_VALIDITY_MS &&
                cachedLandmarks106.get() != null
    }
}
