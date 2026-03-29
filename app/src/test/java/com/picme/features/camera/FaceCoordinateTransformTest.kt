package com.picme.features.camera

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RD] 人脸坐标转换单元测试（简化版 - 纯 Kotlin）
 * 
 * 测试目标：验证 transformFaceCoordinate 函数在不同摄像头、旋转角度下的正确性
 */
class FaceCoordinateTransformTest {

    // 摄像头方向常量（使用 CameraX 的值）
    private val LENS_FACING_FRONT = 1  // CameraSelector.LENS_FACING_FRONT
    private val LENS_FACING_BACK = 0   // CameraSelector.LENS_FACING_BACK

    // 测试数据：传感器尺寸 1280x720，PreviewView 尺寸 1200x2670
    private val imageProxyWidth = 1280
    private val imageProxyHeight = 720
    private val previewWidth = 1200f
    private val previewHeight = 2670f

    // 简化的测试实现（复制主逻辑）
    private fun transformFaceCoordinate(
        faceX: Float,
        faceY: Float,
        rotationDegrees: Int,
        lensFacing: Int
    ): Offset {
        // Step 1: 归一化
        val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
            90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
            else -> Pair(imageProxyWidth, imageProxyHeight)
        }
        
        val normX = faceX / rotatedWidth
        val normY = faceY / rotatedHeight
        
        // Step 2: 镜像处理
        val mirroredX = if (lensFacing == LENS_FACING_FRONT) {
            1f - normX
        } else {
            normX
        }
        
        // Step 3: 旋转补偿
        val (adjustedX, adjustedY) = when (rotationDegrees) {
            0 -> Pair(mirroredX, normY)
            90 -> Pair(normY, mirroredX)
            180 -> Pair(1f - mirroredX, 1f - normY)
            270 -> Pair(mirroredX, normY)
            else -> Pair(mirroredX, normY)
        }
        
        // Step 4: 转换为像素坐标
        val screenX = adjustedX * previewWidth
        val screenY = adjustedY * previewHeight
        
        return Offset(screenX, screenY)
    }

    /**
     * 测试场景 1：前置摄像头 rot=270°，人脸向右移动
     * 预期：十字星向左移动（镜像效果）
     */
    @Test
    fun testFrontCamera_Rot270_MoveRight() {
        // 初始位置
        val result1 = transformFaceCoordinate(360f, 640f, 270, LENS_FACING_FRONT)
        
        // 向右移动后
        val result2 = transformFaceCoordinate(432f, 640f, 270, LENS_FACING_FRONT)
        
        println("\n前置 rot=270° 向右移动测试:")
        println("  初始：face=(360, 640) → screen=(${result1.x.toInt()}, ${result1.y.toInt()})")
        println("  右移：face=(432, 640) → screen=(${result2.x.toInt()}, ${result2.y.toInt()})")
        println("  X 变化：ΔscreenX=${result2.x - result1.x}")
        
        // 关键验证：人脸向右，十字星应该向左（镜像）
        assert(result2.x < result1.x) { 
            "❌ 失败：人脸向右移动，十字星应该向左移动！\n" +
            "  ΔscreenX=${result2.x - result1.x} (应该<0，实际>0)"
        }
    }

    /**
     * 测试场景 2：前置摄像头 rot=270°，人脸向上移动
     * 预期：十字星向上移动
     */
    @Test
    fun testFrontCamera_Rot270_MoveUp() {
        // 初始位置
        val result1 = transformFaceCoordinate(360f, 640f, 270, LENS_FACING_FRONT)
        
        // 向上移动后
        val result2 = transformFaceCoordinate(360f, 512f, 270, LENS_FACING_FRONT)
        
        println("\n前置 rot=270° 向上移动测试:")
        println("  初始：face=(360, 640) → screen=(${result1.x.toInt()}, ${result1.y.toInt()})")
        println("  上移：face=(360, 512) → screen=(${result2.x.toInt()}, ${result2.y.toInt()})")
        println("  Y 变化：ΔscreenY=${result2.y - result1.y}")
        
        // 关键验证：人脸向上，十字星也应该向上
        assert(result2.y < result1.y) {
            "❌ 失败：人脸向上移动，十字星应该向上移动！\n" +
            "  ΔscreenY=${result2.y - result1.y} (应该<0)"
        }
    }

    /**
     * 测试场景 3：后置摄像头 rot=270°，人脸向右移动
     * 预期：十字星向右移动（无镜像）
     */
    @Test
    fun testBackCamera_Rot270_MoveRight() {
        // 初始位置
        val result1 = transformFaceCoordinate(360f, 640f, 270, LENS_FACING_BACK)
        
        // 向右移动后
        val result2 = transformFaceCoordinate(432f, 640f, 270, LENS_FACING_BACK)
        
        println("\n后置 rot=270° 向右移动测试:")
        println("  初始：face=(360, 640) → screen=(${result1.x.toInt()}, ${result1.y.toInt()})")
        println("  右移：face=(432, 640) → screen=(${result2.x.toInt()}, ${result2.y.toInt()})")
        println("  X 变化：ΔscreenX=${result2.x - result1.x}")
        
        // 关键验证：人脸向右，十字星也应该向右
        assert(result2.x > result1.x) {
            "❌ 失败：人脸向右移动，十字星应该向右移动！\n" +
            "  ΔscreenX=${result2.x - result1.x} (应该>0)"
        }
    }

    /**
     * 测试场景 4：后置摄像头 rot=270°，人脸向上移动
     * 预期：十字星向上移动
     */
    @Test
    fun testBackCamera_Rot270_MoveUp() {
        // 初始位置
        val result1 = transformFaceCoordinate(360f, 640f, 270, LENS_FACING_BACK)
        
        // 向上移动后
        val result2 = transformFaceCoordinate(360f, 512f, 270, LENS_FACING_BACK)
        
        println("\n后置 rot=270° 向上移动测试:")
        println("  初始：face=(360, 640) → screen=(${result1.x.toInt()}, ${result1.y.toInt()})")
        println("  上移：face=(360, 512) → screen=(${result2.x.toInt()}, ${result2.y.toInt()})")
        println("  Y 变化：ΔscreenY=${result2.y - result1.y}")
        
        // 关键验证：人脸向上，十字星也应该向上
        assert(result2.y < result1.y) {
            "❌ 失败：人脸向上移动，十字星应该向上移动！\n" +
            "  ΔscreenY=${result2.y - result1.y} (应该<0)"
        }
    }
}
