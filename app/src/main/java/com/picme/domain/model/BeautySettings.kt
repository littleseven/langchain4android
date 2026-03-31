package com.picme.domain.model

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
    val youth: Float = 0f,          // 年轻化 0-100
    
    // 妆容调节
    val lipColor: Float = 0f,       // 唇色强度 0-100
    val lipColorIndex: Int = 0,     // 唇色色号索引 0-11
    val blush: Float = 0f,          // 腮红 0-100
    val eyebrow: Float = 0f,        // 眉毛 0-100
    
    // 身材管理
    val bodyEnhancement: Float = 0f, // 丰胸 -30~+30
    val legExtension: Float = 0f     // 长腿 0-50
) {
    /**
     * 检查是否有任何美颜参数被设置
     */
    fun hasAnyEffect(): Boolean {
        return smoothing > 0 || whitening > 0 || slimFace != 0f || bigEyes > 0 ||
                youth > 0 || lipColor > 0 || blush > 0 || eyebrow > 0 ||
                bodyEnhancement != 0f || legExtension > 0
    }
}
