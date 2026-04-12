package com.picme.beauty.engine.bigbeauty

import android.content.Context
import android.view.Surface
import com.picme.beauty.api.IBeautyPipeline
import com.picme.beauty.egl.BeautyRenderer

/**
 * 大美丽自研引擎适配器
 * 将自研 BeautyRenderer 封装为统一的 IBeautyPipeline
 */
class BPipelineAdapter(private val context: Context) : IBeautyPipeline {

    private var beautyRenderer: BeautyRenderer? = null
    private var isInitialized = false

    override fun init(width: Int, height: Int) {
        if (isInitialized) return
        
        beautyRenderer = BeautyRenderer(context).apply {
            onInit()
        }
        
        isInitialized = true
    }

    override fun setOutputSurface(surface: Surface) {
        // 大美丽引擎的 Surface 设置由外部 EGL 管理
        // 这里不需要额外操作
    }

    override fun onFrameAvailable(textureId: Int, transformMatrix: FloatArray, timestampNs: Long) {
        if (!isInitialized) return
        
        beautyRenderer?.let { renderer ->
            renderer.setTextureTransform(transformMatrix)
            renderer.onRender()
        }
    }

    override fun setParameter(key: String, value: Float) {
        // 大美丽引擎使用批量参数更新
        when (key) {
            "smoothing" -> beautyRenderer?.updateBeautyParams(smoothing = value, whitening = 0f)
            "whitening" -> beautyRenderer?.updateBeautyParams(smoothing = 0f, whitening = value)
            "slim_face" -> beautyRenderer?.updateBeautyParams(smoothing = 0f, whitening = 0f, slimFace = value)
            "big_eyes" -> beautyRenderer?.updateBeautyParams(smoothing = 0f, whitening = 0f, bigEyes = value)
            // 更多参数映射...
        }
    }

    override fun release() {
        beautyRenderer?.release()
        beautyRenderer = null
        isInitialized = false
    }
}
