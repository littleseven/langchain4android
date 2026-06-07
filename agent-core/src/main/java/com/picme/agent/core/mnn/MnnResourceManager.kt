package com.picme.agent.core.mnn

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.picme.agent.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MNN 全局释放锁
 *
 * libMNN.so 的 EagerBufferAllocator 和 Express::Executor 是全局共享状态，
 * 非线程安全。所有涉及 MNN native 资源释放的操作必须通过此锁串行化，
 * 防止并发释放导致的 use-after-free / double-free 崩溃。
 *
 * 使用规范：
 * - 仅在执行 native unload/release 时持有
 * - 不要持有此锁调用可能触发 GC 的 Kotlin 代码
 * - 释放操作应尽量简短，避免阻塞主线程
 */
object MnnGlobalReleaseLock {
    private val lock = Object()
    private var activeOperationCount: Int = 0

    /**
     * 标记一次 MNN native 运行中操作（推理 / create / reset 等）。
     *
     * 释放操作会等待活跃操作归零后再执行，避免共享 Allocator 在使用中被销毁。
     */
    fun <T> withOperation(block: () -> T): T {
        synchronized(lock) {
            activeOperationCount += 1
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                activeOperationCount -= 1
                if (activeOperationCount <= 0) {
                    activeOperationCount = 0
                    lock.notifyAll()
                }
            }
        }
    }

    /**
     * 执行 MNN native 释放操作（线程安全）。
     *
     * 会先等待所有 in-flight MNN 操作完成，再串行执行释放，
     * 防止 use-after-free / allocator double-free。
     */
    fun <T> withLock(block: () -> T): T {
        synchronized(lock) {
            while (activeOperationCount > 0) {
                lock.wait(16L)
            }
            return block()
        }
    }
}

