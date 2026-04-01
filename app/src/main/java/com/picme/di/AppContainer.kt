package com.picme.di

import android.content.Context
import com.picme.core.common.Logger
import com.picme.core.image.BeautyProcessor
import com.picme.core.image.GpuBeautyProcessor
import com.picme.core.image.ImageProcessor
import com.picme.core.image.ImageProcessorImpl
import com.picme.core.image.pixelfree.PixelFreeBeautyProcessor
import com.picme.data.local.AppDatabase
import com.picme.data.preferences.BeautyStrategy
import com.picme.data.preferences.UserPreferencesRepository
import com.picme.data.repository.MediaRepositoryImpl
import com.picme.domain.repository.MediaRepository

interface AppContainer {
    val repository: MediaRepository
    val userPreferencesRepository: UserPreferencesRepository
    val imageProcessor: ImageProcessor
}

object BeautyEngineRuntimeState {
    @Volatile
    private var fallbackReason: String? = null

    fun markRPlanFallback(reason: String) {
        fallbackReason = reason
    }

    fun consumeRPlanFallbackReason(): String? {
        val reason = fallbackReason
        fallbackReason = null
        return reason
    }
}

class AppContainerImpl(private val context: Context) : AppContainer {

    private val database by lazy { AppDatabase.getDatabase(context) }

    /**
     * [RD] 美颜处理器 - 根据用户设置动态选择
     * - BeautyStrategy.R_PLAN -> GpuBeautyProcessor（主引擎）
     * - BeautyStrategy.PIXEL_FREE -> PixelFreeBeautyProcessor（备用引擎）
     *
     * 当 R_PLAN 初始化失败时，自动回退 PixelFree，保证可用性。
     */
    private val beautyProcessor: BeautyProcessor by lazy {
        val userPrefs = UserPreferencesRepository(context)
        val strategy = userPrefs.getBeautyStrategyBlocking()

        when (strategy) {
            BeautyStrategy.PIXEL_FREE -> PixelFreeBeautyProcessor(context)
            BeautyStrategy.R_PLAN -> {
                try {
                    GpuBeautyProcessor(context)
                } catch (error: Throwable) {
                    BeautyEngineRuntimeState.markRPlanFallback(
                        error.message ?: "unknown"
                    )
                    Logger.w("DI", "R Plan init failed, fallback to PixelFree", error)
                    PixelFreeBeautyProcessor(context)
                }
            }
        }
    }

    override val repository: MediaRepository by lazy {
        MediaRepositoryImpl(database.mediaDao(), context)
    }

    override val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context)
    }

    override val imageProcessor: ImageProcessor by lazy {
        ImageProcessorImpl(beautyProcessor)
    }
}
