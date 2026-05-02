package com.picme

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.picme.core.image.CoilConfig
import com.picme.di.AppContainer
import com.picme.di.AppContainerImpl
import com.picme.domain.repository.MediaRepository
import com.picme.features.camera.facedetect.adapter.FaceLandmarkAdapterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class PicMeApplication : Application(), ImageLoaderFactory {

    val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var container: AppContainer
        private set

    val repository: MediaRepository
        get() = container.repository

    override fun onCreate() {
        super.onCreate()
        container = AppContainerImpl(this)

        // 初始化人脸关键点适配器注册表
        FaceLandmarkAdapterRegistry.initDefaults()
    }

    override fun newImageLoader(): ImageLoader {
        return CoilConfig.createImageLoader(this)
    }
}
