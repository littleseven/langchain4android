package com.picme.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.picme.core.common.Logger
import com.picme.core.image.BeautyProcessor
import com.picme.core.image.GpuBeautyProcessor
import com.picme.core.image.ImageProcessor
import com.picme.core.image.ImageProcessorImpl
import com.picme.data.local.AppDatabase
import com.picme.data.local.MlKitOcrProcessor
import com.picme.data.preferences.UserPreferencesRepository
import com.picme.data.repository.MediaRepositoryImpl
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.repository.MediaRepository
import com.picme.domain.repository.UserSettingsRepository
import com.picme.domain.usecase.FindDuplicateMediaUseCase
import com.picme.domain.usecase.GetGroupedMediaUseCase
import com.picme.domain.usecase.OcrProcessor
import com.picme.features.gallery.MediaViewModel

data class MediaViewModelDependencies(
    val repository: MediaRepository,
    val getGroupedMediaUseCase: GetGroupedMediaUseCase,
    val findDuplicateMediaUseCase: FindDuplicateMediaUseCase,
    val ocrUseCase: OcrProcessor
)

class MediaViewModelFactory(
    private val dependencies: MediaViewModelDependencies
) : ViewModelProvider.Factory {

    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewModel(
                repository = dependencies.repository,
                getGroupedMediaUseCase = dependencies.getGroupedMediaUseCase,
                findDuplicateMediaUseCase = dependencies.findDuplicateMediaUseCase,
                ocrUseCase = dependencies.ocrUseCase
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

interface AppContainer {
    val repository: MediaRepository
    val userPreferencesRepository: UserSettingsRepository
    val imageProcessor: ImageProcessor

    fun createMediaViewModelFactory(): ViewModelProvider.Factory
}

class AppContainerImpl(private val context: Context) : AppContainer {

    private val database by lazy { AppDatabase.getDatabase(context) }

    /**
     * RD 美颜处理器 - 使用 R 计划自研方案（BIG_BEAUTY）
     */
    private val beautyProcessor: BeautyProcessor by lazy {
        GpuBeautyProcessor(context)
    }

    override val repository: MediaRepository by lazy {
        MediaRepositoryImpl(database.mediaDao(), context)
    }

    override val userPreferencesRepository: UserSettingsRepository by lazy {
        UserPreferencesRepository(context)
    }

    override val imageProcessor: ImageProcessor by lazy {
        ImageProcessorImpl(beautyProcessor)
    }

    private val ocrProcessor: OcrProcessor by lazy {
        MlKitOcrProcessor()
    }

    private val mediaViewModelDependencies: MediaViewModelDependencies by lazy {
        MediaViewModelDependencies(
            repository = repository,
            getGroupedMediaUseCase = GetGroupedMediaUseCase(),
            findDuplicateMediaUseCase = FindDuplicateMediaUseCase(repository),
            ocrUseCase = ocrProcessor
        )
    }

    private val mediaViewModelFactory: ViewModelProvider.Factory by lazy {
        MediaViewModelFactory(mediaViewModelDependencies)
    }

    override fun createMediaViewModelFactory(): ViewModelProvider.Factory {
        return mediaViewModelFactory
    }
}
