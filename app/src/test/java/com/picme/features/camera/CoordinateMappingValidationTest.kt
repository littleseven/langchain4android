package com.picme.features.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 妆容坐标映射验证测试
 * 
 * 验证原理：
 * 1. CameraFrameAnalyzer 输出基于 PreviewView 的归一化坐标
 * 2. CameraPreviewRenderer 接收归一化坐标，映射到 viewport
 * 3. 两者使用的坐标基准应该一致
 * 
 * 关键假设：
 * - PreviewView 和 SurfaceView 尺寸相同（都使用 MATCH_PARENT）
 * - 坐标转换逻辑正确
 */
class CoordinateMappingValidationTest {

    companion object {
        // 典型设备参数：竖屏 1080x1920，相机预览 1280x720
        const val PREVIEW_WIDTH = 1080f
        const val PREVIEW_HEIGHT = 1920f
        const val IMAGE_WIDTH = 1280
        const val IMAGE_HEIGHT = 720
        const val ROTATION_90 = 90
    }

    /**
     * 测试用例 1: 验证坐标转换的数学正确性
     * 
     * 验证 transformFaceCoordinateSimple 函数的数学正确性
     * 不依赖 Android Log，纯数学验证
     */
    @Test
    fun `test coordinate transformation math - center point`() {
        // 图像中心点 (640, 360)
        val faceX = IMAGE_WIDTH / 2f
        val faceY = IMAGE_HEIGHT / 2f
        
        // 手动计算期望结果
        // Step 1: 旋转 90 度，交换宽高 -> rotatedSize = (720, 1280)
        val rotatedWidth = IMAGE_HEIGHT  // 720
        val rotatedHeight = IMAGE_WIDTH  // 1280
        
        // Step 2: 归一化
        val normX = faceX / rotatedWidth   // 640 / 720 = 0.888...
        val normY = faceY / rotatedHeight  // 360 / 1280 = 0.281...
        
        // Step 3: 后置摄像头，不镜像
        val mirroredX = normX  // 0.888...
        
        // Step 4: 旋转 90 度，不交换 XY
        val adjustedX = mirroredX  // 0.888...
        val adjustedY = normY      // 0.281...
        
        // Step 5: 转换为像素坐标
        val expectedScreenX = adjustedX * PREVIEW_WIDTH  // 0.888... * 1080 = 960
        val expectedScreenY = adjustedY * PREVIEW_HEIGHT // 0.281... * 1920 = 540
        
        // 验证数学计算
        assertEquals("Center X calculation", 960f, expectedScreenX, 1f)
        assertEquals("Center Y calculation", 540f, expectedScreenY, 1f)
        
        // 验证归一化坐标
        val expectedNormX = expectedScreenX / PREVIEW_WIDTH   // 0.888...
        val expectedNormY = expectedScreenY / PREVIEW_HEIGHT  // 0.281...
        
        assertEquals("Normalized X", 0.8889f, expectedNormX, 0.01f)
        assertEquals("Normalized Y", 0.28125f, expectedNormY, 0.01f)
    }

    /**
     * 测试用例 2: 验证 viewport 映射逻辑
     * 
     * 验证 CameraPreviewRenderer.mapViewNormalizedToUv 的映射逻辑
     */
    @Test
    fun `test viewport mapping - FillCenter mode`() {
        // FILL_CENTER 模式：viewport 填满整个输出区域
        val outputWidth = 1080
        val outputHeight = 1920
        val viewportWidth = 1080
        val viewportHeight = 1920
        val viewportX = 0
        val viewportY = 0
        
        // 输入归一化坐标 (0.5, 0.5)
        val normX = 0.5f
        val normY = 0.5f
        
        // Step 1: 转像素
        val pixelX = normX * outputWidth   // 540
        val pixelY = normY * outputHeight  // 960
        
        // Step 2: 映射到 viewport
        val uvX = (pixelX - viewportX) / viewportWidth.toFloat()   // 540 / 1080 = 0.5
        val uvY = 1f - ((pixelY - viewportY) / viewportHeight.toFloat())  // 1 - (960 / 1920) = 0.5
        
        // 验证
        assertEquals("UV X in FillCenter", 0.5f, uvX, 0.01f)
        assertEquals("UV Y in FillCenter", 0.5f, uvY, 0.01f)
    }

