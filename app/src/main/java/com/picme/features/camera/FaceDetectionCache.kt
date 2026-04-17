package com.picme.features.camera

import com.google.mlkit.vision.face.Face
import java.util.concurrent.atomic.AtomicReference

/**
 * 人脸检测结果缓存
 *
 * 用于在预览和拍照之间共享人脸检测数据，减少重复检测导致的效果差异。
 * 支持两种模式：
 * 1. ML Kit 133点模式（GPUPixel 模式使用）
 * 2. MediaPipe 106点模式（大美丽模式使用）
 */
object FaceDetectionCache {
    private val cachedFaces = AtomicReference<List<Face>>(emptyList())
    private val cachedLandmarks106 = AtomicReference<FloatArray?>(null)
    private var lastUpdateTimeMs: Long = 0
    private const val CACHE_VALIDITY_MS = 500L // 缓存有效期 500ms

    /**
     * 更新 ML Kit 人脸数据缓存（GPUPixel 模式使用）
     */
    fun updateFaces(faces: List<Face>) {
        cachedFaces.set(faces)
        lastUpdateTimeMs = System.currentTimeMillis()
    }

    /**
     * 更新 MediaPipe 106 点数据缓存（大美丽模式使用）
     */
    fun updateLandmarks106(landmarks: FloatArray) {
        cachedLandmarks106.set(landmarks.copyOf())
        lastUpdateTimeMs = System.currentTimeMillis()
    }

    /**
     * 获取缓存的 ML Kit 人脸数据
     * @return 如果缓存有效则返回人脸列表，否则返回空列表
     */
    fun getCachedFaces(): List<Face> {
        val now = System.currentTimeMillis()
        return if (now - lastUpdateTimeMs <= CACHE_VALIDITY_MS) {
            cachedFaces.get() ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * 获取缓存的 MediaPipe 106 点数据
     * @return 如果缓存有效则返回 106 点 FloatArray，否则返回 null
     */
    fun getCachedLandmarks106(): FloatArray? {
        val now = System.currentTimeMillis()
        return if (now - lastUpdateTimeMs <= CACHE_VALIDITY_MS) {
            cachedLandmarks106.get()?.copyOf()
        } else {
            null
        }
    }

    /**
     * 清除缓存
     */
    fun clear() {
        cachedFaces.set(emptyList())
        cachedLandmarks106.set(null)
        lastUpdateTimeMs = 0
    }

    /**
     * 检查缓存是否有效
     */
    fun isValid(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastUpdateTimeMs <= CACHE_VALIDITY_MS &&
                ((cachedFaces.get()?.isNotEmpty() == true) || cachedLandmarks106.get() != null)
    }
}
