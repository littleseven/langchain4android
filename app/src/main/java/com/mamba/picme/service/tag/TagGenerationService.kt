package com.mamba.picme.service.tag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mamba.picme.MainActivity
import com.mamba.picme.R
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.TagGenerationScheduler
import com.mamba.picme.domain.tag.TagScanProgress
import com.mamba.picme.domain.tag.scan.ScanQueuePolicy
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * TAG 生成前台 Service —— Android Style.
 *
 * ## 对外接口
 * - **触发操作**：通过标准 Android Intent 驱动，见伴生对象的 Intent 构建方法
 * - **观察状态**：通过伴生对象的 StateFlow 暴露
 * - **Service 内部细节**（编排器、协程、单线程执行器）完全隐藏
 *
 * ## 使用示例
 * ```kotlin
 * // 启动全量扫描
 * startService(TagGenerationService.intentScanAll(context))
 *
 * // 观察扫描状态
 * TagGenerationService.isScanning.collect { ... }
 * TagGenerationService.sessionProgress.collect { ... }
 * ```
 */
class TagGenerationService : Service() {

    // ═══════════════════════════════════════════════════════════
    //  对外 API：Intent 动作 + 状态流
    // ═══════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "TagGenService"
        private const val CHANNEL_ID = "picme_tag_generation"
        private const val CHANNEL_NAME = "TAG 生成"
        private const val NOTIFICATION_ID = 10043

        /** 电池电量阈值：低于此值暂停扫描 */
        private const val BATTERY_LOW_THRESHOLD = 15
        /** 电池电量阈值：低于此值终止扫描 */
        private const val BATTERY_CRITICAL_THRESHOLD = 5

        // ── Intent Action 常量 ──────────────────────────────
        const val ACTION_SCAN_ALL = "com.mamba.picme.tag.SCAN_ALL"
        const val ACTION_SCAN_INCREMENTAL = "com.mamba.picme.tag.SCAN_INCREMENTAL"
        const val ACTION_SCAN_PASS_1 = "com.mamba.picme.tag.SCAN_PASS_1"
        const val ACTION_SCAN_PASS_2 = "com.mamba.picme.tag.SCAN_PASS_2"
        const val ACTION_SCAN_PASS_3 = "com.mamba.picme.tag.SCAN_PASS_3"
        const val ACTION_SCAN_PASS_3_FULL = "com.mamba.picme.tag.SCAN_PASS_3_FULL"
        const val ACTION_SCAN_PASS_4 = "com.mamba.picme.tag.SCAN_PASS_4"
        const val ACTION_SCAN_PASS_4_FULL = "com.mamba.picme.tag.SCAN_PASS_4_FULL"
        const val ACTION_REGENERATE_CATEGORIES = "com.mamba.picme.tag.REGENERATE_CATEGORIES"
        const val ACTION_PAUSE = "com.mamba.picme.tag.PAUSE"
        const val ACTION_RESUME = "com.mamba.picme.tag.RESUME"
        const val ACTION_CANCEL = "com.mamba.picme.tag.CANCEL"
        const val ACTION_RETRY_FAILED = "com.mamba.picme.tag.RETRY_FAILED"

        // Intent extras
        private const val EXTRA_CATEGORIES = "categories"
        private const val EXTRA_START_TIME_MS = "start_time_ms"
        private const val EXTRA_FULL_MODE = "full_mode"

        /** 创建用于启动 Service 的 Intent（首次需 [startForegroundService]） */
        private fun intent(context: Context, action: String): Intent =
            Intent(context, TagGenerationService::class.java).setAction(action)

        fun intentScanAll(context: Context) = intent(context, ACTION_SCAN_ALL)
        fun intentScanIncremental(context: Context) = intent(context, ACTION_SCAN_INCREMENTAL)
        fun intentScanPass1(context: Context) = intent(context, ACTION_SCAN_PASS_1)
        fun intentScanPass2(context: Context) = intent(context, ACTION_SCAN_PASS_2)
        fun intentScanPass3(context: Context) = intent(context, ACTION_SCAN_PASS_3)
        fun intentScanPass3Full(context: Context) = intent(context, ACTION_SCAN_PASS_3_FULL)
        fun intentScanPass4(context: Context) = intent(context, ACTION_SCAN_PASS_4)
        fun intentScanPass4Full(context: Context) = intent(context, ACTION_SCAN_PASS_4_FULL)

        /**
         * 按 TAG 类别 / 时间范围重新生成
         *
         * @param categories 需要重新生成的类别名称列表，如 ["SCENE", "TAGS"]
         * @param startTimeMs 时间范围起点（毫秒），0 表示不限制
         * @param fullMode true=清空旧标签后重标，false=仅补充缺失类别
         */
        fun intentRegenerateCategories(
            context: Context,
            categories: List<String>,
            startTimeMs: Long = 0L,
            fullMode: Boolean = false
        ): Intent = intent(context, ACTION_REGENERATE_CATEGORIES)
            .putStringArrayListExtra(EXTRA_CATEGORIES, ArrayList(categories))
            .putExtra(EXTRA_START_TIME_MS, startTimeMs)
            .putExtra(EXTRA_FULL_MODE, fullMode)

