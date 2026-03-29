package com.picme.domain.preview

import android.view.Surface
import com.picme.domain.model.BeautySettings

/**
 * [RD] 美颜预览提供者接口
 * 
 * 设计原则：
 * 1. 单一职责：只负责预览表面的提供和滤镜更新
 * 2. 依赖倒置：上层模块依赖抽象接口，而非具体实现
 * 3. 开闭原则：对扩展开放（新增实现类），对修改关闭（不改动调用方）
 * 
 * @since 1.0.0
 */
interface BeautyPreviewProvider {
    
    /**
     * 创建预览 Surface
     * CameraX 将帧输出到此 Surface
     */
    fun createPreviewSurface(): Surface
    
    /**
     * 更新美颜滤镜参数
     * @param settings 美颜设置（磨皮、美白、瘦脸、大眼）
     */
    fun updateFilters(settings: BeautySettings)
    
    /**
     * 释放资源
     * 在 ViewModel 销毁时调用
     */
    fun release()
    
    /**
     * 是否已准备好
     * @return true 如果 Surface 可用
     */
    fun isReady(): Boolean
}
