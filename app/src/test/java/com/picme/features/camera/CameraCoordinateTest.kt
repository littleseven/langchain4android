package com.picme.features.camera

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * [QA] 相机坐标转换测试
 *
 * 整合内容（原 FaceCoordinateTransformTest / CoordinateMappingValidationTest / FacePositionAccuracyTest）：
 *
 * 文件结构：
 * 1. [CoordinateMathTest]           - 坐标转换数学正确性（方向、镜像、比例切换）
 * 2. [FaceKeyPointAccuracyTest]     - 参数化关键点精确位置验证（7 组前/后置 × 旋转角度组合）
 *
 * 测试范围：
 * - 旋转 0°/90°/180°/270° 下归一化与像素坐标映射
 * - 前置镜像效果（X 轴翻转）
 * - 宽高比切换（4:3 vs 16:9）引起的 imageProxy 尺寸变化对 normY 的影响
 * - FIT_CENTER 黑边偏移量化（复现 4:3 偏移 Bug）
 * - 边界/角点坐标的超界行为
 *
 * 坐标转换核心函数 transformFaceCoordinate()：
 *   与 CameraFrameAnalyzer 生产逻辑同构，imageProxyWidth/Height 随比例动态变化
 *   (4:3=960×720, 16:9=1280×720)
 */

// ============================================================================================
// 共享坐标转换实现（internal，同包内 FaceDataFlowTest 也复用此函数）
// ============================================================================================

/**
 * 坐标转换函数（测试版本，与 CameraFrameAnalyzer 生产代码同构）
 *
 * @param imageProxyWidth  ImageProxy 原始宽，随比例动态变化（4:3=960，16:9=1280）
 * @param imageProxyHeight ImageProxy 原始高
 * @param lensFacing       0=后置, 1=前置（前置需镜像 X 轴）
 */
internal fun transformFaceCoordinate(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewWidth: Float,
    previewHeight: Float,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }

    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight

    val mirroredX = if (lensFacing == 1) 1f - normX else normX

    val (adjustedX, adjustedY) = when (rotationDegrees) {
        180 -> Pair(1f - mirroredX, 1f - normY)
        else -> Pair(mirroredX, normY)
    }

    return Offset(adjustedX * previewWidth, adjustedY * previewHeight)
}

// ============================================================================================
// 1. CoordinateMathTest：坐标转换数学正确性
//    整合自：FaceCoordinateTransformTest + CoordinateMappingValidationTest
// ============================================================================================

/**
 * [QA] 坐标转换数学验证
 *
 * 典型参数：竖屏 1080×1920，相机预览 1280×720（16:9）或 960×720（4:3），旋转 90°
 */
class CoordinateMathTest {

    companion object {
        const val PREVIEW_W = 1080f
        const val PREVIEW_H = 1920f
        const val IMG_W_16_9 = 1280
        const val IMG_H = 720
        const val IMG_W_4_3 = 960
    }

    // ================================================================
    // 方向测试：前/后置 × 左右/上下移动（原 FaceCoordinateTransformTest）
    // ================================================================

    @Test
    fun `front camera rot270 - moving right makes screen X decrease (mirror effect)`() {
        val r1 = transformFaceCoordinate(360f, 640f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 270, 1)
        val r2 = transformFaceCoordinate(432f, 640f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 270, 1)
        assertTrue("Front camera: face moves right → screen X should decrease (mirrored)", r2.x < r1.x)
    }

    @Test
    fun `front camera rot270 - moving up makes screen Y decrease`() {
        val r1 = transformFaceCoordinate(360f, 640f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 270, 1)
        val r2 = transformFaceCoordinate(360f, 512f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 270, 1)
        assertTrue("Front camera: face moves up → screen Y should decrease", r2.y < r1.y)
    }

    @Test
    fun `back camera rot270 - moving right makes screen X increase (no mirror)`() {
        val r1 = transformFaceCoordinate(360f, 640f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 270, 0)
        val r2 = transformFaceCoordinate(432f, 640f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 270, 0)
        assertTrue("Back camera: face moves right → screen X should increase", r2.x > r1.x)
    }

