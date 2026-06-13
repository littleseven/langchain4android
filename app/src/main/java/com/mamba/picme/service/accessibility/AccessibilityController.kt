package com.mamba.picme.service.accessibility

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/**
 * 无障碍控制器
 *
 * 作为 AccessibilityService 与 Capability 之间的桥梁：
 * - Capability 将动作投递到控制器
 * - 控制器在 AccessibilityService 连接后，把动作交给服务执行
 *
 * 设计为单例，避免 Capability 直接持有 Service 引用导致内存泄漏。
 */
object AccessibilityController {

    private val serviceRef = AtomicReference<PicMeAccessibilityService?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * AccessibilityService 连接时调用
     */
    fun attachService(service: PicMeAccessibilityService) {
        serviceRef.set(service)
    }

    /**
     * AccessibilityService 断开时调用
     */
    fun detachService(service: PicMeAccessibilityService) {
        serviceRef.compareAndSet(service, null)
    }

    /**
     * 当前是否有无障碍服务可用
     */
    fun isServiceConnected(): Boolean = serviceRef.get() != null

    /**
     * 投递无障碍动作到执行队列
     *
     * @return 若服务未连接则返回失败
     */
    fun enqueue(action: AccessibilityAction): Result<Unit> {
        val service = serviceRef.get()
            ?: return Result.failure(IllegalStateException("无障碍服务未开启"))

        mainHandler.post {
            service.performAction(action)
        }
        return Result.success(Unit)
    }
}
