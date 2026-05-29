package com.picme.capability

import android.graphics.Bitmap
import com.picme.beauty.api.BeautyProcessor
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.Face
import com.picme.beauty.api.facedetect.FaceDetector
import kotlinx.coroutines.runBlocking

/**
 * 美颜业务能力接口
 *
 * 职责：提供美颜调节的标准化能力，支持生产和测试环境调用。
 * 
 * 设计原则：
 * 1. 独立运行 - 不依赖 LLM，按协议发送指令即可执行
 * 2. 类型安全 - 所有参数和返回值都有明确类型
 * 3. 可组合性 - 单个操作可组合成复杂测试流程
 *
 * 使用示例：
 * ```kotlin
 * val capability = BeautyCapability(beautyProcessor, faceDetector)
 * 
 * // 调整磨皮参数
 * val result1 = capability.adjustSmoothing(smoothness = 80f)
 * assert(result1.success)
 * 
 * // 批量测试多组参数
 * val results = capability.batchTest(listOf(
 *     BeautyParameters(smooth = 50f),
 *     BeautyParameters(smooth = 70f),
 *     BeautyParameters(smooth = 90f)
 * ))
 * ```
 */
class BeautyCapability(
    private val beautyProcessor: BeautyProcessor,
    private val faceDetector: FaceDetector? = null
) {

    /**
     * 调整磨皮参数
     *
     * @param smoothness 磨皮强度 0-100
     * @param inputBitmap 输入图像（可选，默认创建测试图）
     * @return 处理结果
     */
    fun adjustSmoothing(
        smoothness: Float,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)
        
        val result = beautyProcessor.applySmoothing(bitmap, smoothness, faces)
        
        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Adjust smoothing failed: ${exception.message}", exception)
    }

    /**
     * 调整美白参数
     */
    fun adjustWhitening(
        whitening: Float,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)
        
        val result = beautyProcessor.applyWhitening(bitmap, whitening, faces)
        
        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Adjust whitening failed: ${exception.message}", exception)
    }

    /**
     * 调整瘦脸参数
     */
    fun adjustSlimFace(
        slimFace: Float,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)
        
        val result = beautyProcessor.applySlimFace(bitmap, slimFace, faces)
        
        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Adjust slim face failed: ${exception.message}", exception)
    }

    /**
     * 调整大眼参数
     */
    fun adjustBigEyes(
        bigEyes: Float,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)
        
        val result = beautyProcessor.applyBigEyes(bitmap, bigEyes, faces)
        
        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Adjust big eyes failed: ${exception.message}", exception)
    }

    /**
     * 应用所有美颜效果
     */
    fun applyAllEffects(
        settings: BeautySettings,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)
        
        val result = beautyProcessor.applyAllEffects(bitmap, settings, faces)
        
        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Apply all effects failed: ${exception.message}", exception)
    }

    /**
     * 批量测试多组参数
     */
    fun batchTest(paramSets: List<BeautySettings>): List<CapabilityResult<Bitmap>> {
        return paramSets.map { settings ->
            applyAllEffects(settings)
        }
    }

    /**
     * 人脸检测
     */
    fun detectFaces(inputBitmap: Bitmap): List<Face> {
        if (faceDetector == null) {
            return emptyList()
        }
        
        return runCatching {
            val results = faceDetector.detect(inputBitmap)
            results.filter { it.isValid }.map { result ->
                Face(
                    boundingBox = result.boundingBox,
                    landmarks = result.landmarks,
                    confidence = result.confidence
                )
            }
        }.getOrElse { emptyList() }
    }

    /**
     * 创建测试用 Bitmap
     */
    private fun createTestBitmap(): Bitmap {
        return Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF808080.toInt())
        }
    }
}

/**
 * 能力执行结果
 *
 * 用于标准化能力调用的返回结果，支持成功/失败状态。
 */
data class CapabilityResult<out T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val stackTrace: String? = null
) {
    companion object {
        fun <T> success(data: T): CapabilityResult<T> = CapabilityResult(
            success = true,
            data = data
        )
        
        fun <T> failure(error: String, exception: Throwable? = null): CapabilityResult<T> = CapabilityResult(
            success = false,
            error = error,
            stackTrace = exception?.stackTraceToString()
        )
    }
    
    fun getError(): String? = error
    fun getData(): T? = data
}
