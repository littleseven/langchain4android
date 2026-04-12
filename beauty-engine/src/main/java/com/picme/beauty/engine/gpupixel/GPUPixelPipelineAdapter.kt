package com.picme.beauty.engine.gpupixel

import android.content.Context
import android.view.Surface
import com.picme.beauty.api.IBeautyPipeline
import com.pixpark.gpupixel.GPUPixel
import com.pixpark.gpupixel.GPUPixelFilter
import com.pixpark.gpupixel.GPUPixelSinkSurface

/**
 * GPUPixel 引擎适配器
 * 将 GPUPixel 的 C++ 滤镜链封装为统一的 IBeautyPipeline
 */
class GPUPixelPipelineAdapter(private val context: android.content.Context) : IBeautyPipeline {

    private var beautyFilter: GPUPixelFilter? = null
    private var sinkSurface: GPUPixelSinkSurface? = null
    private var isInitialized = false

    override fun init(width: Int, height: Int) {
        if (isInitialized) return
        
        // 初始化 GPUPixel 全局环境
        com.pixpark.gpupixel.GPUPixel.Init(context)
        
        // 创建美颜滤镜 (使用 BeautyFaceFilter)
        beautyFilter = GPUPixelFilter.Create(GPUPixelFilter.BEAUTY_FACE_FILTER)
        
        isInitialized = true
    }

    override fun setOutputSurface(surface: android.view.Surface) {
        sinkSurface = GPUPixelSinkSurface.Create().apply {
            SetSurface(surface, 1280, 720) // 这里后续需要从外部传入实际尺寸
            SetMirror(true) // 前置摄像头通常需要镜像
        }
        
        // 连接管线: BeautyFilter -> SinkSurface
        beautyFilter?.AddSink(sinkSurface)
    }

    override fun onFrameAvailable(textureId: Int, transformMatrix: FloatArray, timestampNs: Long) {
        if (!isInitialized || beautyFilter == null) return
        
        // GPUPixel 的 Java API 主要是通过 Source 驱动的。
        // 由于我们目前没有实现自定义的 TextureSource，这里尝试通过设置属性或调用内部方法触发。
        // 暂时先记录日志，观察是否能进入此分支
        android.util.Log.d("GPUPixelAdapter", "Frame received: texId=$textureId")
        
        // 注意：如果 GPUPixel 没有接收到 Source 的信号，它是不会自动渲染的。
        // 这是一个已知的集成难点，后续可能需要通过 C++ JNI 扩展来解决。
    }

    override fun setParameter(key: String, value: Float) {
        when (key) {
            "smoothing" -> beautyFilter?.SetProperty("smoothLevel", value)
            "whitening" -> beautyFilter?.SetProperty("whiteLevel", value)
            "slim_face" -> beautyFilter?.SetProperty("faceSlimLevel", value)
            "big_eyes" -> beautyFilter?.SetProperty("eyeEnlargeLevel", value)
            // 更多参数映射...
        }
    }

    override fun release() {
        beautyFilter?.RemoveAllSinks()
        beautyFilter?.Destroy()
        sinkSurface?.Destroy()
        beautyFilter = null
        sinkSurface = null
        isInitialized = false
    }
}
