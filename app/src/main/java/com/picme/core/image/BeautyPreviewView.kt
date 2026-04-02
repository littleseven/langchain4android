package com.picme.core.image

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/**
 * R Plan 预览视图：
 * - CameraX 输入 Surface 与显示 Surface 明确分离
 * - 显示层固定为本视图内部 TextureView，避免依赖 PreviewView 内部实现
 */
class BeautyPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    
    companion object {
        private const val TAG = "PicMe:BeautyPreviewView"
    }
    
    private val renderer: CameraPreviewRenderer = CameraPreviewRenderer()
    private val textureView: TextureView = TextureView(context)

    private var cameraSurface: Surface? = null
    private var displaySurface: Surface? = null
    private var isRendererInitialized = false

    /** 磨皮强度 */
    var smoothingStrength: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParams()
        }
    
    /** 美白强度 */
    var whiteningStrength: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParams()
        }
    
    /** 大眼强度 */
    var bigEyesStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParams()
        }

    /** 瘦脸强度（-1.0 ~ 1.0） */
    var slimFaceStrength: Float = 0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            updateBeautyParams()
        }

    /** 渲染模式 */
    var renderMode: Int = BeautyRenderer.MODE_BEAUTY
        set(value) {
            field = value
            renderer.setRenderMode(value)
        }
    
    init {
        textureView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        textureView.setLayerType(LAYER_TYPE_HARDWARE, null)
        addView(textureView)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "Display TextureView ready: ${width}x${height}")
                ensureRendererInitialized()
                bindDisplaySurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "Display TextureView resized: ${width}x${height}")
                bindDisplaySurface(surface)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.d(TAG, "Display TextureView destroyed")
                displaySurface?.release()
                displaySurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        
        Log.d(TAG, "BeautyPreviewView created with dedicated TextureView")
    }
    
    /** 预热链路：用于 CameraX 请求输入 Surface 前初始化 renderer */
    fun ensureOffscreenReady() {
        ensureRendererInitialized()
    }

    private fun ensureRendererInitialized() {
        if (isRendererInitialized) {
            return
        }

        renderer.init(textureView)
        isRendererInitialized = true
        updateBeautyParams()
        Log.d(TAG, "Renderer initialized")
    }

    private fun bindDisplaySurface(surfaceTexture: SurfaceTexture) {
        if (!isRendererInitialized) {
            return
        }
        
        displaySurface?.release()
        displaySurface = Surface(surfaceTexture)
        renderer.setRenderSurface(displaySurface!!)
    }
    
    fun getSurfaceTexture(): SurfaceTexture? {
        return renderer.getSurfaceTexture()
    }
    
    fun getSurfaceForCamera(): Surface? {
        ensureRendererInitialized()

        if (cameraSurface == null) {
            renderer.getSurfaceTexture()?.setDefaultBufferSize(1920, 1080)
            cameraSurface = renderer.getSurfaceForCamera()
            Log.d(TAG, "Created camera input surface: ${cameraSurface?.hashCode()}")
        }
        
        return cameraSurface
    }
    
    private fun updateBeautyParams() {
        if (!isRendererInitialized) {
            return
        }

        renderer.updateBeautyParams(
            smoothing = smoothingStrength,
            whitening = whiteningStrength,
            bigEyes = bigEyesStrength,
            slimFace = slimFaceStrength
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
        if (!isRendererInitialized) {
            return
        }

        renderer.updateFaceWarpParams(
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
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        displaySurface?.release()
        displaySurface = null

        cameraSurface?.release()
        cameraSurface = null

        renderer.release()
        isRendererInitialized = false

        Log.d(TAG, "BeautyPreviewView released")
    }
    
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        Log.d(TAG, "Visibility changed: $visibility")
    }
}
