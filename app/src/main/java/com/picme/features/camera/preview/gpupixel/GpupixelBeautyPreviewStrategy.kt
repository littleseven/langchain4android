package com.picme.features.camera.preview.gpupixel

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import com.picme.beauty.api.BeautyParams
import com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider
import com.picme.core.common.Logger
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.BeautyStrategy
import com.picme.features.camera.AspectRatio
import com.picme.features.camera.preview.core.BeautyPreviewEngineStrategy
import com.picme.features.camera.preview.core.FaceWarpParams

internal class GpupixelBeautyPreviewStrategy(
    private val gpupixelProvider: GpupixelBeautyPreviewProvider,
    private val previewView: PreviewView,
    private val onWarmUpFallback: (String) -> Unit
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.GPUPIXEL

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        return try {
            // setScaleMode 必须在 initialize() 之前调用，确保 isFillCenter 状态在初始化时正确
            gpupixelProvider.setScaleMode(isFillCenter = aspectRatio == AspectRatio.RATIO_FULL)
            gpupixelProvider.initialize()
            // GPUPixel 通过 ImageAnalysis → onRgbaFrame 路径接收帧，不需要 Preview Surface 渲染。
            // 但必须提供一个有效的 SurfaceProvider，否则 CameraX 会阻塞整个 session（包括 ImageAnalysis）。
            // 使用 previewView.surfaceProvider 作为占位——帧会送到 PreviewView，但 PreviewView
            // 在 GPUPixel 模式下不可见（container 里放的是 TextureView），不影响显示效果。
            previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
            Logger.i(
                "Camera",
                "GPUPixel preview strategy active, aspectRatio=$aspectRatio, " +
                    "fillCenter=${aspectRatio == AspectRatio.RATIO_FULL}, " +
                    "ready=${gpupixelProvider.isReady()}"
            )
            true
        } catch (error: Throwable) {
            Logger.w("Camera", "GPUPixel warm-up failed", error)
            onWarmUpFallback(error.message ?: "GPUPixel warm-up error")
            false
        }
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        // 调色参数：映射规则参考 beauty-engine/AGENTS.md §4.7 GPUPixel 专业调色滤镜接入规范
        // gpuContrast: UI 0-200，50=原始 → GPUPixel 0.0-4.0，1.0=原始 (÷50 × 1.0)
        // gpuSaturation: UI 0-200，100=原始 → GPUPixel 0.0-2.0，1.0=原始 (÷100)
        val gpuContrast = (settings.gpuContrast / 50f).coerceIn(0f, 4f)
        val gpuSaturation = (settings.gpuSaturation / 100f).coerceIn(0f, 2f)

        val beautyBase = if (!settings.enabled || !settings.hasAnyEffect()) {
            BeautyParams.EMPTY
        } else {
            BeautyParams(
                enabled = true,
                smoothing = (settings.smoothing / 100f).coerceIn(0f, 1f),
                whitening = (settings.whitening / 100f).coerceIn(0f, 1f),
                bigEyes = (settings.bigEyes / 100f).coerceIn(0f, 1f),
                slimFace = (settings.slimFace / 50f * 1.35f).coerceIn(-1f, 1f),
                lipColor = (settings.lipColor / 100f).coerceIn(0f, 1f),
                lipColorIndex = settings.lipColorIndex.coerceIn(0, 11),
                blush = (settings.blush / 100f).coerceIn(0f, 1f),
                blushColorFamily = settings.blushColorFamily.coerceIn(0, 2)
            )
        }
        // 将调色参数合并到 BeautyParams（调色始终生效，不依赖美颜开关）
        val params = beautyBase.copy(
            gpuExposure = settings.gpuExposure.coerceIn(-10f, 10f),
            gpuContrast = gpuContrast,
            gpuSaturation = gpuSaturation,
            gpuWhiteBalance = settings.gpuWhiteBalance.coerceIn(2000f, 10000f)
        )
        gpupixelProvider.updateFilters(params)
    }

    override fun applyFaceWarpParams(params: FaceWarpParams) {
        // GPUPixel 使用自带人脸检测器，忽略 ML Kit 传来的 FaceWarpParams
    }

    override fun release() {
        gpupixelProvider.release()
    }
}