/**
 * MNN 共享资源协调管理器
 *
 * 解决 LLM (MNN::Transformer::Llm)、ASR (Sherpa-MNN via MNN::Express)
 * 与人脸检测 (MNN::Interpreter) 共享 libMNN.so 时的全局状态冲突和内存压力问题。
 *
 * 核心策略：
 * 1. 引用计数：LLM、ASR、FaceDetection 分别持有独立引用
 * 2. 场景状态机：根据当前页面（相机/聊天/设置）决定哪些模型需要常驻
 * 3. 协调释放：仅当所有相关方均同意释放时才执行真正的 native unload
 * 4. 软释放：单方释放时仅做状态清理（trimMemory / stopStreaming），保留模型
 * 5. 内存阈值：Native Heap 超过阈值时自动卸载非必要模型
 * 6. 生命周期感知：自动响应 App 前后台切换和系统内存压力
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

        // 内存阈值（MB）
        private const val NATIVE_HEAP_WARNING_MB = 2048L
        private const val NATIVE_HEAP_CRITICAL_MB = 2560L
        private const val NATIVE_HEAP_EMERGENCY_MB = 3072L

        // 人脸检测自动卸载延迟（相机页离开后）
        private const val FACE_DETECTION_UNLOAD_DELAY_MS = 5000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 引用计数 ─────────────────────────────────────────────

    private val llmRefCount = AtomicInteger(0)
    private val asrRefCount = AtomicInteger(0)
    private val faceDetectionRefCount = AtomicInteger(0)

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
     * 人脸检测是否被请求保持加载
     */
    val isFaceDetectionRequested: Boolean
        get() = faceDetectionRefCount.get() > 0

    /**
     * 是否有任何一方请求保持 MNN 资源
     */
    val isAnyRequested: Boolean
        get() = isLlmRequested || isAsrRequested || isFaceDetectionRequested

    // ── 场景状态 ─────────────────────────────────────────────

    /**
     * 当前应用场景
     */
    enum class Scene {
        CAMERA,      // 相机预览页：需要人脸检测，不需要 LLM
        CHAT,        // 聊天页：需要 LLM/ASR，不需要人脸检测
        SETTINGS,    // 设置页：可能需要下载/切换模型
        BACKGROUND,  // 后台：所有模型可卸载
        OTHER        // 其他页面：按需保留
    }

    private val currentScene = AtomicInteger(Scene.OTHER.ordinal)
    val scene: Scene
        get() = Scene.entries[currentScene.get()]

    // ── 状态追踪 ─────────────────────────────────────────────

    private val _isAppInForeground = AtomicBoolean(true)
    val isAppInForeground: Boolean
        get() = _isAppInForeground.get()

    private val backgroundUnloadScheduled = AtomicBoolean(false)
    private val faceDetectionUnloadScheduled = AtomicBoolean(false)

    // 上次检测到的 Native Heap（MB）
    private val lastNativeHeapMB = AtomicLong(0)

    // ── 监听器 ───────────────────────────────────────────────

    private val softTrimListeners = CopyOnWriteArrayList<() -> Unit>()
    private val safeUnloadListeners = CopyOnWriteArrayList<() -> Unit>()
    private val faceDetectionUnloadListeners = CopyOnWriteArrayList<() -> Unit>()
    private val faceDetectionLoadListeners = CopyOnWriteArrayList<() -> Unit>()
    private val memoryPressureListeners = CopyOnWriteArrayList<(MemoryPressureLevel) -> Unit>()

    // ── 生命周期回调注册 ─────────────────────────────────────

    init {
        registerLifecycleCallbacks()
        startMemoryMonitor()
    }

    private fun registerLifecycleCallbacks() {
        if (appContext is Application) {
            appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

                override fun onLowMemory() {
                    Logger.w(TAG, "System onLowMemory triggered")
                    handleMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                }

                override fun onTrimMemory(level: Int) {
                    Logger.d(TAG, "onTrimMemory level=$level")
                    handleMemoryPressure(level)
                }
            })
        }
    }

    // ── 场景管理 API ─────────────────────────────────────────

    /**
     * 切换应用场景
     *
     * 场景切换会触发模型的自动加载/卸载：
     * - CAMERA: 保留人脸检测，延迟卸载 LLM/ASR
     * - CHAT: 保留 LLM/ASR，立即卸载人脸检测
     * - BACKGROUND: 延迟卸载所有模型
     *
     * @param newScene 目标场景
     */
    fun setScene(newScene: Scene) {
        val oldScene = Scene.entries[currentScene.getAndSet(newScene.ordinal)]
        if (oldScene == newScene) return

        Logger.i(TAG, "Scene changed: $oldScene -> $newScene")

        when (newScene) {
            Scene.CAMERA -> {
                // 相机页：确保人脸检测可用，LLM 可延迟卸载
                cancelFaceDetectionUnload()
                notifyFaceDetectionLoad()
                // 如果 LLM 不再被引用，安排软释放
                if (!isLlmRequested) {
                    scheduleSoftTrim()
                }
            }
            Scene.CHAT -> {
                // 聊天页：人脸检测不再需要，立即安排卸载
                scheduleFaceDetectionUnload(immediate = false)
                // LLM/ASR 由各自的引用计数管理
            }
            Scene.SETTINGS -> {
                // 设置页：保留当前模型，不做激进卸载
                cancelFaceDetectionUnload()
            }
            Scene.BACKGROUND -> {
                // 后台：所有模型可卸载
                onAppBackground()
            }
            Scene.OTHER -> {
                // 其他页面：人脸检测延迟卸载
                scheduleFaceDetectionUnload(immediate = false)
            }
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
        Logger.d(TAG, "LLM acquired by $owner, refCount=$count")
        cancelBackgroundUnload()
    }

    /**
     * 释放 LLM 引用
     *
     * @param owner 请求者标识
     * @param onSafeUnload 当可以安全卸载时调用（无其他引用冲突）
     * @param onSoftRelease 当只能软释放时调用（其他方仍在使用）
     */
    fun releaseLlm(
        owner: String,
        onSafeUnload: () -> Unit,
        onSoftRelease: () -> Unit
    ) {
        val count = llmRefCount.decrementAndGet()
        Logger.d(TAG, "LLM released by $owner, refCount=$count")

        if (count <= 0) {
            synchronized(this) {
                llmRefCount.set(0)
                if (!hasOtherReferences(excludeLlm = true)) {
                    Logger.i(TAG, "LLM safe to unload (no other references)")
                    // 使用 MNN 全局锁串行化 native 释放
                    MnnGlobalReleaseLock.withLock { onSafeUnload() }
                } else {
                    Logger.i(TAG, "LLM soft release (other references active)")
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
        Logger.d(TAG, "ASR acquired by $owner, refCount=$count")
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
        Logger.d(TAG, "ASR released by $owner, refCount=$count")

        if (count <= 0) {
            synchronized(this) {
                asrRefCount.set(0)
                if (!hasOtherReferences(excludeAsr = true)) {
                    Logger.i(TAG, "ASR safe to unload (no other references)")
                    // 使用 MNN 全局锁串行化 native 释放
                    MnnGlobalReleaseLock.withLock { onSafeUnload() }
                } else {
                    Logger.i(TAG, "ASR soft release (other references active)")
                    onSoftRelease()
                }
            }
        }
    }

    /**
     * 请求保持人脸检测加载
     */
    fun acquireFaceDetection(owner: String) {
        val count = faceDetectionRefCount.incrementAndGet()
        Logger.d(TAG, "FaceDetection acquired by $owner, refCount=$count")
        cancelFaceDetectionUnload()
        cancelBackgroundUnload()
    }

    /**
     * 释放人脸检测引用
     */
    fun releaseFaceDetection(
        owner: String,
        onSafeUnload: () -> Unit,
        onSoftRelease: () -> Unit
    ) {
        val count = faceDetectionRefCount.decrementAndGet()
        Logger.d(TAG, "FaceDetection released by $owner, refCount=$count")

        if (count <= 0) {
            synchronized(this) {
                faceDetectionRefCount.set(0)
                if (!hasOtherReferences(excludeFaceDetection = true)) {
                    Logger.i(TAG, "FaceDetection safe to unload (no other references)")
                    // 使用 MNN 全局锁串行化 native 释放
                    MnnGlobalReleaseLock.withLock { onSafeUnload() }
                } else {
                    Logger.i(TAG, "FaceDetection soft release (other references active)")
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
        Logger.i(TAG, "App entered foreground")
    }

    /**
     * 应用进入后台时调用
     */
    fun onAppBackground() {
        _isAppInForeground.set(false)
        Logger.i(TAG, "App entered background, scheduling unload")

        if (backgroundUnloadScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(BACKGROUND_UNLOAD_DELAY_MS)
                if (!isAppInForeground && !isAnyRequested) {
                    Logger.i(TAG, "Background timeout, triggering soft trim for all")
                    notifySoftTrim()
                }

                delay(BACKGROUND_FORCE_UNLOAD_DELAY_MS - BACKGROUND_UNLOAD_DELAY_MS)
                if (!isAppInForeground && !isAnyRequested) {
                    Logger.i(TAG, "Background force unload timeout, triggering safe unload")
                    notifySafeUnload()
                }
                backgroundUnloadScheduled.set(false)
            }
        }
    }

    // ── 内存压力处理 ─────────────────────────────────────────

    /**
     * 内存压力等级
     */
    enum class MemoryPressureLevel {
        NORMAL,     // < 2GB
        WARNING,    // 2-2.5GB
        CRITICAL,   // 2.5-3GB
        EMERGENCY   // > 3GB
    }

    private fun handleMemoryPressure(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Logger.i(TAG, "Memory pressure: MODERATE, soft trim")
                notifySoftTrim()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Logger.i(TAG, "Memory pressure: LOW/CRITICAL, force unload")
                notifySafeUnload()
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                if (!isAnyRequested) {
                    Logger.i(TAG, "UI hidden, scheduling unload")
                    onAppBackground()
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Logger.w(TAG, "Memory pressure: COMPLETE, emergency unload")
                notifySafeUnload()
            }
        }
    }

    /**
     * 根据 Native Heap 压力自动降级
     */
    private fun handleNativeMemoryPressure(nativeHeapMB: Long) {
        lastNativeHeapMB.set(nativeHeapMB)

        val level = when {
            nativeHeapMB >= NATIVE_HEAP_EMERGENCY_MB -> MemoryPressureLevel.EMERGENCY
            nativeHeapMB >= NATIVE_HEAP_CRITICAL_MB -> MemoryPressureLevel.CRITICAL
            nativeHeapMB >= NATIVE_HEAP_WARNING_MB -> MemoryPressureLevel.WARNING
            else -> MemoryPressureLevel.NORMAL
        }

        if (level != MemoryPressureLevel.NORMAL) {
            Logger.w(TAG, "Native memory pressure: $level (heap=${nativeHeapMB}MB)")
            notifyMemoryPressure(level)
        }

        when (level) {
            MemoryPressureLevel.EMERGENCY -> {
                // 紧急：卸载一切可卸载的
                notifySafeUnload()
                scheduleFaceDetectionUnload(immediate = true)
            }
            MemoryPressureLevel.CRITICAL -> {
                // 严重：软释放 LLM（保留模型，清 KV Cache），卸载人脸检测
                notifySoftTrim()
                scheduleFaceDetectionUnload(immediate = true)
            }
            MemoryPressureLevel.WARNING -> {
                // 警告：仅软释放
                notifySoftTrim()
            }
            MemoryPressureLevel.NORMAL -> {
                // 正常：不做处理
            }
        }
    }

    // ── 内存监控 ─────────────────────────────────────────────

    private fun startMemoryMonitor() {
        scope.launch {
            while (true) {
                delay(5000) // 每 5 秒检查一次
                val nativeHeapMB = getNativeHeapSizeMB()
                if (nativeHeapMB >= NATIVE_HEAP_WARNING_MB) {
                    handleNativeMemoryPressure(nativeHeapMB)
                }
            }
        }
    }

    /**
     * 获取 Native Heap 大小（MB）
     */
    private fun getNativeHeapSizeMB(): Long {
        return try {
            val clazz = Class.forName("android.os.Debug")
            val method = clazz.getMethod("getNativeHeapAllocatedSize")
            val bytes = method.invoke(null) as Long
            bytes / 1024 / 1024
        } catch (e: Exception) {
            // 降级：通过 Runtime 估算
            val runtime = Runtime.getRuntime()
            (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        }
    }

    // ── 人脸检测自动卸载调度 ─────────────────────────────────

    private fun scheduleFaceDetectionUnload(immediate: Boolean) {
        if (faceDetectionUnloadScheduled.get()) return
        if (!isFaceDetectionRequested) return

        val delayMs = if (immediate) 0L else FACE_DETECTION_UNLOAD_DELAY_MS

        if (faceDetectionUnloadScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(delayMs)
                if (faceDetectionUnloadScheduled.get() && !isFaceDetectionRequested) {
                    Logger.i(TAG, "FaceDetection auto-unload triggered")
                    notifyFaceDetectionUnload()
                }
                faceDetectionUnloadScheduled.set(false)
            }
        }
    }

    private fun cancelFaceDetectionUnload() {
        faceDetectionUnloadScheduled.set(false)
    }

    private fun scheduleSoftTrim() {
        scope.launch {
            delay(3000)
            notifySoftTrim()
        }
    }

    // ── 监听器管理 ───────────────────────────────────────────

    fun registerSoftTrimListener(listener: () -> Unit) {
        softTrimListeners.add(listener)
    }

    fun registerSafeUnloadListener(listener: () -> Unit) {
        safeUnloadListeners.add(listener)
    }

    fun registerFaceDetectionUnloadListener(listener: () -> Unit) {
        faceDetectionUnloadListeners.add(listener)
    }

    fun registerFaceDetectionLoadListener(listener: () -> Unit) {
        faceDetectionLoadListeners.add(listener)
    }

    fun registerMemoryPressureListener(listener: (MemoryPressureLevel) -> Unit) {
        memoryPressureListeners.add(listener)
    }

    fun unregisterSoftTrimListener(listener: () -> Unit) {
        softTrimListeners.remove(listener)
    }

    fun unregisterSafeUnloadListener(listener: () -> Unit) {
        safeUnloadListeners.remove(listener)
    }

    fun unregisterFaceDetectionUnloadListener(listener: () -> Unit) {
        faceDetectionUnloadListeners.remove(listener)
    }

    fun unregisterFaceDetectionLoadListener(listener: () -> Unit) {
        faceDetectionLoadListeners.remove(listener)
    }

    fun unregisterMemoryPressureListener(listener: (MemoryPressureLevel) -> Unit) {
        memoryPressureListeners.remove(listener)
    }

    /**
     * 安全卸载通知
     *
     * 使用 IO 线程执行（而非主线程），并在 MnnGlobalReleaseLock 保护下串行化 native 释放。
     * 避免主线程阻塞，同时防止多模块并发释放 MNN 全局状态导致崩溃。
     */
    private fun notifySafeUnload() {
        scope.launch {
            MnnGlobalReleaseLock.withLock {
                safeUnloadListeners.forEach { it.invoke() }
            }
        }
    }

    private fun notifySoftTrim() {
        scope.launch {
            softTrimListeners.forEach { it.invoke() }
        }
    }

    private fun notifyFaceDetectionUnload() {
        scope.launch {
            MnnGlobalReleaseLock.withLock {
                faceDetectionUnloadListeners.forEach { it.invoke() }
            }
        }
    }

    private fun notifyFaceDetectionLoad() {
        scope.launch {
            faceDetectionLoadListeners.forEach { it.invoke() }
        }
    }

    private fun notifyMemoryPressure(level: MemoryPressureLevel) {
        mainHandler.post {
            memoryPressureListeners.forEach { it.invoke(level) }
        }
    }

    private fun cancelBackgroundUnload() {
        backgroundUnloadScheduled.set(false)
    }

    // ── 辅助方法 ─────────────────────────────────────────────

    /**
     * 检查是否有其他引用（排除指定类型）
     */
    private fun hasOtherReferences(
        excludeLlm: Boolean = false,
        excludeAsr: Boolean = false,
        excludeFaceDetection: Boolean = false
    ): Boolean {
        val llmActive = !excludeLlm && llmRefCount.get() > 0
        val asrActive = !excludeAsr && asrRefCount.get() > 0
        val faceActive = !excludeFaceDetection && faceDetectionRefCount.get() > 0
        return llmActive || asrActive || faceActive
    }

    /**
     * 当前是否可以安全卸载 LLM（不影响 ASR / FaceDetection）。
     */
    fun canSafelyUnloadLlm(): Boolean {
        synchronized(this) {
            return !hasOtherReferences(excludeLlm = true)
        }
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
            faceDetectionRefCount = faceDetectionRefCount.get(),
            currentScene = scene.name,
            appInForeground = isAppInForeground,
            javaHeapUsedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            nativeHeapMB = lastNativeHeapMB.get(),
            totalMemoryMB = memoryInfo.totalMem / 1024 / 1024,
            availableMemoryMB = memoryInfo.availMem / 1024 / 1024,
            lowMemory = memoryInfo.lowMemory
        )
    }

    data class MemoryStats(
        val llmRefCount: Int,
        val asrRefCount: Int,
        val faceDetectionRefCount: Int,
        val currentScene: String,
        val appInForeground: Boolean,
        val javaHeapUsedMB: Long,
        val nativeHeapMB: Long,
        val totalMemoryMB: Long,
        val availableMemoryMB: Long,
        val lowMemory: Boolean
    )
}
