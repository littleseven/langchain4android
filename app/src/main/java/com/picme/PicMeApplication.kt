package com.picme

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.picme.core.common.Logger
import com.picme.core.image.CoilConfig
import com.picme.di.AppContainer
import com.picme.di.AppContainerImpl
import com.picme.domain.agent.CapabilityRegistry
import com.picme.domain.agent.capability.CameraCapability
import com.picme.domain.agent.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.capability.SettingsCapability
import com.picme.domain.repository.MediaRepository
import com.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
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

        // 注册应用级 Capability（只注册一次，永不注销）
        initializeCapabilities()
    }

    /**
     * 初始化应用级 Capability
     *
     * 所有 Capability 在 Application.onCreate() 中注册一次，永不注销。
     * 页面通过绑定/解绑 delegate 来激活/停用 Capability。
     * 这支持跨页面指令：命令可以在后台排队，当目标页面激活时自动执行。
     */
    private fun initializeCapabilities() {
        val registry = CapabilityRegistry.getInstance()

        // 注册导航 Capability（全局可用）- 使用单例实例
        registry.register(NavigationCapability.getInstance())
        Logger.i("PicMe:Application", "NavigationCapability registered")

        // 注册相机 Capability（仅在 CAMERA 场景可用，需绑定 delegate）- 使用单例实例
        registry.register(CameraCapability.getInstance())
        Logger.i("PicMe:Application", "CameraCapability registered")

        // 注册相册 Capability（仅在 GALLERY 场景可用，需绑定 delegate）- 使用单例实例
        registry.register(GalleryCapability.getInstance())
        Logger.i("PicMe:Application", "GalleryCapability registered")

        // 注册设置 Capability（仅在 SETTINGS 场景可用，需绑定 delegate）- 使用单例实例
        registry.register(SettingsCapability.getInstance())
        Logger.i("PicMe:Application", "SettingsCapability registered")

        Logger.i("PicMe:Application", "All capabilities initialized")
    }

    override fun newImageLoader(): ImageLoader {
        return CoilConfig.createImageLoader(this)
    }
}
