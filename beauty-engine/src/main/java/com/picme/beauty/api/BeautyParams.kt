package com.picme.beauty.api

/**
 * R Plan 美颜参数
 *
 * 新模块专用参数容器，解耦对 app 层 BeautySettings 的依赖。
 * app 层负责将 BeautySettings 转换为 BeautyParams 后传入。
 *
 * 所有强度值说明：
 * - smoothing   : 磨皮 0.0~1.0（已归一化）
 * - whitening   : 美白 0.0~1.0（已归一化）
 * - bigEyes     : 大眼 0.0~1.0（已归一化）
 * - slimFace    : 瘦脸 -1.0~1.0（已归一化，负值为丰满）
 * - lipColor    : 唇色强度 0.0~1.0
 * - lipColorIndex : 唇色色号 0~11
 * - blush       : 腮红强度 0.0~1.0
 * - blushColorFamily : 腮红色系 0=粉/1=橙/2=梅
 *
 * 专业调色参数（GPUPixel 路径专用，大美丽引擎忽略此字段）：
 * - gpuExposure      : 曝光 -10.0~10.0，0 为原始（对应 GPUPixel ExposureFilter exposure）
 * - gpuContrast      : 对比度 0.0~4.0，1.0 为原始（对应 GPUPixel ContrastFilter contrast）
 * - gpuSaturation    : 饱和度 0.0~2.0，1.0 为原始（对应 GPUPixel SaturationFilter saturation）
 * - gpuWhiteBalance  : 色温 2000~10000K，5000 为原始（对应 GPUPixel WhiteBalanceFilter temperature）
 *
 * 风格特效参数（GPUPixel 路径专用，大美丽引擎忽略此字段）：
 * - styleFilterClassName : GPUPixel 风格滤镜类名，null 表示无特效（对应 StyleFilter 枚举）
 *                         合法值："ToonFilter" | "SmoothToonFilter" | "SketchFilter" |
 *                                "PosterizeFilter" | "EmbossFilter" | "CrosshatchFilter" | null
 *
 * 色调滤镜参数（大美丽引擎路径专用，GPUPixel 路径忽略此字段）：
 * - colorMatrix : 4x5 颜色矩阵（20个 float），用于预览时实时色调变换。
 *                 layout 与 Android ColorMatrix 一致（行主序，4行×5列，第5列为平移量，已归一化到 0~1）。
 *                 null 表示无色调滤镜（直通）。
 */
data class BeautyParams(
    val enabled: Boolean = false,
    val smoothing: Float = 0f,
    val whitening: Float = 0f,
    val bigEyes: Float = 0f,
    val slimFace: Float = 0f,
    val lipColor: Float = 0f,
    val lipColorIndex: Int = 0,
    val blush: Float = 0f,
    val blushColorFamily: Int = 0,
    // 专业调色参数（GPUPixel 路径专用）
    val gpuExposure: Float = 0f,
    val gpuContrast: Float = 1f,
    val gpuSaturation: Float = 1f,
    val gpuWhiteBalance: Float = 5000f,
    // 风格特效参数（GPUPixel 路径专用）
    val styleFilterClassName: String? = null,
    // 色调滤镜矩阵（大美丽引擎路径专用）：4x5 ColorMatrix，null 表示无滤镜
    val colorMatrix: FloatArray? = null
) {
    companion object {
        val EMPTY = BeautyParams()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BeautyParams) return false
        return enabled == other.enabled &&
            smoothing == other.smoothing &&
            whitening == other.whitening &&
            bigEyes == other.bigEyes &&
            slimFace == other.slimFace &&
            lipColor == other.lipColor &&
            lipColorIndex == other.lipColorIndex &&
            blush == other.blush &&
            blushColorFamily == other.blushColorFamily &&
            gpuExposure == other.gpuExposure &&
            gpuContrast == other.gpuContrast &&
            gpuSaturation == other.gpuSaturation &&
            gpuWhiteBalance == other.gpuWhiteBalance &&
            styleFilterClassName == other.styleFilterClassName &&
            colorMatrix.contentEquals(other.colorMatrix)
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + smoothing.hashCode()
        result = 31 * result + whitening.hashCode()
        result = 31 * result + bigEyes.hashCode()
        result = 31 * result + slimFace.hashCode()
        result = 31 * result + lipColor.hashCode()
        result = 31 * result + lipColorIndex
        result = 31 * result + blush.hashCode()
        result = 31 * result + blushColorFamily
        result = 31 * result + gpuExposure.hashCode()
        result = 31 * result + gpuContrast.hashCode()
        result = 31 * result + gpuSaturation.hashCode()
        result = 31 * result + gpuWhiteBalance.hashCode()
        result = 31 * result + styleFilterClassName.hashCode()
        result = 31 * result + colorMatrix.contentHashCode()
        return result
    }
}