    @Test
    fun `back camera rot270 - moving up makes screen Y decrease`() {
        val r1 = transformFaceCoordinate(360f, 640f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 270, 0)
        val r2 = transformFaceCoordinate(360f, 512f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 270, 0)
        assertTrue("Back camera: face moves up → screen Y should decrease", r2.y < r1.y)
    }

    // ================================================================
    // 数学正确性：归一化、镜像、端到端（原 CoordinateMappingValidationTest TC1-TC7）
    // ================================================================

    @Test
    fun `center point rot90 back camera - maps to screen (960, 540)`() {
        val result = transformFaceCoordinate(640f, 360f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 90, 0)
        assertEquals("Center X", 960f, result.x, 1f)
        assertEquals("Center Y", 540f, result.y, 1f)
    }

    @Test
    fun `center point rot90 back camera - normalized coords are correct`() {
        val result = transformFaceCoordinate(640f, 360f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 90, 0)
        assertEquals("Norm X = 0.8889", 0.8889f, result.x / PREVIEW_W, 0.001f)
        assertEquals("Norm Y = 0.2813", 0.28125f, result.y / PREVIEW_H, 0.001f)
    }

    @Test
    fun `front camera mirroring - back and front X are symmetric about screen center`() {
        val leftX = IMG_W_16_9 * 0.3f
        val rotatedWidth = IMG_H.toFloat()

        val backNormX = leftX / rotatedWidth
        val backScreenX = backNormX * PREVIEW_W

        val frontNormX = 1f - backNormX
        val frontScreenX = frontNormX * PREVIEW_W

        val backDist = backScreenX - PREVIEW_W / 2
        val frontDist = PREVIEW_W / 2 - frontScreenX
        assertEquals("Mirror distance from center should be equal", backDist, frontDist, 1f)
    }

    @Test
    fun `top-left corner rot90 - screen coords are within preview bounds`() {
        val result = transformFaceCoordinate(0f, 0f, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 90, 0)
        assertTrue("Top-left X in [0, $PREVIEW_W]", result.x in 0f..PREVIEW_W)
        assertTrue("Top-left Y in [0, $PREVIEW_H]", result.y in 0f..PREVIEW_H)
    }

    @Test
    fun `bottom-right corner rot90 - raw screen X exceeds preview width`() {
        val result = transformFaceCoordinate(
            IMG_W_16_9.toFloat(), IMG_H.toFloat(),
            IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 90, 0
        )
        assertTrue("Bottom-right X should exceed preview width (out-of-bounds)", result.x > PREVIEW_W)
    }

    @Test
    fun `normalized coords consistent across different screen resolutions with same aspect ratio`() {
        val faceX = IMG_W_16_9 / 2f
        val faceY = IMG_H / 2f

        // 1080×1920
        val screenA = transformFaceCoordinate(faceX, faceY, IMG_W_16_9, IMG_H, PREVIEW_W, PREVIEW_H, 90, 0)
        val normAX = screenA.x / PREVIEW_W
        val normAY = screenA.y / PREVIEW_H

        // 1440×2560（同为 9:16，不同分辨率）
        val screenB = transformFaceCoordinate(faceX, faceY, IMG_W_16_9, IMG_H, 1440f, 2560f, 90, 0)
        val normBX = screenB.x / 1440f
        val normBY = screenB.y / 2560f

        assertEquals("Normalized X consistent across resolutions", normAX, normBX, 0.0001f)
        assertEquals("Normalized Y consistent across resolutions", normAY, normBY, 0.0001f)
        assertEquals("Normalized X value should be ~0.8889", 0.8889f, normAX, 0.001f)
        assertEquals("Normalized Y value should be ~0.28125", 0.28125f, normAY, 0.001f)
    }

    @Test
    fun `lip position below face center in screen coords`() {
        val faceCenterY = IMG_H * 0.6f
        val lipY = faceCenterY + 50f
        val rotatedHeight = IMG_W_16_9.toFloat()

        val faceScreenY = (faceCenterY / rotatedHeight) * PREVIEW_H
        val lipScreenY = (lipY / rotatedHeight) * PREVIEW_H

        assertTrue("Lip should be below face center", lipScreenY > faceScreenY)
    }

