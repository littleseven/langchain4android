package com.picme

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.picme.core.common.Logger
import com.picme.core.image.CoilConfig
import com.picme.di.AppContainer
import com.picme.di.AppContainerImpl
import com.picme.domain.agent.CapabilityRegistry
// Capability 导入已移除：页面级 Capability 由各 Screen 自行创建
import com.picme.domain.repository.MediaRepository
import com.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.beauty.log.BeautyLogProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PicMeApplication : Application(), ImageLoaderFactory {

    companion object {
        private const val TAG = "Application"
    }

    val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var container: AppContainer
        private set

    val repository: MediaRepository
        get() = container.repository

    /**
     * 当前活跃的 Activity（用于测试截屏等场景）
     *
     * 通过 ActivityLifecycleCallbacks 自动跟踪，无需手动设置。
     */
    @Volatile
    var currentActivity: Activity? = null
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainerImpl(this)

        // 初始化人脸关键点适配器注册表
        FaceLandmarkAdapterRegistry.initDefaults()

        // 绑定 Beauty Engine 日志代理，使 beauty-engine 模块的日志受 Logger 模块开关控制
        BeautyLogProxy.bindLogger(Logger)

        // 从 DataStore 加载日志模块配置并同步到 Logger
        applicationScope.launch {
            val config = container.userPreferencesRepository.logModuleConfigFlow.first()
            Logger.setModuleConfig(config)
        }

        // 注册应用级 Capability（只注册一次，永不注销）
        initializeCapabilities()

        // 注册 Activity 生命周期回调，跟踪当前活跃 Activity
        registerActivityLifecycleCallbacks(ActivityTracker())
    }

    /**
     * Activity 生命周期跟踪器
     *
     * 用于测试框架获取当前前台 Activity 进行截屏等操作。
     */
    private inner class ActivityTracker : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
            Logger.d(TAG, "Activity resumed: ${activity.javaClass.simpleName}")
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivity == activity) {
                currentActivity = null
            }
        }
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            // Activity 销毁时清理引用
            // NavigationCapability 现在由 MainActivity 持有，随 Activity 自动释放
            if (currentActivity == activity) {
                currentActivity = null
            }
        }
    }

    /**
     * 初始化应用级 Capability
     *
     * **已废弃**：Capability 现在采用页面级生命周期管理。
     * - NavigationCapability 由 MainActivity 创建（Activity 级）
     * - CameraCapability/GalleryCapability/SettingsCapability 由各 Screen 创建（页面级）
     *
     * 保留此方法用于向后兼容的日志输出，实际注册已移至 MainActivity 和各 Screen。
     */
    private fun initializeCapabilities() {
        Logger.i(TAG, "Capability lifecycle migrated to page-scoped model")
        Logger.i(TAG, "- NavigationCapability: Activity-scoped (MainActivity)")
        Logger.i(TAG, "- CameraCapability: Page-scoped (CameraScreen)")
        Logger.i(TAG, "- GalleryCapability: Page-scoped (GalleryScreen)")
        Logger.i(TAG, "- SettingsCapability: Page-scoped (SettingsScreen)")
    }

    override fun newImageLoader(): ImageLoader {
        return CoilConfig.createImageLoader(this)
    }
}
