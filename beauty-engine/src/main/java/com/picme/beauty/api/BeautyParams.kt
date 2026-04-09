package com.picme.beauty.api

/**
 * R Plan 美颜参数
 *
 * 新模块专用参数容器，解耦对 app 层 BeautySettings 的依赖。
 * app 层负责将 BeautySettings 转换为 BeautyParams 后传入。
 *
 * 所有强度值说明：
 * - smoothing   : 磨皮 0.0~1.0（已归一化）
 * - whitening   : 美白 0.0~1.0（已归一化）
 * - bigEyes     : 大眼 0.0~1.0（已归一化）
 * - slimFace    : 瘦脸 -1.0~1.0（已归一化，负值为丰满）
 * - lipColor    : 唇色强度 0.0~1.0
 * - lipColorIndex : 唇色色号 0~11
 * - blush       : 腮红强度 0.0~1.0
 * - blushColorFamily : 腮红色系 0=粉/1=橙/2=梅
 */
data class BeautyParams(
    val enabled: Boolean = false,
    val smoothing: Float = 0f,
    val whitening: Float = 0f,
    val bigEyes: Float = 0f,
    val slimFace: Float = 0f,
    val lipColor: Float = 0f,
    val lipColorIndex: Int = 0,
    val blush: Float = 0f,
    val blushColorFamily: Int = 0
) {
    companion object {
        val EMPTY = BeautyParams()
    }
}

