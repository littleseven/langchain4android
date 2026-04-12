package com.picme.features.camera

import com.google.mlkit.vision.face.Face
import java.util.concurrent.atomic.AtomicReference

/**
 * 人脸检测结果缓存
 *
 * 用于在预览和拍照之间共享人脸检测数据，减少重复检测导致的效果差异。
 * 这是方案 B 变种的关键组件：预览阶段检测的人脸数据直接用于拍照处理。
 */
object FaceDetectionCache {
    private val cachedFaces = AtomicReference<List<Face>>(emptyList())
    private var lastUpdateTimeMs: Long = 0
    private const val CACHE_VALIDITY_MS = 500L // 缓存有效期 500ms

    /**
     * 更新缓存的人脸数据
     */
    fun updateFaces(faces: List<Face>) {
        cachedFaces.set(faces)
        lastUpdateTimeMs = System.currentTimeMillis()
    }

    /**
     * 获取缓存的人脸数据
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
     * 清除缓存
     */
    fun clear() {
        cachedFaces.set(emptyList())
        lastUpdateTimeMs = 0
    }

    /**
     * 检查缓存是否有效
     */
    fun isValid(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastUpdateTimeMs <= CACHE_VALIDITY_MS &&
                (cachedFaces.get()?.isNotEmpty() == true)
    }
}
