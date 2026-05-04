package com.picme.beauty.api

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

    // 专业调色（大美丽引擎路径专用，UI 原始值，由 Converter 映射到 Shader 参数范围）
    val exposure: Float = 0f,         // 曝光 -10.0~10.0，0 为原始
    val contrast: Float = 50f,        // 对比度 0-200，50=原始(→ Shader 1.0)
    val saturation: Float = 100f,     // 饱和度 0-200，100=原始(→ Shader 1.0)
    val temperature: Float = 5000f,   // 色温 2000~8000K，5000=原始(→ Shader -1~+1)
    val tint: Float = 0f,             // 色调 -100~+100，0 为原始(→ Shader -1~+1)
    val brightness: Float = 0f,       // 亮度 -100~+100，0 为原始(→ Shader -1~+1)
    val redAdjustment: Float = 100f,  // 红色 0-200，100=原始(→ Shader 1.0)
    val greenAdjustment: Float = 100f,// 绿色 0-200，100=原始(→ Shader 1.0)
    val blueAdjustment: Float = 100f, // 蓝色 0-200，100=原始(→ Shader 1.0)

    // 色调滤镜（同时影响预览和拍照后期处理）
    val colorFilter: FilterType = FilterType.NONE,

    // 风格特效（大美丽引擎路径）
    val styleFilter: StyleFilter = StyleFilter.NONE
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
