package com.picme.core.image

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.util.AttributeSet
import android.view.TextureView
import androidx.camera.view.PreviewView
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter

/**
 * [RD] 支持 GPUImage 实时美颜的 TextureView
 */
class BeautyTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    private var gpuImage: GPUImage? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurfaceTexture: SurfaceTexture? = null
    
    // 美颜参数
    var smoothingStrength: Float = 0f
    var whiteningStrength: Float = 0f
    var slimFaceStrength: Float = 0f
    var bigEyesStrength: Float = 0f

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (gpuImage == null) {
            gpuImage = GPUImage(context).apply {
                setSurfaceTexture(surface)
            }
            surfaceTexture = surface
            
            // 创建一个新的 SurfaceTexture 给 CameraX 使用
            cameraSurfaceTexture = SurfaceTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            
            // 更新滤镜
            updateFilters()
            
            android.util.Log.d("PicMe:BeautyTexture", "Surface ready, cameraSurfaceTexture created")
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // GPUImage 会自动处理尺寸变化
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // GPUImage 不需要手动 release
        gpuImage = null
        cameraSurfaceTexture?.release()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // 不直接处理，让 GPUImage 自动渲染
    }

    fun getCameraSurfaceTexture(): SurfaceTexture? {
        return cameraSurfaceTexture
    }

    private fun processFrame() {
        // GPUImage 会自动处理渲染，不需要手动调用
        // 这个方法保留用于未来扩展
    }

    fun updateFilters() {
        val filters = mutableListOf<GPUImageFilter>()
        
        if (smoothingStrength > 0f) {
            filters.add(GPUImageSmoothSkinFilter().apply {
                setIntensity(smoothingStrength / 100f)
            })
        }
        
        if (whiteningStrength > 0f) {
            filters.add(GPUImageColorMatrixFilter().apply {
                setBrightness(whiteningStrength / 100f * 0.3f)
            })
        }
        
        gpuImage?.setFilter(if (filters.isEmpty()) null else {
            val filterGroup = GPUImageFilterGroup()
            filters.forEach { filter -> filterGroup.addFilter(filter) }
            filterGroup
        })
    }
}