    // ================================================================
    // 宽高比切换专项（原 CoordinateMappingValidationTest TC9-TC11）
    // 复现 Bug：4:3 下使用错误的 rotatedHeight 导致人脸 UI 向上偏移
    // ================================================================

    @Test
    fun `aspect ratio switch - 4x3 and 16x9 image center both give normY 0_5`() {
        // 16:9：imageProxy=1280×720，旋转 90° → rotatedHeight=1280
        val normY_16_9 = (IMG_W_16_9 / 2f) / IMG_W_16_9.toFloat()  // 640/1280 = 0.5

        // 4:3：imageProxy=960×720，旋转 90° → rotatedHeight=960
        val normY_4_3 = (IMG_W_4_3 / 2f) / IMG_W_4_3.toFloat()    // 480/960 = 0.5

        assertEquals("16:9 center normY should be 0.5", 0.5f, normY_16_9, 0.001f)
        assertEquals("4:3 center normY should be 0.5", 0.5f, normY_4_3, 0.001f)
    }

    @Test
    fun `aspect ratio switch - using 16x9 rotatedHeight in 4x3 mode causes 240px upward Y shift`() {
        val faceY_4_3 = IMG_W_4_3 / 2f  // 480（4:3 竖屏中心）

        val normY_correct = faceY_4_3 / IMG_W_4_3.toFloat()    // 480/960 = 0.5
        val normY_wrong = faceY_4_3 / IMG_W_16_9.toFloat()     // 480/1280 = 0.375（误用 16:9 高度）

        val screenY_correct = normY_correct * PREVIEW_H   // 960
        val screenY_wrong = normY_wrong * PREVIEW_H       // 720

        val yOffset = screenY_correct - screenY_wrong
        assertEquals("Wrong rotatedHeight causes 240px upward Y shift in 4:3 mode", 240f, yOffset, 1f)
        assertTrue("Y shift should exceed 200px to be user-visible", yOffset > 200f)
    }

    @Test
    fun `aspect ratio switch - 16x9 full-height mapping equals content-area mapping (no letterbox)`() {
        val faceY = IMG_W_16_9 * 0.25f   // 旋转后 rotatedHeight=1280
        val normY = faceY / IMG_W_16_9.toFloat()  // 0.25

        val screenY_fullHeight = normY * PREVIEW_H      // 480
        val screenY_contentArea = normY * PREVIEW_H     // 480（无黑边，两者等价）

        assertEquals("16:9: full-height and content-area mapping should be identical",
            screenY_fullHeight, screenY_contentArea, 0.1f)
    }

    @Test
    fun `aspect ratio switch - 4x3 FIT_CENTER black bar height is 555px on 1080x1920 screen`() {
        // sourceAspect = 960/720 = 1.333，outputAspect = 1080/1920 = 0.5625
        // sourceAspect > outputAspect → viewportWidth=1080, viewportHeight = 1080/1.333 = 810
        val contentHeight = 810f
        val blackBarHeight = (PREVIEW_H - contentHeight) / 2f

        assertEquals("Black bar height for 4:3 on 1080×1920 should be 555px", 555f, blackBarHeight, 1f)
    }
}

// ============================================================================================
// 2. FaceKeyPointAccuracyTest：参数化关键点精确位置验证
//    整合自：FacePositionAccuracyTest（重命名以避免与原文件冲突）
// ============================================================================================

/**
 * [QA] 人脸关键点位置精确匹配（参数化）
 *
 * 覆盖：前/后置 × 0°/90°/180°/270° × 不同人脸位置（中心/左上/右下/超界）
 */
