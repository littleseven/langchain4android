package com.picme.domain.model

import com.picme.features.camera.model.FilterType
import com.picme.features.camera.model.StyleFilter

/**
 * 美颜设置数据类
 * 包含所有美颜功能的参数配置
 */
data class BeautySettings(
    // 美颜总开关
    val enabled: Boolean = false,   // 美颜是否启用

    // 面部精修
    val smoothing: Float = 0f,      // 磨皮 0-100
    val whitening: Float = 0f,      // 美白 0-100
    val slimFace: Float = 0f,       // 瘦脸 -50~+50
    val bigEyes: Float = 0f,        // 大眼 0-100

    // 妆容调节
    val lipColor: Float = DEFAULT_LIP_COLOR,       // 唇色强度 0-100，默认 40
    val lipColorIndex: Int = 0,                    // 唇色色号索引 0-11
    val blush: Float = DEFAULT_BLUSH,              // 腮红 0-100，默认 20
    val blushColorFamily: Int = 0,                 // 腮红色系 0-2（粉/橙/梅）
    val eyebrow: Float = DEFAULT_EYEBROW,          // 眉毛 0-100，默认 15
    
    // 身材管理
    val bodyEnhancement: Float = 0f, // 丰胸 -30~+30
    val legExtension: Float = 0f,    // 长腿 0-50

    // 专业调色（GPUPixel 路径专用，UI 原始值，由 Strategy 层映射到 GPUPixel 参数范围）
    val gpuExposure: Float = 0f,         // 曝光 -3.0~3.0，0 为原始
    val gpuContrast: Float = 50f,        // 对比度 0-200，50=原始(→ GPUPixel 1.0)
    val gpuSaturation: Float = 100f,     // 饱和度 0-200，100=原始(→ GPUPixel 1.0)
    val gpuWhiteBalance: Float = 5000f,  // 色温 2000~10000K，5000=原始

    // 风格特效滤镜（GPUPixel 路径专用，大美丽引擎忽略）
    val styleFilter: StyleFilter = StyleFilter.NONE,

    // 色调滤镜（大美丽引擎路径专用，GPUPixel 路径忽略；同时影响拍照后期处理）
    val colorFilter: FilterType = FilterType.NONE
) {
    /**
     * 检查是否有任何美颜参数被设置
     */
    fun hasAnyEffect(): Boolean {
        return smoothing > 0 || whitening > 0 || slimFace != 0f || bigEyes > 0 ||
            lipColor > 0 || blush > 0 || eyebrow > 0 ||
            bodyEnhancement != 0f || legExtension > 0
    }

    companion object {
        const val DEFAULT_LIP_COLOR: Float = 40f
        const val DEFAULT_BLUSH: Float = 20f
        const val DEFAULT_EYEBROW: Float = 15f
    }
}
