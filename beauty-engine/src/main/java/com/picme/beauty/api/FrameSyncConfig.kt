package com.picme.beauty.api

/**
 * 帧同步配置
 *
 * @param maxStoredResults 保留最近检测结果数量
 * @param missingThresholdFrames 缺失阈值帧数（超过后隐藏妆容）
 * @param predictionMaxRatio 预测位移最大比例（相对上一帧位移）
 * @param syncMode 同步模式
 */
data class FrameSyncConfig(
    val maxStoredResults: Int = 10,
    val missingThresholdFrames: Int = 10,
    val predictionMaxRatio: Float = 1.5f,
    val syncMode: SyncMode = SyncMode.SMOOTH
) {
    enum class SyncMode {
        STRICT,
        SMOOTH,
        OFF
    }

    companion object {
        val DEFAULT = FrameSyncConfig()
    }
}
