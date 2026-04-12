package com.picme.beauty.api

import android.view.Surface

/**
 * 美颜渲染管线统一接口
 * 用于屏蔽底层引擎（自研 OpenGL 或 GPUPixel）的差异
 */
interface IBeautyPipeline {
    
    /**
     * 初始化渲染管线
     * @param width 预览宽度
     * @param height 预览高度
     */
    fun init(width: Int, height: Int)

    /**
     * 设置输出表面
     */
    fun setOutputSurface(surface: Surface)

    /**
     * 处理视频帧
     * @param textureId 输入纹理 ID
     * @param transformMatrix 变换矩阵
     * @param timestampNs 时间戳
     */
    fun onFrameAvailable(textureId: Int, transformMatrix: FloatArray, timestampNs: Long)

    /**
     * 设置美颜参数
     * @param key 参数键 (如 "smoothing", "whitening")
     * @param value 参数值 (0.0 - 1.0)
     */
    fun setParameter(key: String, value: Float)

    /**
     * 释放资源
     */
    fun release()
}
