package com.picme.core.image

import android.content.Context
import android.util.Log

/**
 * [RD] 美颜预览处理器（简化版）
 * 
 * 直接操作 GPUImageBeautyView 的属性来更新美颜效果
 */
class BeautyPreviewProcessor(
    private val context: Context,
    private val gpuImageBeautyView: GPUImageBeautyView
) {
    companion object {
        private const val TAG = "PicMe:BeautyPreview"
    }
    
    fun updateBeautyFilters() {
        // GPUImageBeautyView 会自动应用新的参数
        Log.d(TAG, "Beauty filters updated: smoothing=${gpuImageBeautyView.smoothingStrength}, whitening=${gpuImageBeautyView.whiteningStrength}")
    }
    
    fun release() {
        // 不需要额外清理
    }
}

