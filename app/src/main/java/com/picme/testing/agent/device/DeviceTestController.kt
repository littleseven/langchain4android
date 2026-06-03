package com.picme.testing.agent.device

import android.content.Context
import android.content.Intent




import com.picme.core.common.Logger
import com.picme.features.camera.test.CameraTestCommand
import com.picme.features.camera.test.CameraTestCommandDispatcher


import com.picme.features.camera.test.CameraTestResult
import com.picme.features.camera.test.CameraTestStateSnapshot
import com.picme.testing.agent.core.AgentTestContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 设备端测试控制器
 *
 * 封装所有设备操作，为 Agent 测试提供统一接口。
 * 将 adb 命令、截屏、日志收集等操作抽象为 Kotlin 协程 API。
 */
class DeviceTestController(private val context: Context) {

    companion object {
        private const val TAG = "AgentDeviceController"
        private const val SCREENSHOT_DIR = "/sdcard/PicMe_Agent_Test"
    }

    private var lastScreenshotPath: String? = null

    // ============================================
    // 应用生命周期控制
    // ============================================

    /**
     * 启动 PicMe 应用
     */
    suspend fun launchApp(): Boolean = withContext(Dispatchers.IO) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.picme")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                delay(2000)
                true
            } else {
                Logger.e(TAG, "Cannot find launch intent for com.picme")
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to launch app", e)
            false
        }
    }

    /**
     * 检查应用是否在前台运行
     */
    fun isAppForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningApps = activityManager.runningAppProcesses
        return runningApps?.any { it.processName == "com.picme" && it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND } == true
    }

    // ============================================
    // 相机操作
    // ============================================

    /**
     * 触发拍照
     */
    suspend fun capture(): Boolean {
        dispatchCommand(CameraTestCommand.Capture)
        delay(500)
        return true
    }

    /**
     * 切换前后摄像头
     */
    suspend fun flipCamera(): Boolean {
        dispatchCommand(CameraTestCommand.FlipCamera)
        delay(1500)
        return true
    }

    /**
     * 设置美颜参数
     */
    suspend fun setBeauty(smooth: Int? = null, whiten: Int? = null, slimFace: Int? = null, bigEye: Int? = null) {
        dispatchCommand(CameraTestCommand.SetBeauty(smooth, whiten, slimFace, bigEye))
        delay(300)
    }

    /**
     * 设置滤镜
     */
    suspend fun setFilter(filter: String) {
        dispatchCommand(CameraTestCommand.SetFilter(filter))
        delay(500)
    }

    /**
     * 切换场景模式
     */
    suspend fun setScene(scene: String) {
        dispatchCommand(CameraTestCommand.SetScene(scene))
        delay(500)
    }

    /**
     * 切换画幅比例
     */
    suspend fun setRatio(ratio: String) {
        dispatchCommand(CameraTestCommand.SetRatio(ratio))
        delay(500)
    }

    /**
     * 设置曝光补偿
     */
    suspend fun setExposure(exposure: Int) {
        dispatchCommand(CameraTestCommand.SetExposure(exposure))
        delay(300)
    }

    /**
     * 设置缩放
     */
    suspend fun setZoom(zoom: Float) {
        dispatchCommand(CameraTestCommand.SetZoom(zoom))
        delay(300)
    }

    /**
     * 切换美颜面板
     */
    suspend fun toggleBeautyPanel() {
        dispatchCommand(CameraTestCommand.ToggleBeautyPanel)
        delay(300)
    }

    /**
     * 切换滤镜面板
     */
    suspend fun toggleFilterPanel() {
        dispatchCommand(CameraTestCommand.ToggleFilterPanel)
        delay(300)
    }

    // ============================================
    // 相册操作
    // ============================================

    /**
     * 进入相册
     */
    suspend fun enterGallery() {
        dispatchCommand(CameraTestCommand.EnterGallery)
        delay(1500)
    }

    /**
     * 打开指定索引的照片
     */
    suspend fun openPhoto(index: Int) {
        dispatchCommand(CameraTestCommand.OpenPhoto(index))
        delay(1000)
    }

    /**
     * 进入编辑模式
     */
    suspend fun startEdit() {
        dispatchCommand(CameraTestCommand.StartEdit)
        delay(1500)
    }

    /**
     * 保存编辑
     */
    suspend fun saveEdit() {
        dispatchCommand(CameraTestCommand.SaveEdit)
        delay(2000)
    }

    /**
     * 设置编辑模式磨皮
     */
    suspend fun setEditSmooth(value: Int) {
        dispatchCommand(CameraTestCommand.SetSmooth(value))
        delay(500)
    }

    /**
     * 设置编辑模式美白
     */
    suspend fun setEditWhiten(value: Int) {
        dispatchCommand(CameraTestCommand.SetWhiten(value))
        delay(500)
    }

    // ============================================
    // 状态查询
    // ============================================

    /**
     * 获取当前相机状态快照
     */
    suspend fun getCameraState(timeout: Duration = 3.seconds): CameraTestStateSnapshot? {
        dispatchCommand(CameraTestCommand.GetState)

        return withTimeoutOrNull(timeout) {
            CameraTestCommandDispatcher.resultFlow.first { result ->
                result is CameraTestResult.State
            }.let { (it as CameraTestResult.State).state }
        }
    }

    // ============================================
    // 截屏与图像分析
    // ============================================

    /**
     * 截屏并保存
     */
    suspend fun takeScreenshot(name: String, ctx: AgentTestContext): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val filename = "${name}_${timestamp}.png"
            val path = "$SCREENSHOT_DIR/$filename"

            // 确保目录存在
            File(SCREENSHOT_DIR).mkdirs()

            // 使用 MediaProjection API 或 adb screencap
            // 这里通过 Runtime 执行 screencap（需要 shell 权限或 root）
            val process = Runtime.getRuntime().exec("screencap -p $path")
            process.waitFor()

            if (File(path).exists()) {
                lastScreenshotPath = path
                ctx.addScreenshot(name, path)
                Logger.i(TAG, "Screenshot saved: $path")
                path
            } else {
                Logger.w(TAG, "Screenshot file not created: $path")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to take screenshot", e)
            null
        }
    }

    /**
     * 获取最后截屏路径
     */
    fun getLastScreenshotPath(): String? = lastScreenshotPath

    // ============================================
    // 日志收集
    // ============================================

    /**
     * 收集 PicMe 相关日志
     */
    fun collectLogs(ctx: AgentTestContext, lines: Int = 200) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d -s PicMe:* *:S")
            val output = process.inputStream.bufferedReader().readText()

            output.lines().take(lines).forEach { line ->
                val level = when {
                    line.contains(" E ") -> com.picme.testing.agent.core.LogLevel.ERROR
                    line.contains(" W ") -> com.picme.testing.agent.core.LogLevel.WARN
                    line.contains(" D ") -> com.picme.testing.agent.core.LogLevel.DEBUG
                    else -> com.picme.testing.agent.core.LogLevel.INFO
                }
                ctx.addLog("PicMe", line, level)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect logs", e)
        }
    }

    // ============================================
    // 性能监控
    // ============================================

    /**
     * 获取 FPS 信息
     */
    fun getFpsInfo(): Map<String, Any> {
        return try {
            val process = Runtime.getRuntime().exec("dumpsys gfxinfo com.picme")
            val output = process.inputStream.bufferedReader().readText()

            val jankCount = Regex("Janky frames: ([0-9]+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val totalFrames = Regex("Total frames rendered: ([0-9]+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1

            mapOf(
                "jankCount" to jankCount,
                "totalFrames" to totalFrames,
                "rawOutput" to output
            )
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * 获取内存信息
     */
    fun getMemoryInfo(): Map<String, Any> {
        return try {
            val process = Runtime.getRuntime().exec("dumpsys meminfo com.picme")
            val output = process.inputStream.bufferedReader().readText()

            val totalPss = Regex("TOTAL PSS: ([0-9,]+)K").find(output)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: -1

            mapOf(
                "totalPssKb" to totalPss,
                "rawOutput" to output
            )
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    // ============================================
    // 私有方法
    // ============================================

    private fun dispatchCommand(command: CameraTestCommand) {
        Logger.i(TAG, "Dispatching command: ${command::class.simpleName}")
        CameraTestCommandDispatcher.dispatch(command)
    }
}
