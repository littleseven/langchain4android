package com.picme.di

import android.content.Context
import androidx.camera.view.PreviewView
import com.picme.core.image.GpuImageBeautyPreviewProvider
import com.picme.domain.preview.BeautyPreviewProvider

/**
 * [RD] 美颜预览提供者工厂
 * 
 * 职责：
 * 1. 集中管理 BeautyPreviewProvider 的创建
 * 2. 支持配置不同的实现策略
 * 3. 便于单元测试时替换为 Mock
 */
object BeautyPreviewProviderFactory {
    
    /**
     * 创建美颜预览提供者
     * 
     * @param context 应用上下文
     * @param previewView 预览视图容器
     * @param useGpuImage 是否使用 GPUImage 实现（默认 true）
     * 
     * @return BeautyPreviewProvider 实例
     */
    fun create(
        context: Context,
        previewView: PreviewView,
        useGpuImage: Boolean = true
    ): BeautyPreviewProvider {
        return if (useGpuImage) {
            GpuImageBeautyPreviewProvider(context, previewView)
        } else {
            // 未来可以添加其他实现
            // 例如：OpenGlBeautyPreviewProvider、MediaCodecBeautyPreviewProvider
            throw UnsupportedOperationException(
                "Only GPU Image implementation is available currently"
            )
        }
    }
}
