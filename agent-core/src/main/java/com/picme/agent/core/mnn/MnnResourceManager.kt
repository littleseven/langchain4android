package com.picme.agent.core.mnn

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.picme.agent.core.AgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * MNN 共享资源协调管理器
 *
 * 解决 LLM (MNN::Transformer::Llm) 与 ASR (Sherpa-MNN via MNN::Express)
 * 共享 libMNN.so 时的全局状态冲突问题。
 *
 * 核心策略：
 * 1. 引用计数：LLM 和 ASR 分别持有独立引用
 * 2. 协调释放：仅当双方均同意释放时才执行真正的 native unload
 * 3. 软释放：单方释放时仅做状态清理（trimMemory / stopStreaming），保留模型
 * 4. 生命周期感知：自动响应 App 前后台切换和系统内存压力
 *
 * @param context Application Context
 */
class MnnResourceManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: MnnResourceManager? = null

        fun getInstance(context: Context): MnnResourceManager {
            return instance ?: synchronized(this) {
                instance ?: MnnResourceManager(context.applicationContext).also { instance = it }
            }
        }

        private const val TAG = "MnnResourceManager"
        private const val BACKGROUND_UNLOAD_DELAY_MS = 30000L
        private const val BACKGROUND_FORCE_UNLOAD_DELAY_MS = 60000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 引用计数 ─────────────────────────────────────────────

    private val llmRefCount = AtomicInteger(0)
    private val asrRefCount = AtomicInteger(0)

    /**
     * LLM 是否被请求保持加载
     */
    val isLlmRequested: Boolean
        get() = llmRefCount.get() > 0

    /**
     * ASR 是否被请求保持加载
     */
    val isAsrRequested: Boolean
        get() = asrRefCount.get() > 0

    /**
     * 是否有任何一方请求保持 MNN 资源
     */
    val isAnyRequested: Boolean
        get() = isLlmRequested || isAsrRequested

    // ── 状态追踪 ─────────────────────────────────────────────

    private val _isAppInForeground = AtomicBoolean(true)
    val isAppInForeground: Boolean
        get() = _isAppInForeground.get()

    private val backgroundUnloadScheduled = AtomicBoolean(false)

    // ── 监听器 ───────────────────────────────────────────────

    private val softTrimListeners = CopyOnWriteArrayList<() -> Unit>()
    private val safeUnloadListeners = CopyOnWriteArrayList<() -> Unit>()

    // ── 生命周期回调注册 ─────────────────────────────────────

    init {
        registerLifecycleCallbacks()
    }

    private fun registerLifecycleCallbacks() {
        if (appContext is Application) {
            appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

                override fun onLowMemory() {
                    AgentLogger.w(TAG, "System onLowMemory triggered")
                    handleMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                }

                override fun onTrimMemory(level: Int) {
                    AgentLogger.d(TAG, "onTrimMemory level=$level")
                    handleMemoryPressure(level)
                }
            })
        }
    }

    // ── 引用管理 API ─────────────────────────────────────────

    /**
     * 请求保持 LLM 加载
     *
     * @param owner 请求者标识，用于调试
     */
    fun acquireLlm(owner: String) {
        val count = llmRefCount.incrementAndGet()
        AgentLogger.d(TAG, "LLM acquired by $owner, refCount=$count")
        cancelBackgroundUnload()
    }

    /**
     * 释放 LLM 引用
     *
     * @param owner 请求者标识
     * @param onSafeUnload 当可以安全卸载时调用（无 ASR 引用冲突）
     * @param onSoftRelease 当只能软释放时调用（ASR 仍在使用）
     */
    fun releaseLlm(
        owner: String,
        onSafeUnload: () -> Unit,
        onSoftRelease: () -> Unit
    ) {
        val count = llmRefCount.decrementAndGet()
        AgentLogger.d(TAG, "LLM released by $owner, refCount=$count")

        if (count <= 0) {
            synchronized(this) {
                llmRefCount.set(0)
                if (asrRefCount.get() <= 0) {
                    AgentLogger.i(TAG, "LLM safe to unload (no ASR reference)")
                    onSafeUnload()
                } else {
                    AgentLogger.i(TAG, "LLM soft release (ASR still active)")
                    onSoftRelease()
                }
            }
        }
    }

    /**
     * 请求保持 ASR 加载
     */
    fun acquireAsr(owner: String) {
        val count = asrRefCount.incrementAndGet()
        AgentLogger.d(TAG, "ASR acquired by $owner, refCount=$count")
        cancelBackgroundUnload()
    }

    /**
     * 释放 ASR 引用
     */
    fun releaseAsr(
        owner: String,
        onSafeUnload: () -> Unit,
        onSoftRelease: () -> Unit
    ) {
        val count = asrRefCount.decrementAndGet()
        AgentLogger.d(TAG, "ASR released by $owner, refCount=$count")

        if (count <= 0) {
            synchronized(this) {
                asrRefCount.set(0)
                if (llmRefCount.get() <= 0) {
                    AgentLogger.i(TAG, "ASR safe to unload (no LLM reference)")
                    onSafeUnload()
                } else {
                    AgentLogger.i(TAG, "ASR soft release (LLM still active)")
                    onSoftRelease()
                }
            }
        }
    }

    // ── 应用前后台管理 ───────────────────────────────────────

    /**
     * 应用进入前台时调用
     */
    fun onAppForeground() {
        _isAppInForeground.set(true)
        cancelBackgroundUnload()
        AgentLogger.i(TAG, "App entered foreground")
    }

    /**
     * 应用进入后台时调用
     */
    fun onAppBackground() {
        _isAppInForeground.set(false)
        AgentLogger.i(TAG, "App entered background, scheduling unload")

        if (backgroundUnloadScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(BACKGROUND_UNLOAD_DELAY_MS)
                if (!isAppInForeground && !isAnyRequested) {
                    AgentLogger.i(TAG, "Background timeout, triggering soft trim for all")
                    notifySoftTrim()
                }

                delay(BACKGROUND_FORCE_UNLOAD_DELAY_MS - BACKGROUND_UNLOAD_DELAY_MS)
                if (!isAppInForeground && !isAnyRequested) {
                    AgentLogger.i(TAG, "Background force unload timeout, triggering safe unload")
                    notifySafeUnload()
                }
                backgroundUnloadScheduled.set(false)
            }
        }
    }

    // ── 内存压力处理 ─────────────────────────────────────────

    private fun handleMemoryPressure(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                AgentLogger.i(TAG, "Memory pressure: MODERATE, soft trim")
                notifySoftTrim()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                AgentLogger.i(TAG, "Memory pressure: LOW/CRITICAL, force unload")
                notifySafeUnload()
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                if (!isAnyRequested) {
                    AgentLogger.i(TAG, "UI hidden, scheduling unload")
                    onAppBackground()
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                AgentLogger.w(TAG, "Memory pressure: COMPLETE, emergency unload")
                notifySafeUnload()
            }
        }
    }

    // ── 监听器管理 ───────────────────────────────────────────

    fun registerSoftTrimListener(listener: () -> Unit) {
        softTrimListeners.add(listener)
    }

    fun registerSafeUnloadListener(listener: () -> Unit) {
        safeUnloadListeners.add(listener)
    }

    fun unregisterSoftTrimListener(listener: () -> Unit) {
        softTrimListeners.remove(listener)
    }

    fun unregisterSafeUnloadListener(listener: () -> Unit) {
        safeUnloadListeners.remove(listener)
    }

    private fun notifySoftTrim() {
        mainHandler.post {
            softTrimListeners.forEach { it.invoke() }
        }
    }

    private fun notifySafeUnload() {
        mainHandler.post {
            safeUnloadListeners.forEach { it.invoke() }
        }
    }

    private fun cancelBackgroundUnload() {
        backgroundUnloadScheduled.set(false)
    }

    // ── 调试诊断 ─────────────────────────────────────────────

    /**
     * 获取当前内存使用概况
     */
    fun getMemoryStats(): MemoryStats {
        val runtime = Runtime.getRuntime()
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        return MemoryStats(
            llmRefCount = llmRefCount.get(),
            asrRefCount = asrRefCount.get(),
            appInForeground = isAppInForeground,
            javaHeapUsedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            totalMemoryMB = memoryInfo.totalMem / 1024 / 1024,
            availableMemoryMB = memoryInfo.availMem / 1024 / 1024,
            lowMemory = memoryInfo.lowMemory
        )
    }

    data class MemoryStats(
        val llmRefCount: Int,
        val asrRefCount: Int,
        val appInForeground: Boolean,
        val javaHeapUsedMB: Long,
        val totalMemoryMB: Long,
        val availableMemoryMB: Long,
        val lowMemory: Boolean
    )
}