        fun intentPause(context: Context) = intent(context, ACTION_PAUSE)
        fun intentResume(context: Context) = intent(context, ACTION_RESUME)
        fun intentCancel(context: Context) = intent(context, ACTION_CANCEL)
        fun intentRetryFailed(context: Context) = intent(context, ACTION_RETRY_FAILED)

        /** 启动前台 Service（UI 进入 TAG 控制页时调用） */
        fun startForeground(context: Context) {
            context.startForegroundService(
                Intent(context, TagGenerationService::class.java)
            )
        }

        // ── 可观察状态（只读 StateFlow，不暴露调度器）─────────

        @Volatile
        private var orchestratorRef: TagScanOrchestrator? = null

        /** 是否正在扫描（Service 未运行时为 false） */
        val isScanning: MutableStateFlow<Boolean> = MutableStateFlow(false)

        /** 旧版扫描进度（兼容旧 UI，Service 未运行时为 null） */
        val progress: MutableStateFlow<TagScanProgress?> = MutableStateFlow(null)

        /** 最后一次扫描消息（Service 未运行时为 null） */
        val lastScanMessage: MutableStateFlow<String?> = MutableStateFlow(null)

        /** 新版会话级增强进度 */
        val sessionProgress: MutableStateFlow<TagScanSessionProgress?> = MutableStateFlow(null)

        private fun TagScanSessionProgress?.toLegacyProgress(): TagScanProgress? {
            if (this == null) return null
            return TagScanProgress(
                processed = processed,
                total = total,
                currentStage = when (currentPass) {
                    com.mamba.picme.data.local.entity.TagScanPass.FACE_DETECTION ->
                        com.mamba.picme.domain.tag.PipelineStage.FACE_ROI
                    com.mamba.picme.data.local.entity.TagScanPass.DBSCAN ->
                        com.mamba.picme.domain.tag.PipelineStage.FACE_CLUSTER
                    com.mamba.picme.data.local.entity.TagScanPass.QWEN_TAGGING ->
                        com.mamba.picme.domain.tag.PipelineStage.QWEN_TAGGING
                    com.mamba.picme.data.local.entity.TagScanPass.MOBILE_CLIP_ENCODING ->
                        com.mamba.picme.domain.tag.PipelineStage.MOBILE_CLIP
                    null ->
                        if (state == ScanSessionState.COMPLETED) {
                            com.mamba.picme.domain.tag.PipelineStage.COMPLETE
                        } else {
                            com.mamba.picme.domain.tag.PipelineStage.FACE_ROI
                        }
                },
                currentItem = currentMediaId?.toInt() ?: 0
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  内部实现（完全对外隐藏）
    // ═══════════════════════════════════════════════════════════

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    /**
     * 控制线程：负责状态机、DB 队列操作、进度更新。
     * 必须与任务线程分离，防止 JNI 阻塞时控制命令无法响应。
     */
    private val controlDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "tag-control").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /**
     * 任务线程：负责执行具体推理任务（人脸检测 / DBSCAN / Qwen 多模态）。
     * 与控制线程解耦，即使被 JNI 阻塞也不影响暂停/取消。
     */
    private val taskDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "tag-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private var batteryLevel: Int = 100
    private var isCharging: Boolean = false
    private var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

    private var scheduler: TagGenerationScheduler? = null
    private var orchestrator: TagScanOrchestrator? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (scale > 0) (batteryLevel * 100.0 / scale).roundToInt() else 100
            batteryLevel = pct
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            android.util.Log.d(TAG, "Battery: $batteryLevel% charging=$isCharging")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val sched = TagGenerationScheduler(
            context = this,
            dispatcher = taskDispatcher,
            guard = { checkGuard() },
            getThrottleMs = { getAdaptiveThrottleMs() }
        )
        scheduler = sched

        val orch = TagScanOrchestrator(
            context = this,
            scheduler = sched,
            dispatcher = controlDispatcher,
            db = AppDatabase.getDatabase(this)
        )
        orchestrator = orch
        orchestratorRef = orch

        progressJob = serviceScope.launch {
            orch.progress.collectLatest { sp ->
                sessionProgress.value = sp
                isScanning.value = sp?.state in setOf(
                    ScanSessionState.RUNNING,
                    ScanSessionState.PAUSING,
                    ScanSessionState.CANCELLING
                )
                progress.value = sp.toLegacyProgress()
                lastScanMessage.value = sp?.messages?.lastOrNull()?.text
                updateNotification(sp)
            }
        }

        android.util.Log.i(TAG, "Service created with orchestrator")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) {
            // 首次启动，仅显示前台通知
            startForeground(NOTIFICATION_ID, buildNotification(null))
            return START_NOT_STICKY
        }

        // 启动前台通知（任何首次 Intent 都需要）
        startForeground(NOTIFICATION_ID, buildNotification(null))

        val orch = orchestrator ?: return START_NOT_STICKY

