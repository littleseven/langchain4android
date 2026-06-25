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
import com.mamba.picme.domain.tag.TagGenerationScheduler
import com.mamba.picme.domain.tag.TagScanProgress
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
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * TAG 生成前台 Service —— Android Style.
 *
 * ## 对外接口
 * - **触发操作**：通过标准 Android Intent 驱动，见伴生对象的 Intent 构建方法
 * - **观察状态**：通过伴生对象的 StateFlow 暴露
 * - **Service 内部细节**（调度器、协程、单线程执行器）完全隐藏
 *
 * ## 使用示例
 * ```kotlin
 * // 启动 Pass 3 重新生成
 * startService(TagGenerationService.intentScanPass3Full(context))
 *
 * // 观察扫描状态
 * TagGenerationService.isScanning.collect { ... }
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
        const val ACTION_CANCEL = "com.mamba.picme.tag.CANCEL"

        /** 创建用于启动 Service 的 Intent（首次需 [startForegroundService]） */
        private fun intent(context: Context, action: String): Intent =
            Intent(context, TagGenerationService::class.java).setAction(action)

        fun intentScanAll(context: Context) = intent(context, ACTION_SCAN_ALL)
        fun intentScanIncremental(context: Context) = intent(context, ACTION_SCAN_INCREMENTAL)
        fun intentScanPass1(context: Context) = intent(context, ACTION_SCAN_PASS_1)
        fun intentScanPass2(context: Context) = intent(context, ACTION_SCAN_PASS_2)
        fun intentScanPass3(context: Context) = intent(context, ACTION_SCAN_PASS_3)
        fun intentScanPass3Full(context: Context) = intent(context, ACTION_SCAN_PASS_3_FULL)
        fun intentCancel(context: Context) = intent(context, ACTION_CANCEL)

        /** 启动前台 Service（UI 进入 TAG 控制页时调用） */
        fun startForeground(context: Context) {
            context.startForegroundService(
                Intent(context, TagGenerationService::class.java)
            )
        }

        // ── 可观察状态（只读 StateFlow，不暴露调度器）─────────

        @Volatile
        private var schedulerRef: TagGenerationScheduler? = null

        private val _idleIsScanning = MutableStateFlow(false)
        private val _idleProgress = MutableStateFlow<TagScanProgress?>(null)
        private val _idleLastMessage = MutableStateFlow<String?>(null)

        /** 是否正在扫描（Service 未运行时为 false） */
        val isScanning: StateFlow<Boolean>
            get() = schedulerRef?.isScanning ?: _idleIsScanning.asStateFlow()

        /** 扫描进度（Service 未运行时为 null） */
        val progress: StateFlow<TagScanProgress?>
            get() = schedulerRef?.progress ?: _idleProgress.asStateFlow()

        /** 最后一次扫描消息（Service 未运行时为 null） */
        val lastScanMessage: StateFlow<String?>
            get() = schedulerRef?.lastScanMessage ?: _idleLastMessage.asStateFlow()
    }

    // ═══════════════════════════════════════════════════════════
    //  内部实现（完全对外隐藏）
    // ═══════════════════════════════════════════════════════════

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    private val singleThreadDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "tag-gen-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private var batteryLevel: Int = 100
    private var isCharging: Boolean = false
    private var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

    private var scheduler: TagGenerationScheduler? = null

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
            dispatcher = singleThreadDispatcher,
            guard = { checkGuard() }
        )
        scheduler = sched
        schedulerRef = sched  // 用于状态流暴露

        progressJob = serviceScope.launch {
            sched.progress.collectLatest { progress ->
                updateNotification(progress, sched.isScanning.value)
            }
        }

        android.util.Log.i(TAG, "Service created with single-thread dispatcher")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) {
            // 首次启动，仅显示前台通知
            startForeground(NOTIFICATION_ID, buildNotification(null, false))
            return START_STICKY
        }

        // 启动前台通知（任何首次 Intent 都需要）
        startForeground(NOTIFICATION_ID, buildNotification(null, false))

        // 根据 Action 分发
        when (intent.action) {
            ACTION_SCAN_ALL -> scheduler?.scanAll()
            ACTION_SCAN_INCREMENTAL -> scheduler?.scanIncremental()
            ACTION_SCAN_PASS_1 -> scheduler?.scanPass1()
            ACTION_SCAN_PASS_2 -> scheduler?.scanPass2()
            ACTION_SCAN_PASS_3 -> scheduler?.scanPass3()
            ACTION_SCAN_PASS_3_FULL -> scheduler?.scanPass3Full()
            ACTION_CANCEL -> scheduler?.cancel()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        progressJob?.cancel()
        batteryReceiver.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        scheduler?.cancel()
        scheduler = null
        schedulerRef = null
        serviceScope.cancel()
        singleThreadDispatcher.cancel()
        android.util.Log.i(TAG, "Service destroyed")
        super.onDestroy()
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

    private fun updateNotification(progress: TagScanProgress?, isScanning: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(progress, isScanning))
    }

    private fun buildNotification(progress: TagScanProgress?, isScanning: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = getString(R.string.tag_gen_notification_title)
        val content = if (progress != null && isScanning) {
            getString(R.string.tag_gen_notification_progress, progress.processed, progress.total)
        } else if (isScanning) {
            getString(R.string.tag_gen_notification_starting)
        } else {
            getString(R.string.tag_gen_notification_idle)
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
