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
    
    private var gpuImageBeautyView: GPUImageBeautyView? = null
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
        gpuImageBeautyView?.apply {
            smoothingStrength = settings.smoothing.toFloat()
            whiteningStrength = settings.whitening.toFloat()
            slimFaceStrength = settings.slimFace.toFloat()
            bigEyesStrength = settings.bigEyes.toFloat()
            // 参数会直接在下一帧渲染时应用，不需要额外调用
        }
    }
    
    override fun release() {
        gpuImageBeautyView?.getCameraSurfaceTexture()?.release()
        surface?.release()
        gpuImageBeautyView = null
        surface = null
        isInitialized = false
    }
    
    override fun isReady(): Boolean {
        return isInitialized && surface?.isValid == true
    }
    
    /**
     * 获取 GPUImageBeautyView 实例
     * 用于在 Compose 中添加到视图树
     */
    fun getGPUImageBeautyView(): GPUImageBeautyView? {
        return gpuImageBeautyView
    }
    
    private fun initializeBeautyView() {
        if (isInitialized) return
        
        // 创建 GPUImageBeautyView
        gpuImageBeautyView = GPUImageBeautyView(context).apply {
            id = previewView.id // 保持 ID 一致，便于替换
        }
        
        // 等待 SurfaceTexture 准备好
        var retryCount = 0
        while (gpuImageBeautyView?.getCameraSurfaceTexture() == null && retryCount < 10) {
            Thread.sleep(100)
            retryCount++
        }
        
        // 创建 Surface
        gpuImageBeautyView?.getCameraSurfaceTexture()?.let { texture ->
            surface = Surface(texture)
            isInitialized = true
        }
        
        if (!isInitialized) {
            throw IllegalStateException("Failed to initialize GPUImageBeautyView")
        }
    }
}