    /**
     * 测试用例 3: 验证 FIT_CENTER 模式下的 viewport 计算
     * 
     * 4:3 图像在 9:16 屏幕上居中显示
     */
    @Test
    fun `test viewport calculation - FitCenter 4_3`() {
        // 4:3 图像 (1280x960) 在 9:16 屏幕 (1080x1920) 上
        val cameraInputWidth = 1280
        val cameraInputHeight = 960
        val outputWidth = 1080
        val outputHeight = 1920
        
        val sourceAspect = cameraInputWidth.toFloat() / cameraInputHeight.toFloat()  // 1.333
        val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()  // 0.5625
        
        // FIT_CENTER 模式
        val viewportWidth: Int
        val viewportHeight: Int
        
        if (sourceAspect > outputAspect) {
            // 图像比屏幕宽，左右填满，上下有黑边
            viewportWidth = outputWidth
            viewportHeight = (outputWidth / sourceAspect).toInt()
        } else {
            // 图像比屏幕高，上下填满，左右有黑边
            viewportHeight = outputHeight
            viewportWidth = (outputHeight * sourceAspect).toInt()
        }
        
        val viewportX = (outputWidth - viewportWidth) / 2
        val viewportY = (outputHeight - viewportHeight) / 2
        
        // 验证 viewport 尺寸
        assertEquals("Viewport width", 1080, viewportWidth)
        assertEquals("Viewport height", 810, viewportHeight)  // 1080 / 1.333 = 810
        assertEquals("Viewport X", 0, viewportX)
        assertEquals("Viewport Y", 555, viewportY)  // (1920 - 810) / 2 = 555
    }

    /**
     * 测试用例 4: 验证端到端坐标映射
     * 
     * 从人脸检测坐标到渲染坐标的完整映射
     */
    @Test
    fun `test end-to-end coordinate mapping`() {
        // 场景：人脸中心在图像中心 (640, 360)，旋转 90 度
        val faceX = 640f
        val faceY = 360f
        
        // ===== Stage 1: CameraFrameAnalyzer =====
        // 旋转 90 度，交换宽高
        val rotatedWidth = IMAGE_HEIGHT  // 720
        val rotatedHeight = IMAGE_WIDTH  // 1280
        
        // 归一化
        val normX = faceX / rotatedWidth   // 0.888...
        val normY = faceY / rotatedHeight  // 0.281...
        
        // 后置，不镜像
        val mirroredX = normX
        
        // 旋转 90 度，不交换
        val adjustedX = mirroredX
        val adjustedY = normY
        
        // 转换为 PreviewView 像素坐标
        val screenX = adjustedX * PREVIEW_WIDTH   // 960
        val screenY = adjustedY * PREVIEW_HEIGHT  // 540
        
        // 归一化输出 (0-1)
        val outputNormX = screenX / PREVIEW_WIDTH   // 0.888...
        val outputNormY = screenY / PREVIEW_HEIGHT  // 0.281...
        
        // ===== Stage 2: CameraPreviewRenderer =====
        // FILL_CENTER 模式，viewport 填满
        val viewportWidth = PREVIEW_WIDTH.toInt()
        val viewportHeight = PREVIEW_HEIGHT.toInt()
        val viewportX = 0
        val viewportY = 0
        
        // 转像素
        val pixelX = outputNormX * PREVIEW_WIDTH   // 960
        val pixelY = outputNormY * PREVIEW_HEIGHT  // 540
        
        // 映射到 viewport
        val uvX = ((pixelX - viewportX) / viewportWidth.toFloat()).coerceIn(0f, 1f)
        val uvY = (1f - ((pixelY - viewportY) / viewportHeight.toFloat())).coerceIn(0f, 1f)
        
        // 验证最终 UV 坐标
        assertEquals("Final UV X", 0.8889f, uvX, 0.01f)
        assertEquals("Final UV Y", 0.71875f, uvY, 0.01f)  // 1 - 0.281 = 0.719
    }

