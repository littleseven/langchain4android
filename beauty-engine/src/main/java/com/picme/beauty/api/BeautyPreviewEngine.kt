package com.picme.beauty.api

import android.view.View

/**
 * 美颜预览引擎组合接口
 *
 * 同时继承 [BeautyPreviewProvider] 与 [BeautyPreviewCapability]，
 * 用于需要完整预览 + 人脸变形能力的场景。
 *
 * @since Phase 3（库化）
 */
interface BeautyPreviewEngine : BeautyPreviewProvider, BeautyPreviewCapability {

    /**
     * 获取引擎内部托管的预览视图实例（供 UI 层嵌入容器）
     */
    fun getView(): View
}
