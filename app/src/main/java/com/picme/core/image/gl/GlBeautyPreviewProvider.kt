package com.picme.core.image.gl

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import com.picme.beauty.api.BeautyParams
import com.picme.beauty.egl.BeautyPreviewView
import com.picme.core.common.Logger
import com.picme.domain.preview.BeautyPreviewCapability
import com.picme.domain.preview.BeautyPreviewProvider

/**
 * GL 渲染美颜预览提供者（app 层适配）
 *
 * 当前实现目标：
 * 1. 打通 GL 美颜参数链路
 * 2. 提供可用 Surface 给 CameraX 绑定
 * 3. 失败时抛出明确错误，便于上层回退
 *
 * 实现 [BeautyPreviewCapability] 以暴露 GL 专属能力（FaceWarp、LipMask、缓冲区配置等），
 * 同时保持 [BeautyPreviewProvider] 接口对调用方透明。
 */
class GlBeautyPreviewProvider(
    context: Context
) : BeautyPreviewProvider, BeautyPreviewCapability {

    private val appContext: Context = context.applicationContext
    private var beautyPreviewView: BeautyPreviewView? = null
    private var previewSurface: Surface? = null
    private var isInitialized = false
    private var lastParams: BeautyParams = BeautyParams.EMPTY
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
        applyParams(lastParams)
        isInitialized = true

        Logger.i("GlBeauty", "GlBeautyPreviewProvider initialized")
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
            "GlBeautyPreviewProvider not initialized"
        )

        repeat(120) { attemptIndex ->
            val surface = view.getSurfaceForCamera()
            if (surface != null && surface.isValid) {
                previewSurface?.takeIf { it !== surface }?.release()
                previewSurface = surface
                Logger.i("GlBeauty", "GL beauty preview surface ready on attempt=${attemptIndex + 1}")
                return surface
            }
            SystemClock.sleep(30)
        }

        throw IllegalStateException("GL beauty preview surface not ready")
    }

    override fun updateFilters(params: BeautyParams) {
        lastParams = params
        applyParams(params)
        Logger.d(
            "GlBeauty",
            "updateFilters: enabled=${params.enabled}, smoothing=${params.smoothing}, " +
                "whitening=${params.whitening}, bigEyes=${params.bigEyes}, slimFace=${params.slimFace}"
        )
    }

    override fun updateFaceWarpParams(
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

    override fun updateLipMaskPoints(
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

        Logger.i("GlBeauty", "GlBeautyPreviewProvider released")
    }

    override fun isReady(): Boolean {
        return isInitialized && (previewSurface?.isValid == true)
    }

    fun getView(): BeautyPreviewView? = beautyPreviewView

    override fun setCameraInputBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        cameraInputWidth = width
        cameraInputHeight = height
        beautyPreviewView?.setCameraInputBufferSize(width, height)
    }

    override fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
        beautyPreviewView?.setScaleMode(isFillCenter)
    }

    fun getPerfStats(): com.picme.beauty.egl.CameraPreviewRenderer.PerfStats? {
        return beautyPreviewView?.getPerfStats()
    }

    private fun applyParams(params: BeautyParams) {
        val view = beautyPreviewView ?: return

        if (!params.enabled) {
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

        view.smoothingStrength = params.smoothing.coerceIn(0f, 1f)
        view.whiteningStrength = params.whitening.coerceIn(0f, 1f)
        view.bigEyesStrength = params.bigEyes.coerceIn(0f, 1f)
        view.slimFaceStrength = params.slimFace.coerceIn(-1f, 1f)
        view.lipColorStrength = params.lipColor.coerceIn(0f, 1f)
        view.lipColorIndex = params.lipColorIndex.coerceIn(0, 11)
        view.blushStrength = params.blush.coerceIn(0f, 1f)
        view.blushColorFamily = params.blushColorFamily.coerceIn(0, 2)
    }
}

