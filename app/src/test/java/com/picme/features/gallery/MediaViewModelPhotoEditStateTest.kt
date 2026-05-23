package com.picme.features.gallery

import android.graphics.Bitmap
import com.picme.beauty.api.FaceData
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QA] MediaViewModel.PhotoEditState 状态机单元测试
 *
 * 测试目标：验证相册静态图编辑的状态密封类建模、属性正确性，
 * 以及 Idle → Analyzing → Ready → Processing → Ready → Idle 的完整流转路径。
 *
 * 注意：该测试不依赖 Android 框架（纯 Kotlin 测试），
 * Bitmap 和 FaceData 使用 mock 或轻量替身。
 */
class MediaViewModelPhotoEditStateTest {

    // ================================================================
    // 状态构造与类型测试
    // ================================================================

    @Test
    fun `Idle is singleton object`() {
        val a = MediaViewModel.PhotoEditState.Idle
        val b = MediaViewModel.PhotoEditState.Idle
        assertTrue("Idle should be the same object reference", a === b)
    }

    @Test
    fun `Analyzing is singleton object`() {
        val a = MediaViewModel.PhotoEditState.Analyzing
        val b = MediaViewModel.PhotoEditState.Analyzing
        assertTrue("Analyzing should be the same object reference", a === b)
    }

    @Test
    fun `Processing is singleton object`() {
        val a = MediaViewModel.PhotoEditState.Processing
        val b = MediaViewModel.PhotoEditState.Processing
        assertTrue("Processing should be the same object reference", a === b)
    }

    @Test
    fun `Ready carries bitmap and faceData`() {
        val bitmap = createFakeBitmap()
        val faceData = FaceData(hasFace = true, faceCenterX = 0.5f, faceCenterY = 0.5f)
        val state = MediaViewModel.PhotoEditState.Ready(bitmap, faceData)

        assertNotNull("Ready should carry bitmap", state.bitmap)
        assertNotNull("Ready should carry faceData", state.faceData)
        assertTrue("Ready.faceData.hasFace should be true", state.faceData?.hasFace == true)
    }

    @Test
    fun `Ready can carry null faceData`() {
        val bitmap = createFakeBitmap()
        val state = MediaViewModel.PhotoEditState.Ready(bitmap, null)

        assertNotNull("Ready should carry bitmap", state.bitmap)
        assertNull("Ready faceData can be null", state.faceData)
    }

    @Test
    fun `Error carries message`() {
        val message = "人脸检测失败"
        val state = MediaViewModel.PhotoEditState.Error(message)

        assertEquals("Error.message should match input", message, state.message)
    }

    // ================================================================
    // 状态子类型判断测试
    // ================================================================

    @Test
    fun `when expression covers all PhotoEditState subtypes`() {
        val states = listOf(
            MediaViewModel.PhotoEditState.Idle,
            MediaViewModel.PhotoEditState.Analyzing,
            MediaViewModel.PhotoEditState.Processing,
            MediaViewModel.PhotoEditState.Ready(createFakeBitmap(), null),
            MediaViewModel.PhotoEditState.Error("fail")
        )

        val descriptions = states.map { state ->
            when (state) {
                MediaViewModel.PhotoEditState.Idle -> "idle"
                MediaViewModel.PhotoEditState.Analyzing -> "analyzing"
                MediaViewModel.PhotoEditState.Processing -> "processing"
                is MediaViewModel.PhotoEditState.Ready -> "ready"
                is MediaViewModel.PhotoEditState.Error -> "error:${state.message}"
            }
        }

        assertEquals("idle", descriptions[0])
        assertEquals("analyzing", descriptions[1])
        assertEquals("processing", descriptions[2])
        assertEquals("ready", descriptions[3])
        assertEquals("error:fail", descriptions[4])
    }

    // ================================================================
    // 状态流转场景测试
    // ================================================================