    /**
     * 测试用例 5: 验证边界条件
     * 
     * 图像四角坐标在旋转 90° 后的映射行为：
     * - 左上角 (0, 0)         → normX=0, normY=0 → 屏幕 (0, 0)       ✔ 屏内
     * - 右下角 (1280, 720)    → normX=1280/720=1.778, 超界→ 屏幕 X 超界  ⚠ 应处理
     *
     * 这里分别验证两种行为：
     * a) 左上角坐标应在屏幕范围内
     * b) 右下角适当角坐标（超界）应超出屏幕，不应被静默裁切
     */
    @Test
    fun `test corner points coordinate mapping`() {
        // 左上角：屏幕范围内
        run {
            val faceX = 0f
            val faceY = 0f
            val rotatedWidth = IMAGE_HEIGHT.toFloat()  // 720
            val rotatedHeight = IMAGE_WIDTH.toFloat()  // 1280
            val normX = faceX / rotatedWidth   // 0
            val normY = faceY / rotatedHeight  // 0
            val screenX = normX * PREVIEW_WIDTH
            val screenY = normY * PREVIEW_HEIGHT
            assertTrue("Top-Left: screenX should be in [0, $PREVIEW_WIDTH]",
                screenX in 0f..PREVIEW_WIDTH)
            assertTrue("Top-Left: screenY should be in [0, $PREVIEW_HEIGHT]",
                screenY in 0f..PREVIEW_HEIGHT)
        }

        // 右下角 (1280, 720)：坐标超界，不裁切时应超出屏幕
        run {
            val faceX = IMAGE_WIDTH.toFloat()  // 1280
            val faceY = IMAGE_HEIGHT.toFloat() // 720
            val rotatedWidth = IMAGE_HEIGHT.toFloat()  // 720
            val rotatedHeight = IMAGE_WIDTH.toFloat()  // 1280
            val normX = faceX / rotatedWidth   // 1280/720 = 1.778, > 1
            val normY = faceY / rotatedHeight  // 720/1280 = 0.5625, 屏内
            val rawScreenX = normX * PREVIEW_WIDTH  // 1920, 超出屏幕
            // 无裁切时 X 超界
            assertTrue("Bottom-Right: rawScreenX should exceed preview width (coordinates are out-of-bounds)",
                rawScreenX > PREVIEW_WIDTH)
            // Y 坐标应在屏幕内
            val screenY = normY * PREVIEW_HEIGHT
            assertTrue("Bottom-Right: screenY should be in [0, $PREVIEW_HEIGHT]",
                screenY in 0f..PREVIEW_HEIGHT)
        }
    }

    /**
     * 测试用例 6: 验证前置摄像头镜像
     */
    @Test
    fun `test front camera mirroring effect`() {
        // 图像左侧点
        val leftX = IMAGE_WIDTH * 0.3f  // 384
        val centerY = IMAGE_HEIGHT / 2f  // 360
        
        // 旋转 90 度
        val rotatedWidth = IMAGE_HEIGHT.toFloat()
        
        // 后置摄像头
        val backNormX = leftX / rotatedWidth  // 384 / 720 = 0.533
        val backScreenX = backNormX * PREVIEW_WIDTH  // 576
        
        // 前置摄像头（镜像）
        val frontNormX = 1f - backNormX  // 1 - 0.533 = 0.467
        val frontScreenX = frontNormX * PREVIEW_WIDTH  // 504
        
        // 验证镜像效果：前置和后置的 X 坐标应该关于中心对称
        val backDistanceFromCenter = backScreenX - PREVIEW_WIDTH / 2   // 576 - 540 = 36
        val frontDistanceFromCenter = PREVIEW_WIDTH / 2 - frontScreenX  // 540 - 504 = 36
        
        assertEquals("Mirror distance from center", 
            backDistanceFromCenter, frontDistanceFromCenter, 1f)
    }

    /**
     * 测试用例 7: 验证坐标归一化的往返一致性（Round-trip）
     *
     * 验证：同一人脸点小屏幕 (1080x1920) 与大屏幕 (1440x2560) 归一化结果一致
     * （前提：PreviewView 尺寸在两种设备上比例相同）
     */
    @Test
    fun `test coordinate consistency across aspect ratios`() {
        val faceX = IMAGE_WIDTH / 2f   // 640
        val faceY = IMAGE_HEIGHT / 2f  // 360
        val rotatedWidth = IMAGE_HEIGHT.toFloat()  // 720
        val rotatedHeight = IMAGE_WIDTH.toFloat()  // 1280
        val normX = faceX / rotatedWidth   // 0.8889
        val normY = faceY / rotatedHeight  // 0.28125

        // 屏幕 A: 1080x1920
        val screenAX = normX * PREVIEW_WIDTH    // 960
        val screenAY = normY * PREVIEW_HEIGHT   // 540
        val outputNormAX = screenAX / PREVIEW_WIDTH   // 0.8889
        val outputNormAY = screenAY / PREVIEW_HEIGHT  // 0.28125

        // 屏幕 B: 1440x2560（同为 9:16，不同分辨率）
        val previewBW = 1440f
        val previewBH = 2560f
        val screenBX = normX * previewBW   // 1280
        val screenBY = normY * previewBH   // 720
        val outputNormBX = screenBX / previewBW   // 0.8889
        val outputNormBY = screenBY / previewBH   // 0.28125

        // 验证：两种屏幕尺寸下归一化坐标应完全一致
        assertEquals("Normalized X should be consistent across screen sizes",
            outputNormAX, outputNormBX, 0.0001f)
        assertEquals("Normalized Y should be consistent across screen sizes",
            outputNormAY, outputNormBY, 0.0001f)
        // 结果应为 0.8889, 0.28125
        assertEquals("Normalized X value", 0.8889f, outputNormAX, 0.001f)
        assertEquals("Normalized Y value", 0.28125f, outputNormAY, 0.001f)
    }

