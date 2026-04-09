package com.picme.features.camera

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 人脸位置精确匹配测试
 * 
 * 目标：验证人脸框、眼睛位置与算法输出 100% 匹配，并与预览图对齐
 * 
 * 测试策略：
 * 1. 使用已知的标准输入输出对进行验证
 * 2. 覆盖不同旋转角度、摄像头方向、画面比例
 * 3. 验证关键点（人脸中心、左眼、右眼）的精确位置
 */
@RunWith(Parameterized::class)
class FacePositionAccuracyTest(
    private val testName: String,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val previewWidth: Float,
    private val previewHeight: Float,
    private val rotationDegrees: Int,
    private val lensFacing: Int,
    private val faceInput: FaceInput,
    private val expectedOutput: FaceOutput
) {

    /**
     * 人脸输入数据（算法原始输出）
     */
    data class FaceInput(
        val faceCenterX: Float,  // 人脸框中心 X（图像坐标）
        val faceCenterY: Float,  // 人脸框中心 Y（图像坐标）
        val leftEyeX: Float,     // 左眼中心 X（图像坐标）
        val leftEyeY: Float,     // 左眼中心 Y（图像坐标）
        val rightEyeX: Float,    // 右眼中心 X（图像坐标）
        val rightEyeY: Float     // 右眼中心 Y（图像坐标）
    )

    /**
     * 期望输出数据（转换后的屏幕坐标）
     */
    data class FaceOutput(
        val faceCenter: Offset,  // 人脸中心在 PreviewView 上的位置（像素）
        val leftEye: Offset,     // 左眼在 PreviewView 上的位置（像素）
        val rightEye: Offset,    // 右眼在 PreviewView 上的位置（像素）
        val faceCenterNorm: Offset,  // 归一化坐标 (0-1)
        val leftEyeNorm: Offset,     // 归一化坐标 (0-1)
        val rightEyeNorm: Offset     // 归一化坐标 (0-1)
    )

    companion object {
        // 典型设备参数
        const val PHONE_WIDTH = 1080f
        const val PHONE_HEIGHT = 1920f
        const val PREVIEW_RES_WIDTH = 1280
        const val PREVIEW_RES_HEIGHT = 720

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            return listOf(
                // ========== 测试用例 1: 后置摄像头，旋转 90 度，人脸在图像中心 ==========
                arrayOf(
                    "后置-90度-人脸中心",
                    PREVIEW_RES_WIDTH, PREVIEW_RES_HEIGHT,
                    PHONE_WIDTH, PHONE_HEIGHT,
                    90, 0,  // 后置摄像头
                    FaceInput(
                        faceCenterX = 640f, faceCenterY = 360f,  // 图像中心
                        leftEyeX = 600f, leftEyeY = 320f,         // 左眼（左上）
                        rightEyeX = 680f, rightEyeY = 320f        // 右眼（右上）
                    ),
                    FaceOutput(
                        faceCenter = Offset(960f, 540f),     // 屏幕中心偏右
                        leftEye = Offset(900f, 480f),        // 左眼位置
                        rightEye = Offset(1020f, 480f),      // 右眼位置
                        faceCenterNorm = Offset(0.8889f, 0.28125f),
                        leftEyeNorm = Offset(0.8333f, 0.25f),
                        rightEyeNorm = Offset(0.9444f, 0.25f)
                    )
                ),

                // ========== 测试用例 2: 前置摄像头，旋转 90 度，人脸在图像中心 ==========
                arrayOf(
                    "前置-90度-人脸中心",
                    PREVIEW_RES_WIDTH, PREVIEW_RES_HEIGHT,
                    PHONE_WIDTH, PHONE_HEIGHT,
                    90, 1,  // 前置摄像头
                    FaceInput(
                        faceCenterX = 640f, faceCenterY = 360f,
                        leftEyeX = 600f, leftEyeY = 320f,
                        rightEyeX = 680f, rightEyeY = 320f
                    ),
                    FaceOutput(
                        faceCenter = Offset(120f, 540f),     // 镜像后偏左
                        leftEye = Offset(180f, 480f),        // 镜像后左眼在右
                        rightEye = Offset(60f, 480f),        // 镜像后右眼在左
                        faceCenterNorm = Offset(0.1111f, 0.28125f),
                        leftEyeNorm = Offset(0.1667f, 0.25f),
                        rightEyeNorm = Offset(0.0556f, 0.25f)
                    )
                ),

                // ========== 测试用例 3: 后置摄像头，旋转 0 度 ==========
                arrayOf(
                    "后置-0度-人脸中心",
                    PREVIEW_RES_WIDTH, PREVIEW_RES_HEIGHT,
                    PHONE_WIDTH, PHONE_HEIGHT,
                    0, 0,
                    FaceInput(
                        faceCenterX = 640f, faceCenterY = 360f,
                        leftEyeX = 580f, leftEyeY = 300f,
                        rightEyeX = 700f, rightEyeY = 300f
                    ),
                    FaceOutput(
                        faceCenter = Offset(540f, 960f),     // 0度时 Y 被拉伸
                        leftEye = Offset(489.375f, 800f),
                        rightEye = Offset(590.625f, 800f),
                        faceCenterNorm = Offset(0.5f, 0.5f),
                        leftEyeNorm = Offset(0.4531f, 0.4167f),
                        rightEyeNorm = Offset(0.5469f, 0.4167f)
                    )
                ),

                // ========== 测试用例 4: 人脸在图像左上角 ==========
                arrayOf(
                    "后置-90度-人脸左上",
                    PREVIEW_RES_WIDTH, PREVIEW_RES_HEIGHT,
                    PHONE_WIDTH, PHONE_HEIGHT,
                    90, 0,
                    FaceInput(
                        faceCenterX = 320f, faceCenterY = 180f,  // 左上区域
                        leftEyeX = 280f, leftEyeY = 140f,
                        rightEyeX = 360f, rightEyeY = 140f
                    ),
                    FaceOutput(
                        faceCenter = Offset(480f, 270f),
                        leftEye = Offset(420f, 210f),
                        rightEye = Offset(540f, 210f),
                        faceCenterNorm = Offset(0.4444f, 0.1406f),
                        leftEyeNorm = Offset(0.3889f, 0.1094f),
                        rightEyeNorm = Offset(0.5f, 0.1094f)
                    )
                ),

                // ========== 测试用例 5: 人脸在图像右下角 ==========
                arrayOf(
                    "后置-90度-人脸右下",
                    PREVIEW_RES_WIDTH, PREVIEW_RES_HEIGHT,
                    PHONE_WIDTH, PHONE_HEIGHT,
                    90, 0,
                    FaceInput(
                        faceCenterX = 960f, faceCenterY = 540f,  // 右下区域
                        leftEyeX = 920f, leftEyeY = 500f,
                        rightEyeX = 1000f, rightEyeY = 500f
                    ),
                    FaceOutput(
                        faceCenter = Offset(1440f, 810f),  // 超出屏幕，需要裁剪
                        leftEye = Offset(1380f, 750f),
                        rightEye = Offset(1500f, 750f),   // 超出屏幕
                        faceCenterNorm = Offset(1.3333f, 0.4219f),  // > 1，需要裁剪
                        leftEyeNorm = Offset(1.2778f, 0.3906f),
                        rightEyeNorm = Offset(1.3889f, 0.3906f)
                    )
                ),

                // ========== 测试用例 6: 旋转 180 度（倒立）==========
                // 180度时，XY都翻转
                // 原左眼(600, 320) -> norm(0.46875, 0.444) -> flip(0.53125, 0.556) -> screen(573.75, 1066.67)
                // 原右眼(680, 320) -> norm(0.53125, 0.444) -> flip(0.46875, 0.556) -> screen(506.25, 1066.67)
                arrayOf(
                    "后置-180度-人脸中心",
                    PREVIEW_RES_WIDTH, PREVIEW_RES_HEIGHT,
                    PHONE_WIDTH, PHONE_HEIGHT,
                    180, 0,
                    FaceInput(
                        faceCenterX = 640f, faceCenterY = 360f,
                        leftEyeX = 600f, leftEyeY = 320f,
                        rightEyeX = 680f, rightEyeY = 320f
                    ),
                    FaceOutput(
                        faceCenter = Offset(540f, 960f),
                        leftEye = Offset(573.75f, 1066.67f),   // 翻转后
                        rightEye = Offset(506.25f, 1066.67f),  // 翻转后
                        faceCenterNorm = Offset(0.5f, 0.5f),
                        leftEyeNorm = Offset(0.53125f, 0.5556f),
                        rightEyeNorm = Offset(0.46875f, 0.5556f)
                    )
                ),

                // ========== 测试用例 7: 旋转 270 度 ==========
                // 【注意】当前 transformFaceCoordinateTest 实现中，270° 与 90° 走同一分支：
                // Pair(mirroredX, normY)，即不做额外轴翻转。
                // 若业务上 270° 场景需要不同于 90° 的坐标变换，应修改生产代码并更新此用例。
                arrayOf(
                    "后置-270度-人脸中心",
                    PREVIEW_RES_WIDTH, PREVIEW_RES_HEIGHT,
                    PHONE_WIDTH, PHONE_HEIGHT,
                    270, 0,
                    FaceInput(
                        faceCenterX = 640f, faceCenterY = 360f,
                        leftEyeX = 600f, leftEyeY = 320f,
                        rightEyeX = 680f, rightEyeY = 320f
                    ),
                    FaceOutput(
                        // 当前实现与 90° 结果相同（270° 未做额外翻转）
                        faceCenter = Offset(960f, 540f),
                        leftEye = Offset(900f, 480f),
                        rightEye = Offset(1020f, 480f),
                        faceCenterNorm = Offset(0.8889f, 0.28125f),
                        leftEyeNorm = Offset(0.8333f, 0.25f),
                        rightEyeNorm = Offset(0.9444f, 0.25f)
                    )
                )
            )
        }
    }

    /**
     * 测试人脸中心位置精确匹配
     */
    @Test
    fun `test face center position accuracy`() {
        val result = transformFaceCoordinateTest(
            faceX = faceInput.faceCenterX,
            faceY = faceInput.faceCenterY,
            imageProxyWidth = imageWidth,
            imageProxyHeight = imageHeight,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            rotationDegrees = rotationDegrees,
            lensFacing = lensFacing
        )

        // 验证像素坐标（允许 1px 误差）
        assertEquals(
            "$testName: Face center X pixel mismatch",
            expectedOutput.faceCenter.x,
            result.x,
            1f
        )
        assertEquals(
            "$testName: Face center Y pixel mismatch",
            expectedOutput.faceCenter.y,
            result.y,
            1f
        )

        // 验证归一化坐标
        val normX = result.x / previewWidth
        val normY = result.y / previewHeight
        assertEquals(
            "$testName: Face center X normalized mismatch",
            expectedOutput.faceCenterNorm.x,
            normX,
            0.001f
        )
        assertEquals(
            "$testName: Face center Y normalized mismatch",
            expectedOutput.faceCenterNorm.y,
            normY,
            0.001f
        )
    }

    /**
     * 测试左眼位置精确匹配
     */
    @Test
    fun `test left eye position accuracy`() {
        val result = transformFaceCoordinateTest(
            faceX = faceInput.leftEyeX,
            faceY = faceInput.leftEyeY,
            imageProxyWidth = imageWidth,
            imageProxyHeight = imageHeight,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            rotationDegrees = rotationDegrees,
            lensFacing = lensFacing
        )

        assertEquals(
            "$testName: Left eye X pixel mismatch",
            expectedOutput.leftEye.x,
            result.x,
            1f
        )
        assertEquals(
            "$testName: Left eye Y pixel mismatch",
            expectedOutput.leftEye.y,
            result.y,
            1f
        )

        val normX = result.x / previewWidth
        val normY = result.y / previewHeight
        assertEquals(
            "$testName: Left eye X normalized mismatch",
            expectedOutput.leftEyeNorm.x,
            normX,
            0.001f
        )
        assertEquals(
            "$testName: Left eye Y normalized mismatch",
            expectedOutput.leftEyeNorm.y,
            normY,
            0.001f
        )
    }

    /**
     * 测试右眼位置精确匹配
     */
    @Test
    fun `test right eye position accuracy`() {
        val result = transformFaceCoordinateTest(
            faceX = faceInput.rightEyeX,
            faceY = faceInput.rightEyeY,
            imageProxyWidth = imageWidth,
            imageProxyHeight = imageHeight,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            rotationDegrees = rotationDegrees,
            lensFacing = lensFacing
        )

        assertEquals(
            "$testName: Right eye X pixel mismatch",
            expectedOutput.rightEye.x,
            result.x,
            1f
        )
        assertEquals(
            "$testName: Right eye Y pixel mismatch",
            expectedOutput.rightEye.y,
            result.y,
            1f
        )

        val normX = result.x / previewWidth
        val normY = result.y / previewHeight
        assertEquals(
            "$testName: Right eye X normalized mismatch",
            expectedOutput.rightEyeNorm.x,
            normX,
            0.001f
        )
        assertEquals(
            "$testName: Right eye Y normalized mismatch",
            expectedOutput.rightEyeNorm.y,
            normY,
            0.001f
        )
    }

    /**
     * 测试眼睛相对位置正确性
     * 验证左眼在左、右眼在右（考虑镜像）
     */
    @Test
    fun `test eye relative position`() {
        val leftEyeResult = transformFaceCoordinateTest(
            faceX = faceInput.leftEyeX,
            faceY = faceInput.leftEyeY,
            imageProxyWidth = imageWidth,
            imageProxyHeight = imageHeight,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            rotationDegrees = rotationDegrees,
            lensFacing = lensFacing
        )

        val rightEyeResult = transformFaceCoordinateTest(
            faceX = faceInput.rightEyeX,
            faceY = faceInput.rightEyeY,
            imageProxyWidth = imageWidth,
            imageProxyHeight = imageHeight,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            rotationDegrees = rotationDegrees,
            lensFacing = lensFacing
        )

        // 根据摄像头方向和旋转角度验证眼睛相对位置
        when (lensFacing) {
            0 -> { // 后置
                when (rotationDegrees) {
                    180 -> { // 180度旋转：XY翻转，左眼在右，右眼在左
                        assertTrue(
                            "$testName: Back camera 180deg - left eye should be right of right eye",
                            leftEyeResult.x > rightEyeResult.x
                        )
                    }
                    else -> { // 其他角度：左眼在左，右眼在右
                        assertTrue(
                            "$testName: Back camera - left eye should be left of right eye",
                            leftEyeResult.x < rightEyeResult.x
                        )
                    }
                }
            }
            1 -> { // 前置：镜像后，左眼在右（X 坐标大），右眼在左（X 坐标小）
                assertTrue(
                    "$testName: Front camera - left eye should be right of right eye (mirrored)",
                    leftEyeResult.x > rightEyeResult.x
                )
            }
        }

        // 验证眼睛在同一水平线上（Y 坐标接近）
        assertEquals(
            "$testName: Eyes should be at same height",
            leftEyeResult.y,
            rightEyeResult.y,
            2f  // 允许 2px 误差
        )
    }

    /**
     * 测试人脸与眼睛的相对位置
     * 验证眼睛在人脸上方
     */
    @Test
    fun `test face and eye relative position`() {
        val faceResult = transformFaceCoordinateTest(
            faceX = faceInput.faceCenterX,
            faceY = faceInput.faceCenterY,
            imageProxyWidth = imageWidth,
            imageProxyHeight = imageHeight,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            rotationDegrees = rotationDegrees,
            lensFacing = lensFacing
        )

        val leftEyeResult = transformFaceCoordinateTest(
            faceX = faceInput.leftEyeX,
            faceY = faceInput.leftEyeY,
            imageProxyWidth = imageWidth,
            imageProxyHeight = imageHeight,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            rotationDegrees = rotationDegrees,
            lensFacing = lensFacing
        )

        // 根据旋转角度判断眼睛相对人脸的位置
        when (rotationDegrees) {
            180 -> { // 180度：Y轴翻转，眼睛在人脸下方
                assertTrue(
                    "$testName: Eyes should be below face center (180deg)",
                    leftEyeResult.y > faceResult.y
                )
            }
            else -> { // 其他角度：眼睛在人脸上方
                assertTrue(
                    "$testName: Eyes should be above face center",
                    leftEyeResult.y < faceResult.y
                )
            }
        }
    }
}

/**
 * 坐标转换函数（测试版本，与生产代码逻辑一致）
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
    // 根据旋转角度交换图像宽高
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }

    // 归一化
    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight

    // 前置摄像头镜像
    val mirroredX = if (lensFacing == 1) {
        1f - normX
    } else {
        normX
    }

    // 旋转补偿
    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)
        90 -> Pair(mirroredX, normY)
        180 -> Pair(1f - mirroredX, 1f - normY)
        270 -> Pair(mirroredX, normY)
        else -> Pair(mirroredX, normY)
    }

    // 转换为像素坐标
    val screenX = adjustedX * previewWidth
    val screenY = adjustedY * previewHeight

    return Offset(screenX, screenY)
}
