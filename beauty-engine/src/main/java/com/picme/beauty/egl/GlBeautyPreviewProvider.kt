package com.picme.beauty.egl

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.picme.beauty.api.BeautyParams
import com.picme.beauty.api.BeautyPerfStats
import com.picme.beauty.api.BeautyPreviewEngine

/**
 * GL 渲染美颜预览提供者
 *
 * 实现 [BeautyPreviewProvider] 和 [BeautyPreviewCapability]，
 * 封装 EGL/GL Shader 渲染管线。
 *
 * 参数接受 [BeautyParams]，解耦对 app 层 BeautySettings 的依赖，
 * app 层负责将 BeautySettings 转换后传入。
 */
class GlBeautyPreviewProvider(
    context: Context
) : BeautyPreviewEngine {

    companion object {
        private const val TAG = "PicMe:GlBeautyProvider"
    }

    private val appContext: Context = context.applicationContext
    private var beautyPreviewView: BeautyPreviewView? = null
    private var previewSurface: Surface? = null
    private var isInitialized = false
    private var lastParams: BeautyParams = BeautyParams.EMPTY
    private var cameraInputWidth: Int = 1280
    private var cameraInputHeight: Int = 720
    private var isFillCenter: Boolean = true

    override fun initialize() {
        if (isInitialized) return
        beautyPreviewView = BeautyPreviewView(appContext).apply {
            ensureOffscreenReady()
        }
        applyParams(lastParams)
        isInitialized = true
        Log.i(TAG, "GlBeautyPreviewProvider initialized")
    }

    override fun createPreviewSurface(): Surface {
        beautyPreviewView?.setScaleMode(isFillCenter)
        beautyPreviewView?.setCameraInputBufferSize(cameraInputWidth, cameraInputHeight)
        if (!isInitialized) initialize()

        previewSurface?.let { cachedSurface ->
            if (cachedSurface.isValid) return cachedSurface
        }

        val view = beautyPreviewView
            ?: throw IllegalStateException("GlBeautyPreviewProvider not initialized")

        repeat(120) { attemptIndex ->
            val surface = view.getSurfaceForCamera()
            if (surface != null && surface.isValid) {
                previewSurface?.takeIf { it !== surface }?.release()
                previewSurface = surface
                Log.i(TAG, "GL beauty preview surface ready on attempt=${attemptIndex + 1}")
                return surface
            }
            SystemClock.sleep(30)
        }

        throw IllegalStateException("GL beauty preview surface not ready")
    }

    override fun updateFilters(params: BeautyParams) {
        lastParams = params
        applyParams(params)
        // 高频调用不打日志，避免日志洪水
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
        Log.i(TAG, "GlBeautyPreviewProvider released")
    }

    override fun isReady(): Boolean {
        return isInitialized && (previewSurface?.isValid == true)
    }

    override fun getView(): BeautyPreviewView = beautyPreviewView
        ?: throw IllegalStateException("GlBeautyPreviewProvider view not ready")

    override fun setCameraInputBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        cameraInputWidth = width
        cameraInputHeight = height
        beautyPreviewView?.setCameraInputBufferSize(width, height)
    }

    override fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
        beautyPreviewView?.setScaleMode(isFillCenter)
    }

    override fun getPerfStats(): BeautyPerfStats = beautyPreviewView?.getPerfStats() ?: BeautyPerfStats.EMPTY

    /**
     * 设置 Shader 调试模式
     * @param mode 0=正常, 1=显示 Skin Mask, 2=显示 Warp 偏移
     */
    fun setDebugMode(mode: Int) {
        beautyPreviewView?.setDebugMode(mode)
    }

    private fun applyParams(params: BeautyParams) {
        val view = beautyPreviewView ?: return

        // 色调滤镜矩阵独立于美颜开关，始终同步
        view.colorMatrix = params.colorMatrix

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

