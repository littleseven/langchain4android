package com.picme.beauty.internal.facedetect.adapter

import android.os.SystemClock
import android.util.Log
import com.picme.beauty.api.facedetect.FaceDetectionSource

/**
 * MNN Landmark 适配器（Identity Adapter）
 *
 * MNN 检测器输出已经是统一的 106 点格式，无需重排映射。
 * 仅需处理前置摄像头的水平镜像。
 */
class MnnLandmarkAdapter : FaceLandmarkAdapter {

    override val detectionSource: FaceDetectionSource = FaceDetectionSource.MNN

    companion object {
        private const val POINT_COUNT = 106
        private const val LENS_FACING_FRONT = 0
    }

    override fun adapt(nativeLandmarks: FloatArray, lensFacing: Int): Result<FloatArray> {
        if (nativeLandmarks.size < POINT_COUNT * 2) {
            return Result.failure(
                IllegalArgumentException(
                    "MNN landmarks size ${nativeLandmarks.size} < required ${POINT_COUNT * 2}"
                )
            )
        }

        val startTime = SystemClock.elapsedRealtime()
        val isFrontCamera = lensFacing == LENS_FACING_FRONT
        val unified = FloatArray(POINT_COUNT * 2)

        // Identity mapping - MNN 输出已经是统一 106 格式
        System.arraycopy(nativeLandmarks, 0, unified, 0, nativeLandmarks.size)

        // 前置摄像头需要水平镜像
        if (isFrontCamera) {
            for (i in 0 until POINT_COUNT) {
                unified[i * 2] = 1f - unified[i * 2]
            }
        }

        val elapsed = SystemClock.elapsedRealtime() - startTime
        Log.d("PicMe:MnnLandmarkAdapter", "[Perf] adapt DONE: ${elapsed}ms (identity)")

        return Result.success(unified)
    }
}
