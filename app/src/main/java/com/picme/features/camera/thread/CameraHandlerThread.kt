package com.picme.features.camera.thread

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.picme.core.common.Logger

/**
 * [Day1 线程隔离] 相机拍照专用 HandlerThread
 *
 * 职责边界：
 * - 唯一拥有者：ImageCapture.takePicture() 的回调执行 + 拍照后处理（Bitmap 旋转/裁剪/美颜/保存）
 * - 禁止：调用任何 CameraX API（bind/unbind）、GL API、Agent 状态机
 *
 * 线程名：PicMe-CameraCapture
 * 优先级：THREAD_PRIORITY_BACKGROUND + THREAD_PRIORITY_MORE_FAVORABLE
 */
class CameraHandlerThread : HandlerThread(
    THREAD_NAME,
    Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE
) {

    companion object {
        private const val THREAD_NAME = "PicMe-CameraCapture"
    }

    private var handler: Handler? = null

    @Volatile
    private var isReady = false

    init {
        start()
        looper  // 阻塞等待 Looper 就绪
        handler = Handler(looper)
        isReady = true
        Logger.i("PicMe:Thread", "CameraHandlerThread started: name=${Thread.currentThread().name}")
    }

    fun post(runnable: Runnable) {
        val h = handler ?: throw IllegalStateException("CameraHandlerThread not ready")
        h.post(runnable)
    }

    fun postDelayed(delayMs: Long, runnable: Runnable) {
        val h = handler ?: throw IllegalStateException("CameraHandlerThread not ready")
        h.postDelayed(runnable, delayMs)
    }

    /**
     * 在当前线程（必须是 CameraHandlerThread）同步执行
     */
    fun <T> executeSync(action: () -> T): T {
        check(Thread.currentThread().name == THREAD_NAME) {
            "executeSync must be called on $THREAD_NAME, current=${Thread.currentThread().name}"
        }
        return action()
    }

    override fun quitSafely(): Boolean {
        isReady = false
        handler = null
        val result = super.quitSafely()
        Logger.i("PicMe:Thread", "CameraHandlerThread quitSafely")
        return result
    }

    fun isThreadReady(): Boolean = isReady

    fun getHandler(): Handler? = handler
}