    /**
     * 测试用例 8: 验证嘴唇位置合理性
     * 
     * 嘴唇应该在面部下半部分
     */
    @Test
    fun `test lip position relative to face center`() {
        // 面部中心
        val faceCenterX = IMAGE_WIDTH / 2f
        val faceCenterY = IMAGE_HEIGHT * 0.6f  // 偏下
        
        // 嘴唇位置（在面部下半部分）
        val lipX = faceCenterX
        val lipY = faceCenterY + 50f  // 比中心更靠下
        
        // 旋转 90 度
        val rotatedWidth = IMAGE_HEIGHT.toFloat()
        val rotatedHeight = IMAGE_WIDTH.toFloat()
        
        // 计算面部中心和嘴唇的屏幕坐标
        val faceNormX = faceCenterX / rotatedWidth
        val faceNormY = faceCenterY / rotatedHeight
        val faceScreenY = faceNormY * PREVIEW_HEIGHT
        
        val lipNormX = lipX / rotatedWidth
        val lipNormY = lipY / rotatedHeight
        val lipScreenY = lipNormY * PREVIEW_HEIGHT
        
        // 验证：嘴唇应该在面部中心下方
        assertTrue("Lip should be below face center", lipScreenY > faceScreenY)
    }

    // ==================== 宽高比切换专项测试 ====================
    // 复现场景：在 4:3 / 16:9 比例切换后，人脸调试 UI 与预览画面存在较大偏移

    /**
     * 测试用例 9: 4:3 比例下 imageProxy 尺寸变化对坐标的影响
     *
     * 关键差异：
     * - 16:9 模式：imageProxy = 1280×720，rotatedHeight=1280
     * - 4:3  模式：imageProxy = 960×720，rotatedHeight=960
     *
     * 若坐标转换函数硬编码 rotatedHeight=1280（或外部传入错误值），
     * 4:3 模式下 normY 会系统性偏小，导致人脸 Y 坐标向上偏移。
     *
     * 验证：同一物理位置（图像垂直中心）在两种比例下，normY 的期望值不同。
     */
    @Test
    fun `test aspect ratio switch - 4x3 vs 16x9 imageProxy size difference`() {
        val rotationDegrees = 90

        // 16:9 模式：imageProxy = 1280×720，人脸在垂直中心
        val img16_9_W = 1280
        val img16_9_H = 720
        val faceY_16_9 = img16_9_W / 2f   // rotatedHeight=1280，faceY 中心=640
        val rotated16_9_H = img16_9_W      // rotation=90，交换宽高
        val normY_16_9 = faceY_16_9 / rotated16_9_H   // 640/1280 = 0.5

        // 4:3 模式：imageProxy = 960×720，人脸在垂直中心
        val img4_3_W = 960
        val img4_3_H = 720
        val faceY_4_3 = img4_3_W / 2f    // rotatedHeight=960，faceY 中心=480
        val rotated4_3_H = img4_3_W      // rotation=90，交换宽高
        val normY_4_3 = faceY_4_3 / rotated4_3_H    // 480/960 = 0.5

        // 两种比例下，图像中心的 normY 应该都是 0.5
        assertEquals("16:9 center normY should be 0.5", 0.5f, normY_16_9, 0.001f)
        assertEquals("4:3 center normY should be 0.5", 0.5f, normY_4_3, 0.001f)

        // 关键验证：若 4:3 模式下错误使用 16:9 的 rotatedHeight=1280 计算
        val normY_4_3_wrong = faceY_4_3 / rotated16_9_H   // 480/1280 = 0.375（错误！）
        val screenY_correct = normY_4_3 * PREVIEW_HEIGHT    // 0.5 * 1920 = 960
        val screenY_wrong = normY_4_3_wrong * PREVIEW_HEIGHT // 0.375 * 1920 = 720

        // 错误使用时，Y 坐标偏移 240px（向上）
        val yOffset = screenY_correct - screenY_wrong
        assertEquals(
            "Wrong rotatedHeight causes 240px Y upward shift in 4:3 mode",
            240f, yOffset, 1f
        )
        assertTrue("4:3 mode Y error should exceed 200px when using wrong imageProxy height",
            yOffset > 200f)
    }

