package com.picme.beauty.egl

import android.content.Context
import com.picme.beauty.api.BeautyPreviewProvider
import com.picme.beauty.api.BeautyPreviewProviderFactory

/**
 * GL 美颜预览提供者工厂实现
 *
 * 封装 [GlBeautyPreviewProvider] 的创建逻辑，对外仅暴露 [BeautyPreviewProvider] 接口。
 */
class GlBeautyPreviewProviderFactory : BeautyPreviewProviderFactory {

    override fun create(context: Context): BeautyPreviewProvider {
        return GlBeautyPreviewProvider(context)
    }
}
