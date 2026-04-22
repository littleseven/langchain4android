package com.picme.features.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Environment
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenshotUtilTest {

    private lateinit var context: Context
    private lateinit var mockView: View

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockView = mockk(relaxed = true)
    }

    @Test
    fun `captureAndSave with valid view should return file path`() {
        // Given
        every { mockView.width } returns 1080
        every { mockView.height } returns 1920
        every { mockView.draw(any()) } answers {
            val canvas = firstArg<Canvas>()
            canvas.drawColor(Color.RED)
        }

        // When
        val result = ScreenshotUtil.captureAndSave(mockView, context)

        // Then
        assertNotNull(result)
        assertTrue(result!!.endsWith(".png"))
        assertTrue(result.contains("screenshot_"))
        
        // Verify file was created
        val file = File(result)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        
        // Cleanup
        file.delete()
    }

    @Test
    fun `captureAndSave with zero dimension view should handle gracefully`() {
        // Given
        every { mockView.width } returns 0
        every { mockView.height } returns 0

        // When
        val result = ScreenshotUtil.captureAndSave(mockView, context)

        // Then - should handle gracefully without crash
        // May return null or create empty file depending on implementation
    }

    @Test
    fun `captureAndSave should create directory if not exists`() {
        // Given
        every { mockView.width } returns 100
        every { mockView.height } returns 100
        every { mockView.draw(any()) } answers {
            val canvas = firstArg<Canvas>()
            canvas.drawColor(Color.BLUE)
        }

        // When
        val result = ScreenshotUtil.captureAndSave(mockView, context)

        // Then
        assertNotNull(result)
        val file = File(result!!)
        assertTrue(file.parentFile?.exists() == true)
        
        // Cleanup
        file.delete()
    }

    @Test
    fun `captureAndSave should save to correct directory`() {
        // Given
        every { mockView.width } returns 100
        every { mockView.height } returns 100
        every { mockView.draw(any()) } answers {
            val canvas = firstArg<Canvas>()
            canvas.drawColor(Color.GREEN)
        }

        // When
        val result = ScreenshotUtil.captureAndSave(mockView, context)

        // Then
        assertNotNull(result)
        assertTrue(result!!.contains("PicMe_Debug_Screenshots"))
        
        // Cleanup
        File(result).delete()
    }

    @Test
    fun `captureAndSave with exception should return null`() {
        // Given - simulate exception during draw
        every { mockView.width } returns 100
        every { mockView.height } returns 100
        every { mockView.draw(any()) } throws RuntimeException("Draw failed")

        // When
        val result = ScreenshotUtil.captureAndSave(mockView, context)

        // Then
        assertNull(result)
    }

    @Test
    fun `captureAndSave should generate unique filenames`() {
        // Given
        every { mockView.width } returns 100
        every { mockView.height } returns 100
        every { mockView.draw(any()) } answers {
            val canvas = firstArg<Canvas>()
            canvas.drawColor(Color.YELLOW)
        }

        // When
        val result1 = ScreenshotUtil.captureAndSave(mockView, context)
        Thread.sleep(1100) // Ensure different timestamp
        val result2 = ScreenshotUtil.captureAndSave(mockView, context)

        // Then
        assertNotNull(result1)
        assertNotNull(result2)
        assertTrue(result1 != result2)
        
        // Cleanup
        File(result1!!).delete()
        File(result2!!).delete()
    }
}
