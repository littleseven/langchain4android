package com.picme.core.image

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.camera.view.PreviewView

/**
 * R 计划 - 美颜预览视图（方案 A1.5）
 * 
 * 功能：
 * 1. 使用 PreviewView 作为显示载体（CameraX 官方推荐）
 * 2. 从 PreviewView 获取 TextureView 的 surfaceTexture
 * 3. 使用离屏渲染器处理美颜
 * 4. 渲染回 TextureView
 * 
 * @author RD Team
 * @version 1.0 (R 计划 A1.5)
 */
class BeautyPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    
    companion object {
        private const val TAG = "PicMe:BeautyPreviewView"
    }
    
    /** PreviewView（CameraX 官方） */
    private val previewView: PreviewView
    
    /** 渲染器 */
    private val renderer: CameraPreviewRenderer = CameraPreviewRenderer()
    
    /** SurfaceTexture（来自 PreviewView 内部的 TextureView） */
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    
    /** 是否已初始化 */
    private var isInitialized = false
    
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
    
    /** 渲染模式 */
    var renderMode: Int = BeautyRenderer.MODE_BEAUTY
        set(value) {
            field = value
            renderer.setRenderMode(value)
        }
    
    /**
     * 从 PreviewView 中查找 TextureView
     */
    private fun findTextureViewInPreviewView(previewView: PreviewView): TextureView? {
        // 尝试直接获取第一个子 View（应该是 TextureView）
        if (previewView.childCount > 0) {
            val child = previewView.getChildAt(0)
            if (child is TextureView) {
                return child
            }
        }
        
        // 如果找不到，返回 null
        return null
    }
    
    /**
     * 备选方案：直接创建 TextureView
     */
    private fun fallbackToTextureView() {
        if (isInitialized && surfaceTexture != null) {
            return
        }

        Log.d(TAG, "Falling back to direct TextureView creation")
        
        val textureView = TextureView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            // 启用硬件加速
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
        
        // 替换 PreviewView
        removeAllViews()
        addView(textureView)
        
        // 初始化渲染器
        try {
            renderer.init(textureView)
            isInitialized = true
            surfaceTexture = renderer.getSurfaceTexture()
            updateBeautyParams()
            
            Log.d(TAG, "Fallback TextureView initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback failed: ${e.message}", e)
        }
    }
    
    /**
     * 预热离屏链路：用于未 attach 到窗口时仍可提供 CameraX Surface。
     */
    fun ensureOffscreenReady() {
        if (surfaceTexture != null) {
            return
        }

        fallbackToTextureView()
    }

    init {
        // 创建 PreviewView（CameraX 官方）
        previewView = PreviewView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            
            // 关键：启用硬件加速
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
        
        addView(previewView)
        
        Log.d(TAG, "BeautyPreviewView created with PreviewView")
        
        // 监听 PreviewView 的布局完成，获取内部的 TextureView
        post {
            Log.d(TAG, "Getting TextureView from PreviewView")
            // PreviewView 内部包含一个 TextureView，但需要等待布局完成
            // 使用延迟获取
            postDelayed({
                // 尝试从 PreviewView 获取 TextureView
                val textureView = findTextureViewInPreviewView(previewView)
                if (textureView != null) {
                    Log.d(TAG, "TextureView found: ${textureView.hashCode()}, hardware accelerated=${textureView.isHardwareAccelerated}")
                    
                    // 初始化渲染器
                    try {
                        renderer.init(textureView)
                        isInitialized = true
                        
                        // 获取用于相机的 SurfaceTexture
                        surfaceTexture = renderer.getSurfaceTexture()
                        
                        // 设置默认美颜参数
                        updateBeautyParams()
                        
                        Log.d(TAG, "Renderer initialized successfully")
                        Log.d(TAG, "Camera SurfaceTexture: ${surfaceTexture}")
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize renderer: ${e.message}", e)
                    }
                } else {
                    Log.e(TAG, "TextureView not found in PreviewView!")
                    // 尝试直接创建 TextureView 作为备选方案
                    fallbackToTextureView()
                }
            }, 100) // 延迟 100ms 等待 PreviewView 布局完成
        }
    }
    
    /**
     * 获取 SurfaceTexture（供 CameraX 绑定）
     */
    fun getSurfaceTexture(): android.graphics.SurfaceTexture? {
        return renderer.getSurfaceTexture()
    }
    
    private var surfaceCreated = false
    private var cameraSurface: android.view.Surface? = null
    
    fun getSurfaceForCamera(): android.view.Surface? {
        if (surfaceTexture == null) {
            ensureOffscreenReady()
        }

        if (surfaceTexture == null) {
            Log.w(TAG, "Camera SurfaceTexture not ready, cannot create Surface")
            return null
        }
        
        // 关键：只创建一次 Surface，每次都返回同一个对象
        if (cameraSurface == null) {
            Log.d(TAG, "Creating Surface for CameraX for the first time")
            
            // 关键：确保缓冲区大小正确
            surfaceTexture!!.setDefaultBufferSize(1920, 1080)
            
            // 创建 Surface 并启动渲染
            cameraSurface = android.view.Surface(surfaceTexture!!)
            surfaceCreated = true
            
            // 启动渲染
            renderer.setRenderSurface(cameraSurface!!)
            Log.d(TAG, "Surface created and render started")
        }
        
        Log.d(TAG, "Returning cached Surface: ${cameraSurface?.hashCode()}")
        return cameraSurface
    }
    
    /**
     * 更新美颜参数
     */
    private fun updateBeautyParams() {
        if (isInitialized) {
            renderer.updateBeautyParams(smoothingStrength, whiteningStrength)
        }
    }
    
    // ========== View Lifecycle ==========
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "Attached to window")
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "Detached from window")
        
        // 确保资源被释放
        renderer.release()
        isInitialized = false
    }
    
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        
        if (visibility == VISIBLE) {
            Log.d(TAG, "View visible")
        } else {
            Log.d(TAG, "View invisible")
        }
    }
}