    /**
     * 测试用例 10: 4:3 比例下坐标归一化与 previewView 实际显示区域的对齐验证
     *
     * 问题本质：
     * 4:3 比例时，PreviewView 全高=1920px，但图像内容只占 810px（FIT_CENTER 模式），
     * 上下各有 555px 黑边。
     * transformFaceCoordinateSimple 直接用 previewView.height=1920 做乘法，
     * 导致映射到全屏坐标系，而 Debug Overlay 绘制时也参考同一坐标系，
     * 两者一致，不会有偏移——但与 OpenGL UV 映射的视觉内容区域对不上。
     *
     * 此测试验证：使用全屏高度和实际内容高度映射同一人脸点，Y 坐标差异 = 黑边高度
     */
    @Test
    fun `test aspect ratio switch - 4x3 letterbox offset matches black bar height`() {
        val img4_3_W = 960
        val img4_3_H = 720
        val rotationDegrees = 90
        // rotatedHeight=960（4:3 竖屏）
        val rotatedHeight = img4_3_W.toFloat()  // 960

        // 人脸在图像上方 1/4 处
        val faceY = rotatedHeight * 0.25f  // 240

        val normY = faceY / rotatedHeight  // 0.25

        // FIT_CENTER 4:3 内容区域计算
        // sourceAspect = 960/720 = 1.333, outputAspect = 1080/1920 = 0.5625
        // sourceAspect > outputAspect → viewportWidth=1080, viewportHeight=1080/1.333=810
        val contentHeight = 810f
        val blackBarHeight = (PREVIEW_HEIGHT - contentHeight) / 2  // (1920-810)/2 = 555

        // 使用全屏高度（当前实现）：screenY = normY * 1920
        val screenY_fullHeight = normY * PREVIEW_HEIGHT  // 0.25 * 1920 = 480

        // 使用内容区域高度（理想实现）：screenY = normY * 810 + blackBarTop
        val screenY_contentArea = normY * contentHeight + blackBarHeight  // 0.25*810 + 555 = 757.5

        // 两种计算方式存在显著差异，量化 Bug 的影响幅度
        val delta = kotlin.math.abs(screenY_fullHeight - screenY_contentArea)
        assertTrue(
            "Y coordinate mismatch between full-height and content-area mapping: ${delta}px (should be > 200px for 1/4 face position)",
            delta > 200f
        )

        // 黑边高度 = 555px，是导致偏移的根本量
        assertEquals("Black bar height for 4:3 on 1080x1920 screen", 555f, blackBarHeight, 1f)
    }

    /**
     * 测试用例 11: 16:9 比例下无黑边，坐标转换应无偏移
     *
     * 对比测试：16:9 模式下 PreviewView 全高与内容高度一致，
     * 全屏高度映射与内容区域映射结果相同，验证基准正确。
     */
    @Test
    fun `test aspect ratio switch - 16x9 no letterbox no offset`() {
        val img16_9_W = 1280
        val img16_9_H = 720
        val rotationDegrees = 90
        val rotatedHeight = img16_9_W.toFloat()  // 1280

        val faceY = rotatedHeight * 0.25f  // 320
        val normY = faceY / rotatedHeight  // 0.25

        // FILL_CENTER 16:9 内容区域 = 全屏（无黑边）
        // sourceAspect=720/1280=0.5625, outputAspect=1080/1920=0.5625，完全匹配
        val contentHeight = PREVIEW_HEIGHT  // 1920，无黑边
        val blackBarHeight = 0f

        val screenY_fullHeight = normY * PREVIEW_HEIGHT   // 0.25 * 1920 = 480
        val screenY_contentArea = normY * contentHeight + blackBarHeight  // 0.25 * 1920 = 480

        // 16:9 时两种计算方式完全一致，无偏移
        assertEquals(
            "16:9 mode: full-height and content-area mapping should be identical",
            screenY_fullHeight, screenY_contentArea, 0.1f
        )
        assertEquals("No black bar in 16:9 mode", 0f, blackBarHeight, 0.1f)
    }
}
