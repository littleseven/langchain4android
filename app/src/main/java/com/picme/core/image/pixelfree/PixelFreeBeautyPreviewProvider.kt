package com.picme.core.image.pixelfree

import android.content.Context
import android.view.Surface
import com.hapi.pixelfree.PFBeautyFilterType
import com.picme.beauty.api.BeautyParams
import com.picme.core.common.Logger
import com.picme.domain.preview.BeautyPreviewProvider

/**
 * [RD] PixelFreeEffects SDK 实现的美颜预览提供者
 *
 * 实施策略：短期方案（1-2 周）
 * - 快速上线产品功能
 * - 验证产品需求
 * - 收集用户反馈
 *
 * 技术特点：
 * - 基于 GLSurfaceView + PixelFree SDK
 * - 支持实时预览美颜效果
 * - 性能优化：纹理模式处理
 *
 * @param context Android Context
 * @see com.picme.domain.preview.BeautyPreviewProvider
 * @see PixelFreeGLSurfaceView
 */
class PixelFreeBeautyPreviewProvider(
    private val context: Context
) : BeautyPreviewProvider {

    private var pixelFreeView: PixelFreeGLSurfaceView? = null
    private var isInitialized = false

    /**
     * 初始化 PixelFree GLSurfaceView
     * 必须在 UI 线程调用
     */
    fun initialize() {
        if (isInitialized) return

        pixelFreeView = PixelFreeGLSurfaceView(context).apply {
            // GLSurfaceView 会自动创建 Surface
            // 在 onSurfaceCreated 中初始化 SDK
        }

        isInitialized = true
        Logger.d("PixelFree", "PixelFreeBeautyPreviewProvider initialized")
    }

    override fun createPreviewSurface(): Surface {
        val view = pixelFreeView ?: throw IllegalStateException(
            "PixelFreeBeautyPreviewProvider not initialized. Call initialize() first."
        )

        // PixelFreeGLSurfaceView 内部管理 Surface
        // 返回其持有的 Surface 供 CameraX 使用
        return view.getSurfaceForCamera() ?: throw IllegalStateException(
            "PixelFreeGLSurfaceView Surface not ready"
        )
    }

    override fun updateFilters(params: BeautyParams) {
        val view = pixelFreeView ?: return

        if (!params.enabled) {
            return
        }

        // 磨皮（RplanBeautyParams 已归一化 0.0-1.0）
        if (params.smoothing > 0) {
            view.setBeautyParam(
                PFBeautyFilterType.PFBeautyFilterTypeFaceBlurStrength,
                params.smoothing
            )
        }

        // 美白
        if (params.whitening > 0) {
            view.setBeautyParam(
                PFBeautyFilterType.PFBeautyFilterTypeFaceM_newWhitenStrength,
                params.whitening
            )
        }

        // 大眼（适度增益提升可见度）
        if (params.bigEyes > 0) {
            view.setBeautyParam(
                PFBeautyFilterType.PFBeautyFilterTypeFace_EyeStrength,
                (params.bigEyes * 1.35f).coerceIn(0f, 1f)
            )
        }

        // 瘦脸（RplanBeautyParams.slimFace 已是 -1.0~1.0，映射到 0.0-1.0）
        val slimFaceNormalized = (params.slimFace + 1f) / 2f
        view.setBeautyParam(
            PFBeautyFilterType.PFBeautyFilterTypeFace_thinning,
            slimFaceNormalized
        )

        Logger.d(
            "PixelFree",
            "Updated filters: smoothing=${params.smoothing}, whitening=${params.whitening}, " +
                "bigEyes=${params.bigEyes}, slimFace=${params.slimFace}"
        )
    }

    override fun release() {
        pixelFreeView?.release()
        pixelFreeView = null
        isInitialized = false
        Logger.d("PixelFree", "PixelFreeBeautyPreviewProvider released")
    }

    override fun isReady(): Boolean {
        return isInitialized && pixelFreeView != null
    }

    /**
     * 获取 GLSurfaceView 实例（供 UI 层渲染）
     */
    fun getView(): PixelFreeGLSurfaceView? = pixelFreeView
}

