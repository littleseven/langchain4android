package com.picme.di

import android.content.Context
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

class AppContainerImpl(private val context: Context) : AppContainer {

    private val database by lazy { AppDatabase.getDatabase(context) }

    /**
     * [RD] 美颜处理器 - 根据用户设置动态选择
     * - BeautyStrategy.PIXEL_FREE -> PixelFreeBeautyProcessor（短期方案）
     * - BeautyStrategy.R_PLAN -> GpuBeautyProcessor（中长期自研方案，暂时使用 GPU 实现）
     */
    private val beautyProcessor: BeautyProcessor by lazy {
        val userPrefs = UserPreferencesRepository(context)
        val strategy = userPrefs.getBeautyStrategyBlocking()

        when (strategy) {
            BeautyStrategy.PIXEL_FREE -> PixelFreeBeautyProcessor(context)
            BeautyStrategy.R_PLAN -> GpuBeautyProcessor(context)
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