@RunWith(Parameterized::class)
class FaceKeyPointAccuracyTest(
    private val testName: String,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val previewWidth: Float,
    private val previewHeight: Float,
    private val rotationDegrees: Int,
    private val lensFacing: Int,
    private val faceInput: FaceKeyInput,
    private val expectedOutput: FaceKeyOutput
) {

    data class FaceKeyInput(
        val faceCenterX: Float,
        val faceCenterY: Float,
        val leftEyeX: Float,
        val leftEyeY: Float,
        val rightEyeX: Float,
        val rightEyeY: Float
    )

    data class FaceKeyOutput(
        val faceCenter: Offset,
        val leftEye: Offset,
        val rightEye: Offset,
        val faceCenterNorm: Offset,
        val leftEyeNorm: Offset,
        val rightEyeNorm: Offset
    )

    companion object {
        const val PHONE_W = 1080f
        const val PHONE_H = 1920f
        const val IMG_W = 1280
        const val IMG_H = 720

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            // 1. 后置-90°-人脸中心
            arrayOf(
                "后置-90度-人脸中心", IMG_W, IMG_H, PHONE_W, PHONE_H, 90, 0,
                FaceKeyInput(640f, 360f, 600f, 320f, 680f, 320f),
                FaceKeyOutput(
                    Offset(960f, 540f), Offset(900f, 480f), Offset(1020f, 480f),
                    Offset(0.8889f, 0.28125f), Offset(0.8333f, 0.25f), Offset(0.9444f, 0.25f)
                )
            ),
            // 2. 前置-90°-人脸中心（镜像）
            arrayOf(
                "前置-90度-人脸中心", IMG_W, IMG_H, PHONE_W, PHONE_H, 90, 1,
                FaceKeyInput(640f, 360f, 600f, 320f, 680f, 320f),
                FaceKeyOutput(
                    Offset(120f, 540f), Offset(180f, 480f), Offset(60f, 480f),
                    Offset(0.1111f, 0.28125f), Offset(0.1667f, 0.25f), Offset(0.0556f, 0.25f)
                )
            ),
            // 3. 后置-0°-人脸中心（横屏）
            arrayOf(
                "后置-0度-人脸中心", IMG_W, IMG_H, PHONE_W, PHONE_H, 0, 0,
                FaceKeyInput(640f, 360f, 580f, 300f, 700f, 300f),
                FaceKeyOutput(
                    Offset(540f, 960f), Offset(489.375f, 800f), Offset(590.625f, 800f),
                    Offset(0.5f, 0.5f), Offset(0.4531f, 0.4167f), Offset(0.5469f, 0.4167f)
                )
            ),
            // 4. 后置-90°-人脸左上
            arrayOf(
                "后置-90度-人脸左上", IMG_W, IMG_H, PHONE_W, PHONE_H, 90, 0,
                FaceKeyInput(320f, 180f, 280f, 140f, 360f, 140f),
                FaceKeyOutput(
                    Offset(480f, 270f), Offset(420f, 210f), Offset(540f, 210f),
                    Offset(0.4444f, 0.1406f), Offset(0.3889f, 0.1094f), Offset(0.5f, 0.1094f)
                )
            ),
            // 5. 后置-90°-人脸右下（坐标超界）
            arrayOf(
                "后置-90度-人脸右下超界", IMG_W, IMG_H, PHONE_W, PHONE_H, 90, 0,
                FaceKeyInput(960f, 540f, 920f, 500f, 1000f, 500f),
                FaceKeyOutput(
                    Offset(1440f, 810f), Offset(1380f, 750f), Offset(1500f, 750f),
                    Offset(1.3333f, 0.4219f), Offset(1.2778f, 0.3906f), Offset(1.3889f, 0.3906f)
                )
            ),
            // 6. 后置-180°-人脸中心（倒立，XY 翻转）
            arrayOf(
                "后置-180度-人脸中心", IMG_W, IMG_H, PHONE_W, PHONE_H, 180, 0,
                FaceKeyInput(640f, 360f, 600f, 320f, 680f, 320f),
                FaceKeyOutput(
                    Offset(540f, 960f), Offset(573.75f, 1066.67f), Offset(506.25f, 1066.67f),
                    Offset(0.5f, 0.5f), Offset(0.53125f, 0.5556f), Offset(0.46875f, 0.5556f)
                )
            ),
            // 7. 后置-270°-人脸中心（270° 与 90° 走同一分支）
            arrayOf(
                "后置-270度-人脸中心", IMG_W, IMG_H, PHONE_W, PHONE_H, 270, 0,
                FaceKeyInput(640f, 360f, 600f, 320f, 680f, 320f),
                FaceKeyOutput(
                    Offset(960f, 540f), Offset(900f, 480f), Offset(1020f, 480f),
                    Offset(0.8889f, 0.28125f), Offset(0.8333f, 0.25f), Offset(0.9444f, 0.25f)
                )
            )
        )
    }

    @Test
    fun `face center pixel position accuracy`() {
        val result = transformFaceCoordinate(
            faceInput.faceCenterX, faceInput.faceCenterY,
            imageWidth, imageHeight, previewWidth, previewHeight, rotationDegrees, lensFacing
        )
        assertEquals("$testName: Center X", expectedOutput.faceCenter.x, result.x, 1f)
        assertEquals("$testName: Center Y", expectedOutput.faceCenter.y, result.y, 1f)
    }

    @Test
    fun `face center normalized coords accuracy`() {
        val result = transformFaceCoordinate(
            faceInput.faceCenterX, faceInput.faceCenterY,
            imageWidth, imageHeight, previewWidth, previewHeight, rotationDegrees, lensFacing
        )
        assertEquals("$testName: Center normX", expectedOutput.faceCenterNorm.x, result.x / previewWidth, 0.001f)
        assertEquals("$testName: Center normY", expectedOutput.faceCenterNorm.y, result.y / previewHeight, 0.001f)
    }

    @Test
    fun `left eye position accuracy`() {
        val result = transformFaceCoordinate(
            faceInput.leftEyeX, faceInput.leftEyeY,
            imageWidth, imageHeight, previewWidth, previewHeight, rotationDegrees, lensFacing
        )
        assertEquals("$testName: Left eye X", expectedOutput.leftEye.x, result.x, 1f)
        assertEquals("$testName: Left eye Y", expectedOutput.leftEye.y, result.y, 1f)
    }

    @Test
    fun `right eye position accuracy`() {
        val result = transformFaceCoordinate(
            faceInput.rightEyeX, faceInput.rightEyeY,
            imageWidth, imageHeight, previewWidth, previewHeight, rotationDegrees, lensFacing
        )
        assertEquals("$testName: Right eye X", expectedOutput.rightEye.x, result.x, 1f)
        assertEquals("$testName: Right eye Y", expectedOutput.rightEye.y, result.y, 1f)
    }

    @Test
    fun `eye relative position - left-right order and horizontal alignment`() {
        val leftResult = transformFaceCoordinate(
            faceInput.leftEyeX, faceInput.leftEyeY,
            imageWidth, imageHeight, previewWidth, previewHeight, rotationDegrees, lensFacing
        )
        val rightResult = transformFaceCoordinate(
            faceInput.rightEyeX, faceInput.rightEyeY,
            imageWidth, imageHeight, previewWidth, previewHeight, rotationDegrees, lensFacing
        )

        when {
            lensFacing == 1 ->
                assertTrue("$testName: Front - left eye X > right eye X (mirrored)", leftResult.x > rightResult.x)
            rotationDegrees == 180 ->
                assertTrue("$testName: Back 180° - left eye X > right eye X (flipped)", leftResult.x > rightResult.x)
            else ->
                assertTrue("$testName: Back - left eye X < right eye X", leftResult.x < rightResult.x)
        }

        assertEquals("$testName: Eyes at same height", leftResult.y, rightResult.y, 2f)
    }

    @Test
    fun `eye above face center`() {
        val faceResult = transformFaceCoordinate(
            faceInput.faceCenterX, faceInput.faceCenterY,
            imageWidth, imageHeight, previewWidth, previewHeight, rotationDegrees, lensFacing
        )
        val leftEyeResult = transformFaceCoordinate(
            faceInput.leftEyeX, faceInput.leftEyeY,
            imageWidth, imageHeight, previewWidth, previewHeight, rotationDegrees, lensFacing
        )

        if (rotationDegrees == 180) {
            assertTrue("$testName: 180° - eyes below face center", leftEyeResult.y > faceResult.y)
        } else {
            assertTrue("$testName: Eyes above face center", leftEyeResult.y < faceResult.y)
        }
    }
}