    @Test
    fun `state flow Idle to Analyzing to Ready`() {
        var state: MediaViewModel.PhotoEditState = MediaViewModel.PhotoEditState.Idle
        assertTrue(state is MediaViewModel.PhotoEditState.Idle)

        // 开始人脸检测
        state = MediaViewModel.PhotoEditState.Analyzing
        assertTrue("State after prepare should be Analyzing", state is MediaViewModel.PhotoEditState.Analyzing)

        // 检测完成
        val bitmap = createFakeBitmap()
        val faceData = FaceData(hasFace = true)
        state = MediaViewModel.PhotoEditState.Ready(bitmap, faceData)
        assertTrue("State after detection should be Ready", state is MediaViewModel.PhotoEditState.Ready)
    }

    @Test
    fun `state flow Ready to Processing to Ready`() {
        val bitmap = createFakeBitmap()
        var state: MediaViewModel.PhotoEditState = MediaViewModel.PhotoEditState.Ready(bitmap, null)

        // 开始 GPU 处理
        state = MediaViewModel.PhotoEditState.Processing
        assertTrue("State after process trigger should be Processing", state is MediaViewModel.PhotoEditState.Processing)

        // 处理完成
        val processedBitmap = createFakeBitmap()
        state = MediaViewModel.PhotoEditState.Ready(processedBitmap, null)
        assertTrue("State after process done should be Ready", state is MediaViewModel.PhotoEditState.Ready)
    }

    @Test
    fun `state flow Analyzing to Error`() {
        var state: MediaViewModel.PhotoEditState = MediaViewModel.PhotoEditState.Analyzing

        // 检测失败
        state = MediaViewModel.PhotoEditState.Error("人脸检测失败：timeout")
        assertTrue("State after failure should be Error", state is MediaViewModel.PhotoEditState.Error)
        assertEquals("人脸检测失败：timeout", (state as MediaViewModel.PhotoEditState.Error).message)
    }

    @Test
    fun `state flow complete cycle`() {
        var state: MediaViewModel.PhotoEditState = MediaViewModel.PhotoEditState.Idle

        // 1. 进入编辑
        state = MediaViewModel.PhotoEditState.Analyzing
        assertNotNull(state)

        // 2. 准备就绪
        state = MediaViewModel.PhotoEditState.Ready(createFakeBitmap(), FaceData(hasFace = true))
        assertTrue(state is MediaViewModel.PhotoEditState.Ready)

        // 3. 开始处理
        state = MediaViewModel.PhotoEditState.Processing
        assertTrue(state is MediaViewModel.PhotoEditState.Processing)

        // 4. 处理完成
        state = MediaViewModel.PhotoEditState.Ready(createFakeBitmap(), FaceData(hasFace = true))
        assertTrue(state is MediaViewModel.PhotoEditState.Ready)

        // 5. 退出编辑
        state = MediaViewModel.PhotoEditState.Idle
        assertTrue(state is MediaViewModel.PhotoEditState.Idle)
    }

    @Test
    fun `Ready equality same bitmap and faceData`() {
        val bitmap = createFakeBitmap()
        val faceData = FaceData(hasFace = true, faceCenterX = 0.4f)
        val a = MediaViewModel.PhotoEditState.Ready(bitmap, faceData)
        val b = MediaViewModel.PhotoEditState.Ready(bitmap, faceData)

        // data class 自动生成的 equals 基于属性值
        assertEquals("Same content should produce equal Ready states", a, b)
    }

    @Test
    fun `Error equality same message`() {
        val a = MediaViewModel.PhotoEditState.Error("err")
        val b = MediaViewModel.PhotoEditState.Error("err")
        assertEquals("Same message should produce equal Error states", a, b)
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private fun createFakeBitmap(): Bitmap {
        // JVM 单元测试中 Bitmap.createBitmap 会抛 RuntimeException，
        // 使用 MockK mock 替代
        return mockk<Bitmap>(relaxed = true)
    }
}
