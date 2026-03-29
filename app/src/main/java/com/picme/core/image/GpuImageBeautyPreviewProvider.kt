package com.picme.core.image

import android.content.Context
import android.view.Surface
import androidx.camera.view.PreviewView
import com.picme.domain.model.BeautySettings
import com.picme.domain.preview.BeautyPreviewProvider

/**
 * [RD] GPUImage 美颜预览提供者
 * 
 * 实现细节：
 * 1. 使用 BeautyTextureView + GPUImage 实现实时美颜
 * 2. 封装 SurfaceTexture 管理细节
 * 3. 符合开闭原则：未来可替换为其他实现（如 OpenGL ES、MediaCodec）
 * 
 * @param context 应用上下文
 * @param previewView 预览视图容器
 */
class GpuImageBeautyPreviewProvider(
    private val context: Context,
    private val previewView: PreviewView
) : BeautyPreviewProvider {
    
    private var beautyTextureView: BeautyTextureView? = null
    private var surface: Surface? = null
    private var isInitialized = false
    
    override fun createPreviewSurface(): Surface {
        if (!isInitialized) {
            initializeBeautyView()
        }
        
        return surface ?: throw IllegalStateException(
            "Surface not ready. Call after BeautyTextureView is attached."
        )
    }
    
    override fun updateFilters(settings: BeautySettings) {
        beautyTextureView?.apply {
            smoothingStrength = settings.smoothing
            whiteningStrength = settings.whitening
            slimFaceStrength = settings.slimFace
            bigEyesStrength = settings.bigEyes
            updateFilters()
        }
    }
    
    override fun release() {
        beautyTextureView?.getCameraSurfaceTexture()?.release()
        surface?.release()
        beautyTextureView = null
        surface = null
        isInitialized = false
    }
    
    override fun isReady(): Boolean {
        return isInitialized && surface?.isValid == true
    }
    
    /**
     * 获取 BeautyTextureView 实例
     * 用于在 Compose 中添加到视图树
     */
    fun getBeautyTextureView(): BeautyTextureView? {
        return beautyTextureView
    }
    
    private fun initializeBeautyView() {
        if (isInitialized) return
        
        // 创建 BeautyTextureView
        beautyTextureView = BeautyTextureView(context).apply {
            id = previewView.id // 保持 ID 一致，便于替换
        }
        
        // 等待 SurfaceTexture 准备好
        var retryCount = 0
        while (beautyTextureView?.getCameraSurfaceTexture() == null && retryCount < 10) {
            Thread.sleep(100)
            retryCount++
        }
        
        // 创建 Surface
        beautyTextureView?.getCameraSurfaceTexture()?.let { texture ->
            surface = Surface(texture)
            isInitialized = true
        }
        
        if (!isInitialized) {
            throw IllegalStateException("Failed to initialize BeautyTextureView")
        }
    }
}
