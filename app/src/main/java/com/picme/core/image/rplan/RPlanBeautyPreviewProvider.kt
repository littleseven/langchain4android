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
    private var cameraInputWidth: Int = 1280
    private var cameraInputHeight: Int = 720
    private var isFillCenter: Boolean = true

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
        beautyPreviewView?.setScaleMode(isFillCenter)
        beautyPreviewView?.setCameraInputBufferSize(cameraInputWidth, cameraInputHeight)
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

        repeat(120) { attemptIndex ->
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
        mouthCenterX: Float,
        mouthCenterY: Float,
        mouthLeftX: Float,
        mouthLeftY: Float,
        mouthRightX: Float,
        mouthRightY: Float,
        upperLipCenterX: Float,
        upperLipCenterY: Float,
        lowerLipCenterX: Float,
        lowerLipCenterY: Float,
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
            mouthCenterX = mouthCenterX,
            mouthCenterY = mouthCenterY,
            mouthLeftX = mouthLeftX,
            mouthLeftY = mouthLeftY,
            mouthRightX = mouthRightX,
            mouthRightY = mouthRightY,
            upperLipCenterX = upperLipCenterX,
            upperLipCenterY = upperLipCenterY,
            lowerLipCenterX = lowerLipCenterX,
            lowerLipCenterY = lowerLipCenterY,
            faceRadius = faceRadius,
            hasFace = hasFace
        )
    }

    fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ) {
        beautyPreviewView?.updateLipMaskPoints(outerPoints, innerPoints)
    }

    override fun release() {
        previewSurface?.release()
        previewSurface = null
        beautyPreviewView = null
        isInitialized = false

        Logger.i("RPlan", "RPlanBeautyPreviewProvider released")
    }

    override fun isReady(): Boolean {
        // Provider 已初始化且输入 Surface 有效即可认为就绪，
        // 避免个别机型 SurfaceTexture 异步创建导致误判回退。
        return isInitialized && (previewSurface?.isValid == true)
    }

    fun getView(): BeautyPreviewView? = beautyPreviewView

    fun setCameraInputBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        cameraInputWidth = width
        cameraInputHeight = height
        beautyPreviewView?.setCameraInputBufferSize(width, height)
    }

    fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
        beautyPreviewView?.setScaleMode(isFillCenter)
    }

    fun getPerfStats(): com.picme.core.image.CameraPreviewRenderer.PerfStats? {
        return beautyPreviewView?.getPerfStats()
    }

    private fun applyBeautySettings(settings: BeautySettings) {
        val view = beautyPreviewView ?: return

        if (!settings.enabled || !settings.hasAnyEffect()) {
            view.smoothingStrength = 0f
            view.whiteningStrength = 0f
            view.bigEyesStrength = 0f
            view.slimFaceStrength = 0f
            view.lipColorStrength = 0f
            view.lipColorIndex = 0
            view.blushStrength = 0f
            view.blushColorFamily = 0
            return
        }

        view.smoothingStrength = (settings.smoothing / 100f).coerceIn(0f, 1f)
        view.whiteningStrength = (settings.whitening / 100f).coerceIn(0f, 1f)
        view.bigEyesStrength = (settings.bigEyes / 100f).coerceIn(0f, 1f)
        view.slimFaceStrength = (settings.slimFace / 50f * 1.35f).coerceIn(-1f, 1f)
        view.lipColorStrength = (settings.lipColor / 100f).coerceIn(0f, 1f)
        view.lipColorIndex = settings.lipColorIndex.coerceIn(0, 11)
        view.blushStrength = (settings.blush / 100f).coerceIn(0f, 1f)
        view.blushColorFamily = settings.blushColorFamily.coerceIn(0, 2)
    }
}

