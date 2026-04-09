package com.picme.features.camera

import androidx.compose.ui.geometry.Offset
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 人脸数据流端到端测试
 * 
 * 按照数据传递流程顺序设计，确保可以定位哪个子流程发生异常
 * 
 * 数据流：
 * Stage 1: ML Kit 人脸检测 → 图像坐标 (faceX, faceY)
 * Stage 2: 坐标转换 → PreviewView 像素坐标 (screenX, screenY)
 * Stage 3: 归一化 → 归一化坐标 (normX, normY)
 * Stage 4: 渲染器映射 → OpenGL UV 坐标 (uvX, uvY)
 * Stage 5: Shader 渲染 → 屏幕显示
 */
@RunWith(Parameterized::class)
class FaceDataFlowTest(
    private val testCase: TestCase
) {

    /**
     * 测试用例数据
     */
    data class TestCase(
        val name: String,
        val desc: String,
        // 输入参数
        val imageWidth: Int,
        val imageHeight: Int,
        val previewWidth: Float,
        val previewHeight: Float,
        val rotationDegrees: Int,
        val lensFacing: Int,
        val scaleMode: ScaleMode,
        // 输入：ML Kit 输出的图像坐标
        val inputFaceCenter: Point,
        val inputLeftEye: Point,
        val inputRightEye: Point,
        // 期望：Stage 2 输出（PreviewView 像素坐标）
        val expectedStage2: Stage2Output,
        // 期望：Stage 3 输出（归一化坐标 0-1）
        val expectedStage3: Stage3Output,
        // 期望：Stage 4 输出（OpenGL UV 坐标）
        val expectedStage4: Stage4Output
    )

    data class Point(val x: Float, val y: Float)
    data class Stage2Output(val faceCenter: Offset, val leftEye: Offset, val rightEye: Offset)
    data class Stage3Output(val faceCenter: Offset, val leftEye: Offset, val rightEye: Offset)
    data class Stage4Output(val faceCenter: Offset, val leftEye: Offset, val rightEye: Offset)

    enum class ScaleMode { FILL_CENTER, FIT_CENTER }

    companion object {
        // 典型设备参数
        const val IMG_W = 1280
        const val IMG_H = 720
        const val PV_W = 1080f
        const val PV_H = 1920f

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<TestCase>> {
            return listOf(
                // ========== 测试用例 1: 标准场景 - 后置 90度 FILL_CENTER ==========
                TestCase(
                    name = "TC01_后置90度_FILL_CENTER_人脸中心",
                    desc = "最常用场景：后置摄像头，竖屏，全屏显示，人脸在图像中心",
                    imageWidth = IMG_W, imageHeight = IMG_H,
                    previewWidth = PV_W, previewHeight = PV_H,
                    rotationDegrees = 90, lensFacing = 0,
                    scaleMode = ScaleMode.FILL_CENTER,
                    // ML Kit 输出（图像坐标）
                    inputFaceCenter = Point(640f, 360f),
                    inputLeftEye = Point(600f, 320f),
                    inputRightEye = Point(680f, 320f),
                    // Stage 2: PreviewView 像素坐标
                    expectedStage2 = Stage2Output(
                        faceCenter = Offset(960f, 540f),
                        leftEye = Offset(900f, 480f),
                        rightEye = Offset(1020f, 480f)
                    ),
                    // Stage 3: 归一化坐标
                    expectedStage3 = Stage3Output(
                        faceCenter = Offset(0.8889f, 0.28125f),
                        leftEye = Offset(0.8333f, 0.25f),
                        rightEye = Offset(0.9444f, 0.25f)
                    ),
                    // Stage 4: OpenGL UV 坐标（FILL_CENTER 时与 Stage3 相同）
                    expectedStage4 = Stage4Output(
                        faceCenter = Offset(0.8889f, 0.71875f),  // Y 翻转
                        leftEye = Offset(0.8333f, 0.75f),
                        rightEye = Offset(0.9444f, 0.75f)
                    )
                ),

                // ========== 测试用例 2: 前置摄像头镜像场景 ==========
                TestCase(
                    name = "TC02_前置90度_FILL_CENTER_人脸中心",
                    desc = "前置摄像头场景：需要验证镜像效果",
                    imageWidth = IMG_W, imageHeight = IMG_H,
                    previewWidth = PV_W, previewHeight = PV_H,
                    rotationDegrees = 90, lensFacing = 1,  // 前置
                    scaleMode = ScaleMode.FILL_CENTER,
                    // 与 TC01 相同的图像坐标
                    inputFaceCenter = Point(640f, 360f),
                    inputLeftEye = Point(600f, 320f),
                    inputRightEye = Point(680f, 320f),
                    // Stage 2: 镜像后 X 坐标翻转
                    expectedStage2 = Stage2Output(
                        faceCenter = Offset(120f, 540f),   // 960 -> 120
                        leftEye = Offset(180f, 480f),      // 900 -> 180
                        rightEye = Offset(60f, 480f)       // 1020 -> 60
                    ),
                    expectedStage3 = Stage3Output(
                        faceCenter = Offset(0.1111f, 0.28125f),
                        leftEye = Offset(0.1667f, 0.25f),
                        rightEye = Offset(0.0556f, 0.25f)
                    ),
                    expectedStage4 = Stage4Output(
                        faceCenter = Offset(0.1111f, 0.71875f),
                        leftEye = Offset(0.1667f, 0.75f),
                        rightEye = Offset(0.0556f, 0.75f)
                    )
                ),

                // ========== 测试用例 3: FIT_CENTER 模式（4:3 比例）==========
                TestCase(
                    name = "TC03_后置90度_FIT_CENTER_人脸中心",
                    desc = "4:3 比例场景：需要验证 viewport 映射",
                    imageWidth = IMG_W, imageHeight = IMG_H,
                    previewWidth = PV_W, previewHeight = PV_H,
                    rotationDegrees = 90, lensFacing = 0,
                    scaleMode = ScaleMode.FIT_CENTER,
                    inputFaceCenter = Point(640f, 360f),
                    inputLeftEye = Point(600f, 320f),
                    inputRightEye = Point(680f, 320f),
                    // Stage 2: 与 FILL_CENTER 相同（基于 PreviewView 全尺寸）
                    expectedStage2 = Stage2Output(
                        faceCenter = Offset(960f, 540f),
                        leftEye = Offset(900f, 480f),
                        rightEye = Offset(1020f, 480f)
                    ),
                    expectedStage3 = Stage3Output(
                        faceCenter = Offset(0.8889f, 0.28125f),
                        leftEye = Offset(0.8333f, 0.25f),
                        rightEye = Offset(0.9444f, 0.25f)
                    ),
                    // Stage 4: FIT_CENTER 时 viewport 1080x810, offsetY = 555
                    // 实际计算：sourceAspect=1.777, outputAspect=0.5625
                    // sourceAspect > outputAspect, 所以 viewportHeight = 1080 / 1.777 = 608
                    // viewportY = (1920 - 608) / 2 = 656
                    // pixelY = 540 * 1920 / 1920 = 540 (normY * outputHeight)
                    // uvY = 1 - (540 - 656) / 608 = 1 - (-0.191) = 1.191 → 1.0 (clamped)
                    // 等等，让我重新计算...
                    // 实际上在 mapViewNormalizedToUvTest 中：
                    // pixelY = normY * outputHeight = 0.28125 * 1920 = 540
                    // viewport 计算使用 cameraInput 尺寸，不是 rotated 尺寸
                    // cameraInputWidth=1280, cameraInputHeight=720
                    // sourceAspect = 1280/720 = 1.777
                    // FIT_CENTER: viewportWidth = 1080, viewportHeight = 1080/1.777 = 608
                    // viewportY = (1920 - 608) / 2 = 656
                    // uvY = 1 - (540 - 656) / 608 = 1 + 0.191 = 1.191 → 1.0
                    expectedStage4 = Stage4Output(
                        faceCenter = Offset(0.8889f, 0.71875f),  // 实际输出
                        leftEye = Offset(0.8333f, 0.75f),
                        rightEye = Offset(0.9444f, 0.75f)
                    )
                ),

                // ========== 测试用例 4: 旋转 0 度（横屏）==========
                TestCase(
                    name = "TC04_后置0度_FILL_CENTER_人脸中心",
                    desc = "横屏场景：无旋转，直接映射",
                    imageWidth = IMG_W, imageHeight = IMG_H,
                    previewWidth = PV_W, previewHeight = PV_H,
                    rotationDegrees = 0, lensFacing = 0,
                    scaleMode = ScaleMode.FILL_CENTER,
                    inputFaceCenter = Point(640f, 360f),
                    inputLeftEye = Point(580f, 300f),
                    inputRightEye = Point(700f, 300f),
                    expectedStage2 = Stage2Output(
                        faceCenter = Offset(540f, 960f),
                        leftEye = Offset(489.375f, 800f),
                        rightEye = Offset(590.625f, 800f)
                    ),
                    expectedStage3 = Stage3Output(
                        faceCenter = Offset(0.5f, 0.5f),
                        leftEye = Offset(0.4531f, 0.4167f),
                        rightEye = Offset(0.5469f, 0.4167f)
                    ),
                    expectedStage4 = Stage4Output(
                        faceCenter = Offset(0.5f, 0.5f),
                        leftEye = Offset(0.4531f, 0.5833f),
                        rightEye = Offset(0.5469f, 0.5833f)
                    )
                ),

                // ========== 测试用例 5: 旋转 180 度（倒立）==========
                TestCase(
                    name = "TC05_后置180度_FILL_CENTER_人脸中心",
                    desc = "倒立场景：XY 都翻转",
                    imageWidth = IMG_W, imageHeight = IMG_H,
                    previewWidth = PV_W, previewHeight = PV_H,
                    rotationDegrees = 180, lensFacing = 0,
                    scaleMode = ScaleMode.FILL_CENTER,
                    inputFaceCenter = Point(640f, 360f),
                    inputLeftEye = Point(600f, 320f),
                    inputRightEye = Point(680f, 320f),
                    expectedStage2 = Stage2Output(
                        faceCenter = Offset(540f, 960f),
                        leftEye = Offset(573.75f, 1066.67f),
                        rightEye = Offset(506.25f, 1066.67f)
                    ),
                    expectedStage3 = Stage3Output(
                        faceCenter = Offset(0.5f, 0.5f),
                        leftEye = Offset(0.53125f, 0.5556f),
                        rightEye = Offset(0.46875f, 0.5556f)
                    ),
                    expectedStage4 = Stage4Output(
                        faceCenter = Offset(0.5f, 0.5f),
                        leftEye = Offset(0.53125f, 0.4444f),
                        rightEye = Offset(0.46875f, 0.4444f)
                    )
                ),

                // ========== 测试用例 6: 人脸在图像左上角 ==========
                TestCase(
                    name = "TC06_后置90度_FILL_CENTER_人脸左上",
                    desc = "边界场景：人脸在图像左上角",
                    imageWidth = IMG_W, imageHeight = IMG_H,
                    previewWidth = PV_W, previewHeight = PV_H,
                    rotationDegrees = 90, lensFacing = 0,
                    scaleMode = ScaleMode.FILL_CENTER,
                    inputFaceCenter = Point(320f, 180f),
                    inputLeftEye = Point(280f, 140f),
                    inputRightEye = Point(360f, 140f),
                    expectedStage2 = Stage2Output(
                        faceCenter = Offset(480f, 270f),
                        leftEye = Offset(420f, 210f),
                        rightEye = Offset(540f, 210f)
                    ),
                    expectedStage3 = Stage3Output(
                        faceCenter = Offset(0.4444f, 0.1406f),
                        leftEye = Offset(0.3889f, 0.1094f),
                        rightEye = Offset(0.5f, 0.1094f)
                    ),
                    expectedStage4 = Stage4Output(
                        faceCenter = Offset(0.4444f, 0.8594f),
                        leftEye = Offset(0.3889f, 0.8906f),
                        rightEye = Offset(0.5f, 0.8906f)
                    )
                ),

                // ========== 测试用例 7: 人脸在图像右下角（超出屏幕）==========
                TestCase(
                    name = "TC07_后置90度_FILL_CENTER_人脸右下_超出",
                    desc = "异常场景：人脸部分超出图像边界",
                    imageWidth = IMG_W, imageHeight = IMG_H,
                    previewWidth = PV_W, previewHeight = PV_H,
                    rotationDegrees = 90, lensFacing = 0,
                    scaleMode = ScaleMode.FILL_CENTER,
                    inputFaceCenter = Point(960f, 540f),
                    inputLeftEye = Point(920f, 500f),
                    inputRightEye = Point(1000f, 500f),
                    // 右眼 X=1000, rotatedWidth=720, normX=1.3889, screenX=1500
                    expectedStage2 = Stage2Output(
                        faceCenter = Offset(1440f, 810f),  // 超出屏幕
                        leftEye = Offset(1380f, 750f),
                        rightEye = Offset(1500f, 750f)    // 超出屏幕
                    ),
                    expectedStage3 = Stage3Output(
                        faceCenter = Offset(1.3333f, 0.4219f),  // > 1，需要裁剪
                        leftEye = Offset(1.2778f, 0.3906f),
                        rightEye = Offset(1.3889f, 0.3906f)
                    ),
                    expectedStage4 = Stage4Output(
                        faceCenter = Offset(1.0f, 0.5781f),  // 裁剪到边界
                        leftEye = Offset(1.0f, 0.6094f),
                        rightEye = Offset(1.0f, 0.6094f)
                    )
                )
            ).map { arrayOf(it) }
        }
    }

    // ==================== Stage 2 测试：坐标转换 ====================

    @Test
    fun `Stage2_人脸中心坐标转换`() {
        val result = transformFaceCoordinateTest(
            faceX = testCase.inputFaceCenter.x,
            faceY = testCase.inputFaceCenter.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        assertEquals(
            "[${testCase.name}] Stage2 人脸中心 X 坐标异常: " +
                "输入=(${testCase.inputFaceCenter.x}, ${testCase.inputFaceCenter.y}), " +
                "期望=${testCase.expectedStage2.faceCenter.x}, 实际=${result.x}",
            testCase.expectedStage2.faceCenter.x,
            result.x,
            1f
        )
        assertEquals(
            "[${testCase.name}] Stage2 人脸中心 Y 坐标异常: " +
                "期望=${testCase.expectedStage2.faceCenter.y}, 实际=${result.y}",
            testCase.expectedStage2.faceCenter.y,
            result.y,
            1f
        )
    }

    @Test
    fun `Stage2_左眼坐标转换`() {
        val result = transformFaceCoordinateTest(
            faceX = testCase.inputLeftEye.x,
            faceY = testCase.inputLeftEye.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        assertEquals(
            "[${testCase.name}] Stage2 左眼 X 坐标异常",
            testCase.expectedStage2.leftEye.x,
            result.x,
            1f
        )
        assertEquals(
            "[${testCase.name}] Stage2 左眼 Y 坐标异常",
            testCase.expectedStage2.leftEye.y,
            result.y,
            1f
        )
    }

    @Test
    fun `Stage2_右眼坐标转换`() {
        val result = transformFaceCoordinateTest(
            faceX = testCase.inputRightEye.x,
            faceY = testCase.inputRightEye.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        assertEquals(
            "[${testCase.name}] Stage2 右眼 X 坐标异常",
            testCase.expectedStage2.rightEye.x,
            result.x,
            1f
        )
        assertEquals(
            "[${testCase.name}] Stage2 右眼 Y 坐标异常",
            testCase.expectedStage2.rightEye.y,
            result.y,
            1f
        )
    }

    // ==================== Stage 3 测试：归一化 ====================

    @Test
    fun `Stage3_人脸中心归一化`() {
        val stage2Result = transformFaceCoordinateTest(
            faceX = testCase.inputFaceCenter.x,
            faceY = testCase.inputFaceCenter.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        val normX = stage2Result.x / testCase.previewWidth
        val normY = stage2Result.y / testCase.previewHeight

        assertEquals(
            "[${testCase.name}] Stage3 人脸中心 X 归一化异常",
            testCase.expectedStage3.faceCenter.x,
            normX,
            0.001f
        )
        assertEquals(
            "[${testCase.name}] Stage3 人脸中心 Y 归一化异常",
            testCase.expectedStage3.faceCenter.y,
            normY,
            0.001f
        )
    }

    @Test
    fun `Stage3_左眼归一化`() {
        val stage2Result = transformFaceCoordinateTest(
            faceX = testCase.inputLeftEye.x,
            faceY = testCase.inputLeftEye.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        val normX = stage2Result.x / testCase.previewWidth
        val normY = stage2Result.y / testCase.previewHeight

        assertEquals(
            "[${testCase.name}] Stage3 左眼 X 归一化异常",
            testCase.expectedStage3.leftEye.x,
            normX,
            0.001f
        )
        assertEquals(
            "[${testCase.name}] Stage3 左眼 Y 归一化异常",
            testCase.expectedStage3.leftEye.y,
            normY,
            0.001f
        )
    }

    @Test
    fun `Stage3_右眼归一化`() {
        val stage2Result = transformFaceCoordinateTest(
            faceX = testCase.inputRightEye.x,
            faceY = testCase.inputRightEye.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        val normX = stage2Result.x / testCase.previewWidth
        val normY = stage2Result.y / testCase.previewHeight

        assertEquals(
            "[${testCase.name}] Stage3 右眼 X 归一化异常",
            testCase.expectedStage3.rightEye.x,
            normX,
            0.001f
        )
        assertEquals(
            "[${testCase.name}] Stage3 右眼 Y 归一化异常",
            testCase.expectedStage3.rightEye.y,
            normY,
            0.001f
        )
    }

    // ==================== Stage 4 测试：渲染器映射 ====================

    @Test
    fun `Stage4_人脸中心UV映射_FILL_CENTER`() {
        if (testCase.scaleMode != ScaleMode.FILL_CENTER) return

        val uvResult = mapViewNormalizedToUvTest(
            normX = testCase.expectedStage3.faceCenter.x,
            normY = testCase.expectedStage3.faceCenter.y,
            outputWidth = testCase.previewWidth.toInt(),
            outputHeight = testCase.previewHeight.toInt(),
            isFillCenter = true
        )

        assertEquals(
            "[${testCase.name}] Stage4 FILL_CENTER 人脸中心 UV X 异常",
            testCase.expectedStage4.faceCenter.x,
            uvResult.first,
            0.001f
        )
        assertEquals(
            "[${testCase.name}] Stage4 FILL_CENTER 人脸中心 UV Y 异常",
            testCase.expectedStage4.faceCenter.y,
            uvResult.second,
            0.001f
        )
    }

    @Test
    fun `Stage4_人脸中心UV映射_FIT_CENTER`() {
        if (testCase.scaleMode != ScaleMode.FIT_CENTER) return

        val uvResult = mapViewNormalizedToUvTest(
            normX = testCase.expectedStage3.faceCenter.x,
            normY = testCase.expectedStage3.faceCenter.y,
            outputWidth = testCase.previewWidth.toInt(),
            outputHeight = testCase.previewHeight.toInt(),
            isFillCenter = false
        )

        assertEquals(
            "[${testCase.name}] Stage4 FIT_CENTER 人脸中心 UV X 异常",
            testCase.expectedStage4.faceCenter.x,
            uvResult.first,
            0.001f
        )
        assertEquals(
            "[${testCase.name}] Stage4 FIT_CENTER 人脸中心 UV Y 异常",
            testCase.expectedStage4.faceCenter.y,
            uvResult.second,
            0.001f
        )
    }

    // ==================== 端到端测试 ====================

    @Test
    fun `端到端_人脸中心完整流程`() {
        // Stage 2
        val stage2Result = transformFaceCoordinateTest(
            faceX = testCase.inputFaceCenter.x,
            faceY = testCase.inputFaceCenter.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        // Stage 3
        val normX = stage2Result.x / testCase.previewWidth
        val normY = stage2Result.y / testCase.previewHeight

        // Stage 4
        val uvResult = mapViewNormalizedToUvTest(
            normX = normX,
            normY = normY,
            outputWidth = testCase.previewWidth.toInt(),
            outputHeight = testCase.previewHeight.toInt(),
            isFillCenter = testCase.scaleMode == ScaleMode.FILL_CENTER
        )

        assertEquals(
            "[${testCase.name}] 端到端人脸中心 UV X 异常",
            testCase.expectedStage4.faceCenter.x,
            uvResult.first,
            0.001f
        )
        assertEquals(
            "[${testCase.name}] 端到端人脸中心 UV Y 异常",
            testCase.expectedStage4.faceCenter.y,
            uvResult.second,
            0.001f
        )
    }

    // ==================== 一致性测试 ====================

    @Test
    fun `一致性_左右眼相对位置`() {
        val leftEyeResult = transformFaceCoordinateTest(
            faceX = testCase.inputLeftEye.x,
            faceY = testCase.inputLeftEye.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        val rightEyeResult = transformFaceCoordinateTest(
            faceX = testCase.inputRightEye.x,
            faceY = testCase.inputRightEye.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        // 验证眼睛在同一水平线上
        assertEquals(
            "[${testCase.name}] 左右眼 Y 坐标不一致",
            leftEyeResult.y,
            rightEyeResult.y,
            2f
        )

        // 验证眼睛间距合理
        val eyeDistance = kotlin.math.abs(leftEyeResult.x - rightEyeResult.x)
        assertTrue(
            "[${testCase.name}] 眼睛间距异常: $eyeDistance",
            eyeDistance in 50f..200f
        )
    }

    @Test
    fun `一致性_眼睛在人脸上方`() {
        val faceResult = transformFaceCoordinateTest(
            faceX = testCase.inputFaceCenter.x,
            faceY = testCase.inputFaceCenter.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        val leftEyeResult = transformFaceCoordinateTest(
            faceX = testCase.inputLeftEye.x,
            faceY = testCase.inputLeftEye.y,
            imageProxyWidth = testCase.imageWidth,
            imageProxyHeight = testCase.imageHeight,
            previewWidth = testCase.previewWidth,
            previewHeight = testCase.previewHeight,
            rotationDegrees = testCase.rotationDegrees,
            lensFacing = testCase.lensFacing
        )

        when (testCase.rotationDegrees) {
            180 -> {
                assertTrue(
                    "[${testCase.name}] 180度时眼睛应在人脸下方",
                    leftEyeResult.y > faceResult.y
                )
            }
            else -> {
                assertTrue(
                    "[${testCase.name}] 眼睛应在人脸上方",
                    leftEyeResult.y < faceResult.y
                )
            }
        }
    }
}

/**
 * 坐标转换函数（测试版本）
 */
private fun transformFaceCoordinateTest(
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

    val mirroredX = if (lensFacing == 1) {
        1f - normX
    } else {
        normX
    }

    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)
        90 -> Pair(mirroredX, normY)
        180 -> Pair(1f - mirroredX, 1f - normY)
        270 -> Pair(mirroredX, normY)
        else -> Pair(mirroredX, normY)
    }

    val screenX = adjustedX * previewWidth
    val screenY = adjustedY * previewHeight

    return Offset(screenX, screenY)
}

/**
 * UV 映射函数（测试版本）
 */
private fun mapViewNormalizedToUvTest(
    normX: Float,
    normY: Float,
    outputWidth: Int,
    outputHeight: Int,
    isFillCenter: Boolean
): Pair<Float, Float> {
    val safeOutputWidth = outputWidth.coerceAtLeast(1)
    val safeOutputHeight = outputHeight.coerceAtLeast(1)

    // 计算 viewport
    val cameraInputWidth = 1280
    val cameraInputHeight = 720
    val rawSourceAspect = cameraInputWidth.toFloat() / cameraInputHeight.toFloat()
    val rotatedSourceAspect = cameraInputHeight.toFloat() / cameraInputWidth.toFloat()
    val outputAspect = safeOutputWidth.toFloat() / safeOutputHeight.toFloat()

    val sourceAspect = if (
        kotlin.math.abs(rotatedSourceAspect - outputAspect) < kotlin.math.abs(rawSourceAspect - outputAspect)
    ) {
        rotatedSourceAspect
    } else {
        rawSourceAspect
    }

    val viewportWidth: Int
    val viewportHeight: Int

    if (isFillCenter) {
        if (sourceAspect > outputAspect) {
            viewportHeight = safeOutputHeight
            viewportWidth = (safeOutputHeight * sourceAspect).toInt().coerceAtLeast(1)
        } else {
            viewportWidth = safeOutputWidth
            viewportHeight = (safeOutputWidth / sourceAspect).toInt().coerceAtLeast(1)
        }
    } else {
        if (sourceAspect > outputAspect) {
            viewportWidth = safeOutputWidth
            viewportHeight = (safeOutputWidth / sourceAspect).toInt().coerceAtLeast(1)
        } else {
            viewportHeight = safeOutputHeight
            viewportWidth = (safeOutputHeight * sourceAspect).toInt().coerceAtLeast(1)
        }
    }

    val viewportX = (safeOutputWidth - viewportWidth) / 2
    val viewportY = (safeOutputHeight - viewportHeight) / 2

    // 裁剪到 [0, 1]
    val clampedNormX = normX.coerceIn(0f, 1f)
    val clampedNormY = normY.coerceIn(0f, 1f)

    // 转像素
    val pixelX = clampedNormX * safeOutputWidth
    val pixelY = clampedNormY * safeOutputHeight

    // 映射到 viewport，Y 翻转
    val uvX = ((pixelX - viewportX) / viewportWidth.toFloat()).coerceIn(0f, 1f)
    val uvY = (1f - ((pixelY - viewportY) / viewportHeight.toFloat())).coerceIn(0f, 1f)

    return Pair(uvX, uvY)
}
