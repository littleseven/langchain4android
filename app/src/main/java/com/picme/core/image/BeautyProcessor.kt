package com.picme.core.image

import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face
import com.picme.domain.model.BeautySettings

/**
 * 美颜效果处理器接口
 * 定义所有美颜功能的标准实现规范
 *
 * ⚠️ 模块化注意（Phase 2）：
 * 此接口当前位于 core/image 层，引用了两个平台级依赖：
 * 1. [android.graphics.Bitmap] — Android 平台类
 * 2. [com.google.mlkit.vision.face.Face] — ML Kit SDK 类
 *
 * 迁移到 beauty-core 模块时的建议方案：
 * a) 将 Bitmap 替换为自定义 ImageBuffer 数据类（纯 Kotlin），
 *    由具体引擎实现负责在 Android 层做 Bitmap <-> ImageBuffer 转换
 * b) 将 Face 替换为 domain 层自定义的 FaceInfo 数据类，
 *    避免 beauty-core 直接依赖 ML Kit SDK
 * c) 此接口可先整体保留在 app 模块，待 ImageBuffer/FaceInfo 完成后再迁移
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
     * @param faces 人脸检测结果（用于 landmarks 定位）
     * @param isFrontCamera 是否为前置摄像头（影响 eye 坐标方向）
     * @return 处理后的图像
     */
    suspend fun applySlimFace(bitmap: Bitmap, strength: Float, faces: List<Face>, isFrontCamera: Boolean = false): Bitmap
    
    /**
     * 应用大眼效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @param faces 人脸检测结果（用于 landmarks 定位）
     * @return 处理后的图像
     */
    suspend fun applyBigEyes(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap
    
    /**
     * 应用唇色效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @param colorIndex 色号索引 0-11
     * @param faces 人脸检测结果（用于嘴部定位）
     * @return 处理后的图像
     */
    suspend fun applyLipColor(bitmap: Bitmap, strength: Float, colorIndex: Int, faces: List<Face>): Bitmap

    /**
     * 应用腮红效果
     * @param bitmap 原始图像
     * @param strength 强度 0-100
     * @param colorFamily 色系 0=粉色,1=橙色,2=梅子色
     * @return 处理后的图像
     */
    suspend fun applyBlush(bitmap: Bitmap, strength: Float, colorFamily: Int): Bitmap

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
     * @param faces 人脸检测结果（用于 landmarks 定位）
     * @return 处理后的图像
     */
    suspend fun applyAllEffects(bitmap: Bitmap, settings: BeautySettings, faces: List<Face>): Bitmap {
        var result = bitmap
        
        // 面部精修
        if (settings.smoothing > 0) {
            result = applySmoothing(result, settings.smoothing)
        }
        if (settings.whitening > 0) {
            result = applyWhitening(result, settings.whitening)
        }
        if (faces.isNotEmpty()) {
            if (settings.slimFace != 0f) {
                result = applySlimFace(result, settings.slimFace, faces)
            }
            if (settings.bigEyes > 0) {
                result = applyBigEyes(result, settings.bigEyes, faces)
            }
            // 妆容调节
            if (settings.lipColor > 0) {
                result = applyLipColor(result, settings.lipColor, settings.lipColorIndex, faces)
            }
            if (settings.blush > 0) {
                result = applyBlush(result, settings.blush, settings.blushColorFamily)
            }
            if (settings.eyebrow > 0) {
                result = applyEyebrow(result, settings.eyebrow)
            }
        }
        // 身材管理 (需要全身检测，当前仅当有人脸时应用)
        if (faces.isNotEmpty() && (settings.bodyEnhancement != 0f || settings.legExtension > 0f)) {
            if (settings.bodyEnhancement != 0f) {
                result = applyBodyEnhancement(result, settings.bodyEnhancement)
            }
            if (settings.legExtension > 0f) {
                result = applyLegExtension(result, settings.legExtension)
            }
        }
        
        return result
    }
}
