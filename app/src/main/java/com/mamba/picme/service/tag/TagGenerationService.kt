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
import com.mamba.picme.PicMeApplication
import com.mamba.picme.R
import com.mamba.picme.domain.tag.TagGenerationScheduler
import com.mamba.picme.domain.tag.TagScanProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * TAG 生成前台 Service
 *
 * ## 职责
 * - 封装 [TagGenerationScheduler] 为前台 Service，防止系统杀进程
 * - 使用固定单线程执行器保证任务串行执行
 * - 监控电池/热状态，通过 Guard 控制调度器行为
 * - 通过通知栏展示扫描进度
 *
 * ## 启动方式
 * ```
 * val intent = Intent(context, TagGenerationService::class.java)
 * context.startForegroundService(intent)
 * ```
 *
 * 停止：调用 [stopService] 或通过通知栏关闭。
 */
class TagGenerationService : Service() {

    companion object {
        private const val TAG = "TagGenService"
        private const val CHANNEL_ID = "picme_tag_generation"
        private const val CHANNEL_NAME = "TAG 生成"
        private const val NOTIFICATION_ID = 10043

        /** 电池电量阈值：低于此值暂停扫描 */
        private const val BATTERY_LOW_THRESHOLD = 15

        /** 电池电量阈值：低于此值终止扫描 */
        private const val BATTERY_CRITICAL_THRESHOLD = 5

        /** 电池电量充足（充电中或高于此值）正常运行 */
        private const val BATTERY_OK_THRESHOLD = 25

        /**
         * Service 管理的调度器实例。
         * 外部（AppContainer / UI）通过此引用访问。
         */
        @Volatile
        var scheduler: TagGenerationScheduler? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    // 单线程执行器：保证所有 TAG 生成任务串行执行
    private val singleThreadDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "tag-gen-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private var batteryLevel: Int = 100
    private var isCharging: Boolean = false
    private var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

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

        // 注册电池广播
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // 创建调度器（单线程 + 电池/热守卫）
        scheduler = TagGenerationScheduler(
            context = this,
            dispatcher = singleThreadDispatcher,
            guard = { checkGuard() }
        )

        // 监听进度变化，更新通知
        scheduler?.let { sched ->
            progressJob = serviceScope.launch {
                sched.progress.collectLatest { progress ->
                    updateNotification(progress, sched.isScanning.value)
                }
            }
        }

        android.util.Log.i(TAG, "Service created with single-thread dispatcher")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即调用 startForeground 满足 Android 5 秒限制
        startForeground(NOTIFICATION_ID, buildNotification(null, false))
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
        serviceScope.cancel()
        // 关闭单线程执行器
        singleThreadDispatcher.cancel()
        android.util.Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    /**
     * 电池/热状态守卫
     *
     * @return [TagGenerationScheduler.GuardResult] 指示允许/暂停/终止
     */
    private fun checkGuard(): TagGenerationScheduler.GuardResult {
        // 1. 检查电池电量
        if (!isCharging && batteryLevel <= BATTERY_CRITICAL_THRESHOLD) {
            android.util.Log.w(TAG, "Guard: battery critical ($batteryLevel%), aborting")
            return TagGenerationScheduler.GuardResult.ABORT
        }
        if (!isCharging && batteryLevel <= BATTERY_LOW_THRESHOLD) {
            android.util.Log.w(TAG, "Guard: battery low ($batteryLevel%), extended throttle")
            return TagGenerationScheduler.GuardResult.PAUSE
        }

        // 2. 检查热状态 (API 29+)
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
        } else {
            0
        }

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
