package com.picme.beauty.internal.framesync

import android.os.SystemClock
import com.picme.beauty.api.FrameId
import kotlin.math.abs

/**
 * 轻量级运动跟踪器
 * 基于速度外推的预测算法（Phase 1），后续可替换为 Kalman Filter
 */
class MotionTracker {
    data class FrameState(
        val frameId: FrameId,
        val landmarks106: FloatArray,
        val timestampMs: Long
    )

    private val history = ArrayDeque<FrameState>(3)
    private val historyLock = Any()

    // 预分配可重用缓冲区，避免每帧 GC（CR-P1-1）
    private val predictedBuffer = FloatArray(VERTEX_COUNT * 2)

    fun update(frameId: FrameId, landmarks106: FloatArray) {
        synchronized(historyLock) {
            history.addLast(
                FrameState(
                    frameId = frameId,
                    landmarks106 = landmarks106.clone(),
                    timestampMs = SystemClock.elapsedRealtime()
                )
            )
            if (history.size > 3) {
                history.removeFirst()
            }
        }
    }

    companion object {
        private const val VERTEX_COUNT = 106
    }

    /**
     * 预测目标帧的人脸关键点位置
     * @return 预测后的 FloatArray（内部缓冲区 clone），如果无法预测则返回历史结果的 clone
     */
    fun predict(fromFrameId: FrameId, toFrameId: FrameId, maxRatio: Float): FloatArray {
        synchronized(historyLock) {
            if (history.size < 2) {
                return history.lastOrNull()?.landmarks106?.clone() ?: FloatArray(VERTEX_COUNT * 2)
            }

            val latest = history.last()
            val previous = history[history.size - 2]

            val frameDiff = (latest.frameId.value - previous.frameId.value).coerceAtLeast(1L)
            val targetDiff = (toFrameId.value - fromFrameId.value).coerceAtLeast(0L)

            // 时间因子：基于实际经过的时间计算更准确的预测
            val timeFactor = targetDiff.toFloat() / frameDiff.toFloat()

            // 复用预分配缓冲区，避免每帧 new FloatArray（CR-P1-1）
            for (i in latest.landmarks106.indices) {
                val velocity = (latest.landmarks106[i] - previous.landmarks106[i]) / frameDiff
                val rawPredicted = latest.landmarks106[i] + velocity * targetDiff

                val actualDiff = rawPredicted - latest.landmarks106[i]
                // 放宽限制：允许更大的预测位移以跟上快速移动
                val maxDiff = abs(velocity * frameDiff * maxRatio * 2.0f)
                val clampedDiff = actualDiff.coerceIn(-maxDiff, maxDiff)

                predictedBuffer[i] = latest.landmarks106[i] + clampedDiff
            }

            return predictedBuffer.clone()
        }
    }

    fun clear() {
        synchronized(historyLock) {
            history.clear()
        }
    }
}
