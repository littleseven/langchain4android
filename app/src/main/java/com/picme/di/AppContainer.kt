package com.picme.di

import android.content.Context
import com.picme.core.image.BeautyProcessor
import com.picme.core.image.GpuBeautyProcessor
import com.picme.core.image.ImageProcessor
import com.picme.core.image.ImageProcessorImpl
import com.picme.data.local.AppDatabase
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
    private val beautyProcessor: BeautyProcessor by lazy { GpuBeautyProcessor(context) }

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
