package com.picme.di

import android.content.Context
import com.picme.beauty.api.BeautyPreviewProvider
import com.picme.beauty.egl.GlBeautyPreviewProviderFactory
import com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider
import com.picme.core.common.Logger
import com.picme.data.preferences.UserPreferencesRepository
import com.picme.domain.model.BeautyStrategy

/**
 * 美颜预览提供者工厂
 *
 * 职责：
 * 1. 集中管理 BeautyPreviewProvider 的创建
 * 2. 支持配置不同的实现策略
 * 3. 便于单元测试时替换为 Mock
 *
 * 当前仅保留主引擎：R 计划自主方案（BIG_BEAUTY）
 * 策略枚举已迁移至 [com.picme.domain.model.BeautyStrategy]
 *
 * @see com.picme.beauty.egl.GlBeautyPreviewProviderFactory
 */
object BeautyPreviewProviderFactory {

    /**
     * 创建美颜预览提供者
     *
     * @param context 应用上下文
     * @param strategy 实现策略（null 则从用户设置中读取）
     * @return BeautyPreviewProvider 实例
     */
    fun create(
        context: Context,
        strategy: BeautyStrategy? = null
    ): BeautyPreviewProvider {
        val selectedStrategy = strategy ?: run {
            val repository = UserPreferencesRepository(context)
            repository.getBeautyStrategyBlocking()
        }

        return when (selectedStrategy) {
            BeautyStrategy.BIG_BEAUTY -> {
                Logger.i("Factory", "Using R Plan (user preference: ${selectedStrategy.name})")
                GlBeautyPreviewProviderFactory().create(context).apply {
                    initialize()
                }
            }
            BeautyStrategy.GPUPIXEL -> {
                Logger.i("Factory", "Using GPUPixel (user preference: ${selectedStrategy.name})")
                GpupixelBeautyPreviewProvider(context).apply {
                    initialize()
                }
            }
        }
    }

    /**
     * 获取当前使用的策略（从用户设置中读取）
     */
    fun getCurrentStrategy(context: Context): BeautyStrategy {
        val repository = UserPreferencesRepository(context)
        return repository.getBeautyStrategyBlocking()
    }
}
