package com.picme.core.image.rplan

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import com.picme.core.common.Logger
import com.picme.core.image.BeautyPreviewView
import com.picme.domain.model.BeautySettings
import com.picme.domain.preview.BeautyPreviewProvider

/**
 * RD R 计划美颜预览提供者
 *
 * 当前实现目标：
 * 1. 打通 R 计划参数链路
 * 2. 提供可用 Surface 给 CameraX 绑定
 * 3. 失败时抛出明确错误，便于上层回退
 */
class RPlanBeautyPreviewProvider(
    context: Context
) : BeautyPreviewProvider {

    private val appContext: Context = context.applicationContext
    private var beautyPreviewView: BeautyPreviewView? = null
    private var previewSurface: Surface? = null
    private var isInitialized = false
    private var lastSettings: BeautySettings = BeautySettings()

    fun initialize() {
        if (isInitialized) {
            return
        }

        beautyPreviewView = BeautyPreviewView(appContext).apply {
            ensureOffscreenReady()
        }
        applyBeautySettings(lastSettings)
        isInitialized = true

        Logger.i("RPlan", "RPlanBeautyPreviewProvider initialized")
    }

    override fun createPreviewSurface(): Surface {
        if (!isInitialized) {
            initialize()
        }

        previewSurface?.let { cachedSurface ->
            if (cachedSurface.isValid) {
                return cachedSurface
            }
        }

        val view = beautyPreviewView ?: throw IllegalStateException(
            "RPlanBeautyPreviewProvider not initialized"
        )

        repeat(24) { attemptIndex ->
            val surface = view.getSurfaceForCamera()
            if (surface != null && surface.isValid) {
                previewSurface?.takeIf { it !== surface }?.release()
                previewSurface = surface
                Logger.i("RPlan", "R Plan preview surface ready on attempt=${attemptIndex + 1}")
                return surface
            }
            SystemClock.sleep(30)
        }

        throw IllegalStateException("R Plan preview surface not ready")
    }

    override fun updateFilters(settings: BeautySettings) {
        lastSettings = settings
        applyBeautySettings(settings)

        Logger.d(
            "RPlan",
            "updateFilters: enabled=${settings.enabled}, smoothing=${settings.smoothing}, whitening=${settings.whitening}, bigEyes=${settings.bigEyes}, slimFace=${settings.slimFace}"
        )
    }

    fun updateFaceWarpParams(
        faceCenterX: Float,
        faceCenterY: Float,
        leftEyeX: Float,
        leftEyeY: Float,
        rightEyeX: Float,
        rightEyeY: Float,
        faceRadius: Float,
        hasFace: Boolean
    ) {
        beautyPreviewView?.updateFaceWarpParams(
            faceCenterX = faceCenterX,
            faceCenterY = faceCenterY,
            leftEyeX = leftEyeX,
            leftEyeY = leftEyeY,
            rightEyeX = rightEyeX,
            rightEyeY = rightEyeY,
            faceRadius = faceRadius,
            hasFace = hasFace
        )
    }

    override fun release() {
        previewSurface?.release()
        previewSurface = null
        beautyPreviewView = null
        isInitialized = false

        Logger.i("RPlan", "RPlanBeautyPreviewProvider released")
    }

    override fun isReady(): Boolean {
        val hasSurfaceTexture = beautyPreviewView?.getSurfaceTexture() != null
        return isInitialized && hasSurfaceTexture
    }

    fun getView(): BeautyPreviewView? = beautyPreviewView

    private fun applyBeautySettings(settings: BeautySettings) {
        val view = beautyPreviewView ?: return

        if (!settings.enabled || !settings.hasAnyEffect()) {
            view.smoothingStrength = 0f
            view.whiteningStrength = 0f
            view.bigEyesStrength = 0f
            view.slimFaceStrength = 0f
            return
        }

        view.smoothingStrength = (settings.smoothing / 100f).coerceIn(0f, 1f)
        view.whiteningStrength = (settings.whitening / 100f).coerceIn(0f, 1f)
        view.bigEyesStrength = (settings.bigEyes / 100f).coerceIn(0f, 1f)
        view.slimFaceStrength = (settings.slimFace / 50f).coerceIn(-1f, 1f)
    }
}

