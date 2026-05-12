package com.picme.beauty.internal.framesync

import com.picme.beauty.api.FrameId
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 帧同步桥接器
 * 线程安全地共享分析线程生成的最新 FrameId 给渲染线程。
 *
 * 解决 CR-P0-1：FrameId 来源不一致问题。
 * - 分析线程（CameraFrameAnalyzer）在生成检测任务时设置 latestFrameId
 * - 渲染线程（CameraPreviewRenderer）读取该 FrameId 用于 query
 * - 确保检测-渲染链路使用同一套 FrameId 序列
 */
object FrameSyncBridge {
    private val latestFrameIdRef = AtomicReference(FrameId.INVALID)
    private val latestTimestampNsRef = AtomicLong(0L)

    /**
     * 设置当前分析帧的 FrameId（由分析线程调用）
     */
    fun setLatestFrameId(frameId: FrameId, timestampNs: Long = 0L) {
        latestFrameIdRef.set(frameId)
        latestTimestampNsRef.set(timestampNs)
    }

    /**
     * 获取最新分析帧的 FrameId（由渲染线程调用）
     */
    fun getLatestFrameId(): FrameId = latestFrameIdRef.get()

    /**
     * 获取最新分析帧的时间戳
     */
    fun getLatestTimestampNs(): Long = latestTimestampNsRef.get()

    /**
     * 重置状态（相机释放时调用）
     */
    fun reset() {
        latestFrameIdRef.set(FrameId.INVALID)
        latestTimestampNsRef.set(0L)
    }
}
