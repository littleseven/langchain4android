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
import com.picme.agent.core.runtime.capability.CapabilityRegistry
import com.picme.agent.core.platform.logging.Logger as AgentCoreLogger
import com.picme.agent.core.platform.mnn.MnnResourceManager
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

        // 预加载 Native 库（agent-core 模块依赖这些库，但 agent-core 不直接依赖 beauty-engine
        // 的 aar，因此需要在 Application 中统一加载，确保类加载器命名空间可见）
        loadNativeLibraries()

        container = AppContainerImpl(this)

        // 初始化人脸关键点适配器注册表
        FaceLandmarkAdapterRegistry.initDefaults()

        // 绑定 Beauty Engine 日志代理，使 beauty-engine 模块的日志受 Logger 模块开关控制
        BeautyLogProxy.bindLogger(Logger)

        // 绑定 Agent Core 日志代理，使 agent-core 模块的日志受 Logger 模块开关控制
        AgentCoreLogger.setDelegate(object : AgentCoreLogger {
            override fun d(tag: String, message: String) = Logger.d(tag, message)
            override fun i(tag: String, message: String) = Logger.i(tag, message)
            override fun w(tag: String, message: String) = Logger.w(tag, message)
            override fun w(tag: String, message: String, throwable: Throwable) = Logger.w(tag, message, throwable)
            override fun e(tag: String, message: String, throwable: Throwable?) = Logger.e(tag, message, throwable)
            override fun isLogEnabled(tag: String): Boolean = Logger.isLogEnabled(tag)
        })

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
     * 用于测试框架获取当前前台 Activity 进行截屏等操作，
     * 同时联动 MnnResourceManager 实现应用级前后台状态感知。
     */
    private inner class ActivityTracker : ActivityLifecycleCallbacks {
        private var activityCount = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {
            if (activityCount == 0) {
                MnnResourceManager.getInstance(this@PicMeApplication).onAppForeground()
            }
            activityCount++
        }
        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
            Logger.d(TAG, "Activity resumed: ${activity.javaClass.simpleName}")
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivity == activity) {
                currentActivity = null
            }
        }
        override fun onActivityStopped(activity: Activity) {
            activityCount--
            if (activityCount == 0) {
                MnnResourceManager.getInstance(this@PicMeApplication).onAppBackground()
            }
        }
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

    /**
     * 预加载 Native 共享库
     *
     * sherpa-mnn-jni.so 由 SherpaMnnAsrEngine 通过 System.loadLibrary 加载，
     * 但它依赖 libMNN_Express.so 中的符号。在 Application 中提前加载可确保
     * 所有依赖 so 在类加载器命名空间中可见，避免运行时符号解析失败。
     */
    private fun loadNativeLibraries() {
        try {
            System.loadLibrary("sherpa-mnn-jni")
            Logger.d(TAG, "Native library loaded: sherpa-mnn-jni")
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "Failed to load sherpa-mnn-jni", e)
        }
    }
}
