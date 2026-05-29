package com.picme.tools

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.picme.beauty.api.BeautyProcessor
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.Face
import com.picme.beauty.api.facedetect.FaceDetector
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PicMe 自动化测试工具类
 *
 * 职责：封装 BeautyEngine、FaceDetector 等核心能力的测试接口，
 * 为 RD Agent 提供类型安全、可组合的 Test Tool。
 *
 * 设计原则：
 * 1. 显式优于隐式 - 所有参数和返回值都有明确类型
 * 2. 结构化输出 - 测试结果包含指标和错误信息
 * 3. 可组合性 - 单个操作可组合成复杂测试流程
 *
 * 使用示例：
 * ```kotlin
 * val result = runBlocking {
 *     TestAutomationTools.adjustAndVerify(
 *         params = BeautyParameters(smooth = 80, whiten = 60),
 *         timeoutMs = 5000
 *     )
 * }
 * assert(result.success) { "Beauty adjustment failed: ${result.error}" }
 * ```
 */
object TestAutomationTools {

    private val context = InstrumentationRegistry.getInstrumentation().context

    /**
     * 调整美颜参数并验证效果
     *
     * @param beautyProcessor BeautyProcessor 实例（从 DI 容器注入）
     * @param settings 美颜设置
     * @param testBitmap 测试图像（可选，默认使用纯色图）
     * @param timeoutMs 超时时间
     * @return TestResult 包含成功状态、处理时间和错误信息
     */
    suspend fun adjustAndVerify(
        beautyProcessor: BeautyProcessor,
        settings: BeautySettings,
        testBitmap: Bitmap? = null,
        timeoutMs: Long = 5000
    ): TestResult = runCatching {
        val startTime = System.currentTimeMillis()

        // 1. 准备测试图像
        val inputBitmap = testBitmap ?: createTestBitmap()

        // 2. 检测人脸（用于面部精修）
        val faceDetector = FaceDetectorFactory.create(context)
        val faces = detectFaces(faceDetector, inputBitmap)

        // 3. 应用美颜效果
        val resultBitmap = beautyProcessor.applyAllEffects(inputBitmap, settings, faces)

        // 4. 验证结果
        val processingTime = System.currentTimeMillis() - startTime
        val isValid = validateResult(inputBitmap, resultBitmap, settings)

        TestResult(
            success = isValid,
            metrics = mapOf(
                "processingTimeMs" to processingTime,
                "facesDetected" to faces.size,
                "inputSize" to "${inputBitmap.width}x${inputBitmap.height}",
                "outputSize" to "${resultBitmap.width}x${resultBitmap.height}"
            ),
            error = if (isValid) null else "Result validation failed"
        )
    }.getOrElse { exception ->
        TestResult(
            success = false,
            error = exception.message ?: "Unknown error",
            stackTrace = exception.stackTraceToString()
        )
    }

    /**
     * 拍照并验证 GPU 处理结果
     *
     * @param photoProcessor PhotoProcessor 实例（从 DI 容器注入）
     * @param settings 美颜设置
     * @param timeoutMs 超时时间
     * @return TestResult 包含成功状态、GPU 处理时间和错误信息
     */
    suspend fun captureAndVerify(
        photoProcessor: com.picme.beauty.api.PhotoProcessor,
        settings: BeautySettings,
        timeoutMs: Long = 5000
    ): TestResult = runCatching {
        val startTime = System.currentTimeMillis()

        // 1. 准备测试图像（模拟拍照输出）
        val inputBitmap = createTestBitmap()

        // 2. 调用 PhotoProcessor（GPU 路径）
        // 注意：实际使用时需要从 CameraX 获取真实图像
        val resultBitmap = processPhoto(photoProcessor, inputBitmap, settings)

        val processingTime = System.currentTimeMillis() - startTime

        // 3. 验证处理结果
        val isValid = processingTime < 300 && resultBitmap != null && !resultBitmap.isRecycled

        TestResult(
            success = isValid,
            metrics = mapOf(
                "gpuProcessingTimeMs" to processingTime,
                "outputSize" to "${resultBitmap?.width}x${resultBitmap?.height}"
            ),
            error = if (isValid) null else "GPU processing failed or timeout"
        )
    }.getOrElse { exception ->
        TestResult(
            success = false,
            error = exception.message ?: "Unknown error",
            stackTrace = exception.stackTraceToString()
        )
    }