        // 根据 Action 分发
        serviceScope.launch {
            when (intent.action) {
                ACTION_SCAN_ALL -> orch.scheduleAutoScan(ScanQueuePolicy())
                ACTION_SCAN_INCREMENTAL -> orch.scheduleAutoScan(ScanQueuePolicy())
                ACTION_SCAN_PASS_1 -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.FACE_DETECTION,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                )
                ACTION_SCAN_PASS_2 -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.DBSCAN,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.FULL
                )
                ACTION_SCAN_PASS_3 -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.QWEN_TAGGING,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                )
                ACTION_SCAN_PASS_3_FULL -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.QWEN_TAGGING,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.FULL
                )
                ACTION_SCAN_PASS_4 -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.MOBILE_CLIP_ENCODING,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                )
                ACTION_SCAN_PASS_4_FULL -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.MOBILE_CLIP_ENCODING,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.FULL
                )
                ACTION_REGENERATE_CATEGORIES -> {
                    val categoryNames = intent.getStringArrayListExtra(EXTRA_CATEGORIES) ?: arrayListOf()
                    val startTimeMs = intent.getLongExtra(EXTRA_START_TIME_MS, 0L)
                    val fullMode = intent.getBooleanExtra(EXTRA_FULL_MODE, false)

                    val categories = categoryNames.mapNotNull { name ->
                        runCatching { com.mamba.picme.domain.tag.TagCategory.valueOf(name) }.getOrNull()
                    }.toSet()

                    if (categories.isNotEmpty()) {
                        val query = com.mamba.picme.domain.tag.scan.TagScanQuery(
                            startTimeMs = startTimeMs.takeIf { it > 0 }
                        )
                        val mode = if (fullMode) {
                            com.mamba.picme.domain.tag.scan.ScanMode.FULL
                        } else {
                            com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                        }
                        orch.scheduleRegenerateByQuery(query, categories, mode)
                    }
                }
                ACTION_PAUSE -> orch.pause()
                ACTION_RESUME -> orch.resume()
                ACTION_CANCEL -> orch.cancel()
                ACTION_RETRY_FAILED -> orch.retryFailed()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        progressJob?.cancel()
        batteryReceiver.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        runBlocking { orchestrator?.cancel() }
        scheduler?.cancel()
        orchestrator = null
        scheduler = null
        orchestratorRef = null
        serviceScope.cancel()
        controlDispatcher.cancel()
        taskDispatcher.cancel()
        android.util.Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    /**
     * 自适应节流间隔：根据设备热状态分级
     *
     * - SEVERE:  30s（触发 ABORT，此值不使用）
     * - MODERATE: 10s（严重发热，大幅降低推理频率）
     * - LIGHT:    3s（轻微发热，适度降低）
     * - NONE:     1s（正常状态，原 500ms 对 CPU 推理过短）
     */
    private fun getAdaptiveThrottleMs(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(PowerManager::class.java)
            return when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_MODERATE -> 10_000L
                PowerManager.THERMAL_STATUS_LIGHT -> 3_000L
                else -> 1_000L
            }
        }
        return 1_000L
    }

    private fun checkGuard(): TagGenerationScheduler.GuardResult {
        if (!isCharging && batteryLevel <= BATTERY_CRITICAL_THRESHOLD) {
            android.util.Log.w(TAG, "Guard: battery critical ($batteryLevel%), aborting")
            return TagGenerationScheduler.GuardResult.ABORT
        }
        if (!isCharging && batteryLevel <= BATTERY_LOW_THRESHOLD) {
            android.util.Log.w(TAG, "Guard: battery low ($batteryLevel%), extended throttle")
            return TagGenerationScheduler.GuardResult.PAUSE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(PowerManager::class.java)
            thermalStatus = pm.currentThermalStatus
            if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                android.util.Log.w(TAG, "Guard: thermal severe ($thermalStatus), aborting")
                return TagGenerationScheduler.GuardResult.ABORT
            }
            if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                android.util.Log.w(TAG, "Guard: thermal moderate ($thermalStatus), extended throttle")
                return TagGenerationScheduler.GuardResult.PAUSE
            }
        }
        return TagGenerationScheduler.GuardResult.ALLOW
    }

    private fun updateNotification(progress: TagScanSessionProgress?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun buildNotification(progress: TagScanSessionProgress?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = getString(R.string.tag_gen_notification_title)
        val isScanning = progress?.state in setOf(
            ScanSessionState.RUNNING,
            ScanSessionState.PAUSING,
            ScanSessionState.CANCELLING
        )
        val content = when {
            progress == null -> getString(R.string.tag_gen_notification_idle)
            isScanning -> "${progress.processed}/${progress.total} 张 · ${progress.currentPass?.name ?: ""}"
            progress.state == ScanSessionState.PAUSED -> "已暂停"
            progress.state == ScanSessionState.COMPLETED -> "完成"
            else -> getString(R.string.tag_gen_notification_idle)
        }
        val progressPercent = if (progress != null && progress.total > 0) {
            (progress.processed * 100 / progress.total).coerceIn(0, 100)
        } else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progressPercent, !isScanning)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = getString(R.string.tag_gen_notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }
}
