package com.mamba.picme

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mamba.picme.agent.core.react.tool.impl.BackTool
import com.mamba.picme.agent.core.react.tool.impl.GetScreenInfoTool
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.image.CoilConfig
import com.mamba.picme.di.AppContainer
import com.mamba.picme.di.AppContainerImpl
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.policy.AiAgentMode
import com.mamba.picme.agent.core.api.policy.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.platform.logging.Logger as AgentCoreLogger
import com.mamba.picme.agent.core.platform.mnn.MnnResourceManager
// Capability 导入已移除：页面级 Capability 由各 Screen 自行创建
import com.mamba.picme.domain.agent.remote.FeishuChannelHandler
import com.mamba.picme.domain.agent.remote.RemoteCommandDispatcher
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.mamba.picme.beauty.log.BeautyLogProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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

    val feishuChannelHandler: FeishuChannelHandler by lazy { FeishuChannelHandler(applicationScope) }

    val remoteCommandDispatcher: RemoteCommandDispatcher by lazy {
        val database = com.mamba.picme.data.local.AppDatabase.getDatabase(this)
        RemoteCommandDispatcher(
            feishuChannelHandler,
            this,
            database.chatMessageDao(),
            database.chatSessionDao()
        )
    }

    /**
     * 当前活跃的 Activity（用于测试截屏等场景）
     *
     * 通过 ActivityLifecycleCallbacks 自动跟踪，无需手动设置。
     */
    @Volatile
    var currentActivity: Activity? = null
        private set

    /**
     * 当前飞书消息处理 Job，用于新消息到达时取消旧任务
     * 防止多个 LLM 推理同时运行吃满 CPU 导致 ANR
     */
    @Volatile
    private var feishuDispatchJob: Job? = null

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

        // 预配置 AgentOrchestrator 默认远程推理配置（含网关 Token）
        // 必须在飞书通道初始化之前执行，确保远程推理管道在首次使用时已有可用认证凭证
        AgentOrchestrator.getInstance(this).configure(
            mode = AiAgentMode.REMOTE,
            modelId = "qwen3_5_2b",
            privacyLevel = AiAgentPrivacyLevel.STRICT,
            remoteConfig = RemoteModelConfig.TENCENT_SCF_DEFAULT.copy(
                gatewayToken = BuildConfig.TENCENT_SCF_APP_TOKEN
            )
        )
        Logger.i(TAG, "Orchestrator pre-configured with fallback remote config")

        // 注册 Activity 生命周期回调，跟踪当前活跃 Activity
        registerActivityLifecycleCallbacks(ActivityTracker())

        // 初始化飞书远程控制通道
        applicationScope.launch {
            try {
                val appId = container.userPreferencesRepository.feishuAppIdFlow.first()
                val appSecret = container.userPreferencesRepository.feishuAppSecretFlow.first()
                if (appId.isNotBlank() && appSecret.isNotBlank()) {
                    feishuChannelHandler.init(appId, appSecret)
                    // 绑定消息处理回调：飞书消息 → RemoteCommandDispatcher
                    // 前一个推理任务未完成时自动取消，防止多个 LLM 线程吃满 CPU
                    feishuChannelHandler.onMessageReceived = { text, messageId ->
                        feishuDispatchJob?.cancel()
                        feishuDispatchJob = applicationScope.launch {
                            remoteCommandDispatcher.dispatch(text, messageId)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "飞书通道初始化失败", e)
            }
        }

        // 监听飞书配置变化，自动重连
        applicationScope.launch {
            try {
                combine(
                    container.userPreferencesRepository.feishuAppIdFlow,
                    container.userPreferencesRepository.feishuAppSecretFlow
                ) { appId, appSecret -> Pair(appId, appSecret) }
                    .drop(1) // 跳过初始值，避免重复 init
                    .collect { (appId, appSecret) ->
                        if (appId.isNotBlank() && appSecret.isNotBlank()) {
                            feishuChannelHandler.reinit(appId, appSecret)
                        } else {
                            feishuChannelHandler.disconnect()
                        }
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "飞书配置监听失败", e)
            }
        }

        // 注册网络状态变化监听：网络恢复时自动重连飞书
        registerFeishuNetworkMonitor()

        // 同步 AI Agent 模式到 AgentOrchestrator，确保飞书远程控制
        // 的路由遵循用户在设置中选定的推理模式（LOCAL/REMOTE/OFF）
        syncAgentModeToOrchestrator()
    }

    /**
     * 注册网络状态监听，网络恢复时自动重连飞书通道
     */
    private fun registerFeishuNetworkMonitor() {
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Logger.w(TAG, "无法获取 ConnectivityManager")
                return
            }

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                private var wasUnavailable = true

                override fun onAvailable(network: Network) {
                    if (wasUnavailable) {
                        wasUnavailable = false
                        Logger.i(TAG, "网络恢复可用，触发飞书重连")
                        // 延迟 2 秒等待网络稳定后再重连
                        applicationScope.launch {
                            delay(2000)
                            feishuChannelHandler.reconnectIfNeeded()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    wasUnavailable = true
                    Logger.w(TAG, "网络连接丢失")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    val hasInternet = capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                    if (hasInternet && wasUnavailable) {
                        wasUnavailable = false
                        Logger.i(TAG, "网络能力恢复，触发飞书重连")
                        applicationScope.launch {
                            delay(2000)
                            feishuChannelHandler.reconnectIfNeeded()
                        }
                    }
                }
            }

            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
            Logger.i(TAG, "网络状态监听已注册")
        } catch (e: Exception) {
            Logger.e(TAG, "注册网络状态监听失败", e)
        }
    }

    /**
     * 同步 AI Agent 模式到 AgentOrchestrator
     *
     * **为什么需要这个方法**：
     * - [AgentOrchestrator] 是单例，其内部 [AgentConfigurator.agentMode] 默认值为 [AiAgentMode.LOCAL]
     * - [RemoteCommandDispatcher] 直接使用此单例，但从不调用 [AgentOrchestrator.configure]
     * - 如果用户在设置中切换了推理模式，而飞书路径没有收到通知，
     *   飞书消息会继续走旧的模式（或默认 LOCAL），与用户期望不符
     * - 此方法在启动时读取用户的设置，并在设置变化时自动同步
     *
     * **注意**：此方法只同步核心模式选择（LOCAL/REMOTE/OFF）。
     * 飞书远程控制走 SCF 默认兜底配置，远程模型详情由 [AiAgentUseCase] 管理。
     */
    private fun syncAgentModeToOrchestrator() {
        applicationScope.launch {
            try {
                val repository = container.userPreferencesRepository
                combine(
                    repository.aiAgentModeFlow,
                    repository.aiAgentLocalModelFlow,
                    repository.aiAgentPrivacyLevelFlow
                ) { mode, localModel, privacyLevel ->
                    Triple(mode, localModel, privacyLevel)
                }.collect { (mode, localModel, privacyLevel) ->
                    val orchestrator = AgentOrchestrator.getInstance(this@PicMeApplication)
                    val effectiveModel = localModel.takeIf { it.isNotBlank() } ?: "qwen3_5_2b"
                    orchestrator.configure(
                        mode = mode,
                        modelId = effectiveModel,
                        privacyLevel = privacyLevel
                    )
                    Logger.i(TAG, "Agent orchestrator synced: mode=$mode, model=$effectiveModel")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Agent mode sync failed", e)
            }
        }
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
                Logger.i(TAG, "App 回到前台")
                MnnResourceManager.getInstance(this@PicMeApplication).onAppForeground()
                // App 回到前台时检查飞书连接，断开则自动重连
                applicationScope.launch {
                    delay(1000) // 等待系统稳定
                    feishuChannelHandler.reconnectIfNeeded()
                }
            }
            activityCount++
        }
        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
            GetScreenInfoTool.currentRootView = activity.window.decorView.rootView
            BackTool.currentActivity = activity
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
     * sherpa-onnx-jni.so 由 SherpaOnnxAsrEngine / KeywordSpotterEngine 通过
     * System.loadLibrary 加载。sherpa-mnn-jni.so 供 LLM、人脸检测等 MNN 路径使用。
     * 在 Application 中提前加载可确保所有依赖 so 在类加载器命名空间中可见。
     */
    private fun loadNativeLibraries() {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            Logger.d(TAG, "Native library loaded: sherpa-onnx-jni")
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "Failed to load sherpa-onnx-jni", e)
        }
        try {
            System.loadLibrary("sherpa-mnn-jni")
            Logger.d(TAG, "Native library loaded: sherpa-mnn-jni")
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "Failed to load sherpa-onnx-jni", e)
        }
    }
}