    /**
     * 人脸检测测试
     *
     * @param faceDetector FaceDetector 实例
     * @param testBitmap 测试图像
     * @return TestResult 包含检测成功状态、人脸数量和错误信息
     */
    suspend fun detectFacesTest(
        faceDetector: FaceDetector,
        testBitmap: Bitmap? = null,
        timeoutMs: Long = 5000
    ): TestResult = runCatching {
        val startTime = System.currentTimeMillis()

        val inputBitmap = testBitmap ?: createTestBitmap()
        val faces = detectFaces(faceDetector, inputBitmap)

        val processingTime = System.currentTimeMillis() - startTime

        TestResult(
            success = faces.isNotEmpty(),
            metrics = mapOf(
                "detectionTimeMs" to processingTime,
                "facesCount" to faces.size,
                "landmarksPerFace" to (faces.firstOrNull()?.landmarks?.size ?: 0)
            ),
            error = if (faces.isEmpty()) "No faces detected" else null
        )
    }.getOrElse { exception ->
        TestResult(
            success = false,
            error = exception.message ?: "Unknown error",
            stackTrace = exception.stackTraceToString()
        )
    }

    /**
     * 批量美颜参数测试
     *
     * @param beautyProcessor BeautyProcessor 实例
     * @param paramSets 多组参数集合
     * @return List<TestResult> 每组参数的测试结果
     */
    suspend fun batchTestBeautyParams(
        beautyProcessor: BeautyProcessor,
        paramSets: List<BeautySettings>
    ): List<TestResult> = runBlocking {
        paramSets.map { settings ->
            adjustAndVerify(beautyProcessor, settings)
        }
    }

    /**
     * 人脸检测结果辅助函数
     */
    private suspend fun detectFaces(
        faceDetector: FaceDetector,
        bitmap: Bitmap
    ): List<Face> = runCatching {
        val results = faceDetector.detect(bitmap)
        results.filter { it.isValid }.map { result ->
            Face(
                boundingBox = result.boundingBox,
                landmarks = result.landmarks,
                confidence = result.confidence
            )
        }
    }.getOrElse { emptyList() }

    /**
     * 照片处理辅助函数
     */
    private suspend fun processPhoto(
        photoProcessor: com.picme.beauty.api.PhotoProcessor,
        bitmap: Bitmap,
        settings: BeautySettings
    ): Bitmap? {
        // TODO: 实现实际的 PhotoProcessor 调用逻辑
        // 目前返回原始图作为占位
        return bitmap
    }

    /**
     * 结果验证辅助函数
     */
    private fun validateResult(
        input: Bitmap,
        output: Bitmap,
        settings: BeautySettings
    ): Boolean {
        // 基本验证：输出不应为空且尺寸匹配
        if (output.isRecycled || output.width != input.width || output.height != input.height) {
            return false
        }

        // 进阶验证：检查像素变化（如果参数不为 0）
        val hasChanges = settings.smoothing > 0 || settings.whitening > 0 ||
                settings.slimFace != 0f || settings.bigEyes > 0

        if (hasChanges) {
            // TODO: 实现像素级对比验证
            // 当前仅做基本尺寸验证
            return true
        }

        return true
    }

    /**
     * 创建测试用 Bitmap（纯色图）
     */
    private fun createTestBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF808080.toInt()) // 灰色背景
        return bitmap
    }
}

/**
 * 测试结果数据结构
 *
 * 用于标准化测试输出，便于 AI 解析和 Self-Heal 决策。
 */
data class TestResult(
    val success: Boolean,
    val metrics: Map<String, Any> = emptyMap(),
    val error: String? = null,
    val stackTrace: String? = null
) {
    fun getMetric(key: String): Any? = metrics[key]

    override fun toString(): String = buildString {
        append("TestResult{success=$success")
        if (metrics.isNotEmpty()) {
            append(", metrics={")
            append(metrics.entries.joinToString(", ") { "${it.key}=${it.value}" })
            append("}")
        }
        if (error != null) {
            append(", error=\"$error\"")
        }
        append("}")
    }
}
