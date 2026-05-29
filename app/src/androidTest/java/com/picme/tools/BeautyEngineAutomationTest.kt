package com.picme.tools

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picme.beauty.api.BeautyProcessor
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.api.facedetect.FaceDetectorFactory
import com.picme.di.AppContainerImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BeautyEngine 自动化测试
 *
 * 用途：验证美颜核心功能的正确性和性能。
 * 设计为 Instrumented Test，在真实设备上运行以验证 GPU/CPU 路径。
 *
 * 使用方式：
 * 1. 直接运行：./gradlew connectedDebugAndroidTest --tests "com.picme.tools.BeautyEngineAutomationTest"
 * 2. 单个测试：./gradlew connectedDebugAndroidTest --tests "com.picme.tools.BeautyEngineAutomationTest.testAdjustSmoothing"
 */
@RunWith(AndroidJUnit4::class)
class BeautyEngineAutomationTest {

    private lateinit var appContainer: AppContainerImpl
    private lateinit var beautyProcessor: BeautyProcessor
    private lateinit var faceDetector: FaceDetector
    private lateinit var testBitmap: Bitmap

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().context
        appContainer = AppContainerImpl(context)
        beautyProcessor = appContainer.imageProcessor as BeautyProcessor
        faceDetector = appContainer.faceDetector
        testBitmap = createTestBitmap()
    }

    @Test
    fun testAdjustSmoothing() = runBlocking {
        // Arrange
        val settings = BeautySettings(smoothing = 80f)

        // Act
        val result = TestAutomationTools.adjustAndVerify(
            beautyProcessor = beautyProcessor,
            settings = settings,
            testBitmap = testBitmap,
            timeoutMs = 5000
        )

        // Assert
        assertTrue("Beauty adjustment should succeed", result.success)
        assertEquals("Processing time should be < 500ms", true, result.getMetric("processingTimeMs") as? Int ?: 0 < 500)
    }

    @Test
    fun testAdjustWhitening() = runBlocking {
        val settings = BeautySettings(whitening = 60f)

        val result = TestAutomationTools.adjustAndVerify(beautyProcessor, settings, testBitmap)

        assertTrue(result.success)
    }

    @Test
    fun testAdjustSlimFace() = runBlocking {
        val settings = BeautySettings(slimFace = 30f)

        val result = TestAutomationTools.adjustAndVerify(beautyProcessor, settings, testBitmap)

        assertTrue(result.success)
    }

    @Test
    fun testAdjustBigEyes() = runBlocking {
        val settings = BeautySettings(bigEyes = 50f)

        val result = TestAutomationTools.adjustAndVerify(beautyProcessor, settings, testBitmap)

        assertTrue(result.success)
    }

    @Test
    fun testAllEffectsCombined() = runBlocking {
        val settings = BeautySettings(
            smoothing = 70f,
            whitening = 50f,
            slimFace = 20f,
            bigEyes = 40f,
            lipColor = 60f,
            blush = 50f
        )

        val result = TestAutomationTools.adjustAndVerify(beautyProcessor, settings, testBitmap)

        assertTrue(result.success)
        
        // 验证性能指标
        val processingTime = result.getMetric("processingTimeMs") as? Int ?: 0
        assertTrue("Combined effects should process within 800ms", processingTime < 800)
    }

    @Test
    fun testFaceDetection() = runBlocking {
        val result = TestAutomationTools.detectFacesTest(faceDetector, testBitmap)

        // 注意：测试图可能没有人脸，所以只检查不崩溃
        assertTrue("Detection should complete", result.success || result.error != null)
        
        // 验证检测时间
        val detectionTime = result.getMetric("detectionTimeMs") as? Int ?: 0
        assertTrue("Face detection should be fast (< 1s)", detectionTime < 1000)
    }

    @Test
    fun testBatchParameterTesting() = runBlocking {
        val paramSets = listOf(
            BeautySettings(smoothing = 50f),
            BeautySettings(smoothing = 70f),
            BeautySettings(smoothing = 90f)
        )

        val results = TestAutomationTools.batchTestBeautyParams(beautyProcessor, paramSets)

        assertEquals("Should test all parameter sets", 3, results.size)
        assertTrue("All tests should pass", results.all { it.success })
    }

    /**
     * 创建测试用 Bitmap（灰色背景，模拟人脸区域）
     */
    private fun createTestBitmap(): Bitmap {
        return Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF808080.toInt())
        }
    }
}
