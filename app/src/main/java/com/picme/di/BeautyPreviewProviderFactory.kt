package com.picme.di

import android.content.Context
import com.picme.core.common.Logger
import com.picme.core.image.pixelfree.PixelFreeBeautyPreviewProvider
import com.picme.core.image.rplan.RPlanBeautyPreviewProvider
import com.picme.data.preferences.UserPreferencesRepository
import com.picme.domain.preview.BeautyPreviewProvider
import com.picme.data.preferences.BeautyStrategy as UserBeautyStrategy

/**
 * RD 美颜预览提供者工厂
 *
 * 职责：
 * 1. 集中管理 BeautyPreviewProvider 的创建
 * 2. 支持配置不同的实现策略（双轨切换）
 * 3. 便于单元测试时替换为 Mock
 *
 * 双引擎策略：
 * - 主引擎：R 计划自主方案（R_PLAN）
 * - 备用引擎：PixelFreeEffects SDK（PIXEL_FREE）
 *
 * 切换方式：设置页「美颜引擎」配置开关
 *
 * @see com.picme.core.image.pixelfree.PixelFreeBeautyPreviewProvider
 * @see com.picme.core.image.rplan.RPlanBeautyPreviewProvider
 */
object BeautyPreviewProviderFactory {
    
    /**
     * 美颜实现策略
     */
    enum class BeautyStrategy {
        /** PixelFreeEffects SDK 实现（备用方案）*/
        PIXEL_FREE,

        /** R 计划自主研发（主方案）*/
        R_PLAN
    }

    /**
     * 创建美颜预览提供者
     * 
     * @param context 应用上下文
     * @param strategy 实现策略（null 则从用户设置中读取）
     *
     * @return BeautyPreviewProvider 实例
     */
    fun create(
        context: Context,
        strategy: BeautyStrategy? = null
    ): BeautyPreviewProvider {
        val selectedStrategy = strategy ?: run {
            // 从用户设置中读取策略
            val repository = UserPreferencesRepository(context)
            val userStrategy = repository.getBeautyStrategyBlocking()

            when (userStrategy) {
                UserBeautyStrategy.PIXEL_FREE -> BeautyStrategy.PIXEL_FREE
                UserBeautyStrategy.R_PLAN -> BeautyStrategy.R_PLAN
            }
        }

        return when (selectedStrategy) {
            BeautyStrategy.PIXEL_FREE -> {
                Logger.i("Factory", "Using PixelFree SDK (user preference: ${selectedStrategy.name})")
                PixelFreeBeautyPreviewProvider(context).apply {
                    initialize()
                }
            }

            BeautyStrategy.R_PLAN -> {
                Logger.i("Factory", "Using R Plan (user preference: ${selectedStrategy.name})")
                try {
                    RPlanBeautyPreviewProvider(context).apply {
                        initialize()
                    }
                } catch (error: Throwable) {
                    BeautyEngineRuntimeState.markRPlanFallback(
                        error.message ?: "unknown"
                    )
                    Logger.w("Factory", "R Plan init failed, fallback to PixelFree", error)
                    PixelFreeBeautyPreviewProvider(context).apply {
                        initialize()
                    }
                }
            }
        }
    }

    /**
     * 获取当前使用的策略（从用户设置中读取）
     */
    fun getCurrentStrategy(context: Context): BeautyStrategy {
        val repository = UserPreferencesRepository(context)
        val userStrategy = repository.getBeautyStrategyBlocking()

        return when (userStrategy) {
            UserBeautyStrategy.PIXEL_FREE -> BeautyStrategy.PIXEL_FREE
            UserBeautyStrategy.R_PLAN -> BeautyStrategy.R_PLAN
        }
    }
}
