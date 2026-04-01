package com.picme.core.image.pixelfree

import android.content.Context
import android.view.Surface
import com.hapi.pixelfree.PFBeautyFilterType
import com.picme.core.common.Logger
import com.picme.domain.model.BeautySettings
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

    override fun updateFilters(settings: BeautySettings) {
        val view = pixelFreeView ?: return

        // 映射 BeautySettings 到 PixelFree SDK 参数
        // 注意：PixelFree 参数范围是 0.0-1.0，需要归一化

        // 磨皮 (0-100 → 0.0-1.0)
        if (settings.smoothing > 0) {
            view.setBeautyParam(
                PFBeautyFilterType.PFBeautyFilterTypeFaceBlurStrength,
                settings.smoothing / 100f
            )
        }

        // 美白 (0-100 → 0.0-1.0)
        if (settings.whitening > 0) {
            view.setBeautyParam(
                PFBeautyFilterType.PFBeautyFilterTypeFaceM_newWhitenStrength,
                settings.whitening / 100f
            )
        }

        // 大眼 (0-100 → 0.0-1.0)，适度增益提升可见度
        if (settings.bigEyes > 0) {
            view.setBeautyParam(
                PFBeautyFilterType.PFBeautyFilterTypeFace_EyeStrength,
                (settings.bigEyes / 100f * 1.35f).coerceIn(0f, 1f)
            )
        }

        // 瘦脸 (-50~+50 → 0.0-1.0)
        // 映射规则：-50 → 0.0, 0 → 0.5, +50 → 1.0
        val slimFaceNormalized = (settings.slimFace + 50f) / 100f
        view.setBeautyParam(
            PFBeautyFilterType.PFBeautyFilterTypeFace_thinning,
            slimFaceNormalized
        )

        Logger.d("PixelFree",
            "Updated filters: smoothing=${settings.smoothing}, whitening=${settings.whitening}, " +
            "bigEyes=${settings.bigEyes}, slimFace=${settings.slimFace}"
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

