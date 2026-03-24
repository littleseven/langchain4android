package com.picme.core.image

import android.graphics.Bitmap
import com.picme.domain.model.BeautySettings

/**
 * 美颜效果处理器接口
 * 定义所有美颜功能的标准实现规范
 */
interface BeautyProcessor {
    
    /**
     * 应用磨皮效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @return 处理后的图像
     */
    suspend fun applySmoothing(bitmap: Bitmap, strength: Float): Bitmap
    
    /**
     * 应用美白效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @return 处理后的图像
     */
    suspend fun applyWhitening(bitmap: Bitmap, strength: Float): Bitmap
    
    /**
     * 应用瘦脸效果
     * @param bitmap 原始图像
     * @param strength 强度 -50~+50 (负值为丰满)
     * @return 处理后的图像
     */
    suspend fun applySlimFace(bitmap: Bitmap, strength: Float): Bitmap
    
    /**
     * 应用大眼效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @return 处理后的图像
     */
    suspend fun applyBigEyes(bitmap: Bitmap, strength: Float): Bitmap
    
    /**
     * 应用年轻化效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @return 处理后的图像
     */
    suspend fun applyYouth(bitmap: Bitmap, strength: Float): Bitmap
    
    /**
     * 应用唇色效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @param colorIndex 色号索引 0-11
     * @return 处理后的图像
     */
    suspend fun applyLipColor(bitmap: Bitmap, strength: Float, colorIndex: Int): Bitmap
    
    /**
     * 应用腮红效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @return 处理后的图像
     */
    suspend fun applyBlush(bitmap: Bitmap, strength: Float): Bitmap
    
    /**
     * 应用眉毛加深效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @return 处理后的图像
     */
    suspend fun applyEyebrow(bitmap: Bitmap, strength: Float): Bitmap
    
    /**
     * 应用身材调整效果
     * @param bitmap 原始图像
     * @param strength 强度 -30~+30 (负值为缩小)
     * @return 处理后的图像
     */
    suspend fun applyBodyEnhancement(bitmap: Bitmap, strength: Float): Bitmap
    
    /**
     * 应用长腿效果
     * @param bitmap 原始图像
     * @param strength 强度 0-50
     * @return 处理后的图像
     */
    suspend fun applyLegExtension(bitmap: Bitmap, strength: Float): Bitmap
    
    /**
     * 应用所有美颜效果
     * @param bitmap 原始图像
     * @param settings 美颜设置
     * @return 处理后的图像
     */
    suspend fun applyAllEffects(bitmap: Bitmap, settings: BeautySettings): Bitmap {
        var result = bitmap
        
        // 面部精修
        if (settings.smoothing > 0) {
            result = applySmoothing(result, settings.smoothing)
        }
        if (settings.whitening > 0) {
            result = applyWhitening(result, settings.whitening)
        }
        if (settings.slimFace != 0f) {
            result = applySlimFace(result, settings.slimFace)
        }
        if (settings.bigEyes > 0) {
            result = applyBigEyes(result, settings.bigEyes)
        }
        if (settings.youth > 0) {
            result = applyYouth(result, settings.youth)
        }
        
        // 妆容调节
        if (settings.lipColor > 0) {
            result = applyLipColor(result, settings.lipColor, settings.lipColorIndex)
        }
        if (settings.blush > 0) {
            result = applyBlush(result, settings.blush)
        }
        if (settings.eyebrow > 0) {
            result = applyEyebrow(result, settings.eyebrow)
        }
        
        // 身材管理
        if (settings.bodyEnhancement != 0f) {
            result = applyBodyEnhancement(result, settings.bodyEnhancement)
        }
        if (settings.legExtension > 0) {
            result = applyLegExtension(result, settings.legExtension)
        }
        
        return result
    }
}
