package com.picme.testing.agent.engine

import com.picme.core.common.Logger
import com.picme.agent.core.runtime.capability.CapabilityRegistry
import com.picme.agent.core.api.command.AgentCommand
import com.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Agent 状态探针
 *
 * 通过 CapabilityRegistry 和 SceneManager 查询应用内部状态，
 * 替代脆弱的截图+图像识别验证方式。
 */
class AgentStateProbe(private val registry: CapabilityRegistry) {

    companion object {
        private const val TAG = "AgentStateProbe"
    }

    /**
     * 查询当前场景
     */
    fun currentScene(): SceneManager.Scene {
        return SceneManager.getInstance().currentScene.value
    }

    /**
     * 查询指定 Capability 是否可用
     */
    fun isCapabilityAvailable(capabilityName: String): Boolean {
        val capability = registry.get(capabilityName)
        return capability?.isAvailable() ?: false
    }

    /**
     * 查询命令在当前场景是否可执行
     */
    fun isCommandAvailable(command: AgentCommand): Boolean {
        return registry.isCommandAvailable(command)
    }

    /**
     * 获取当前页面状态快照
     *
     * 从多个来源收集状态信息，形成统一的状态视图。
     */
    fun captureStateSnapshot(): Map<String, Any?> {
        val snapshot = mutableMapOf<String, Any?>()

        try {
            // 1. 场景信息
            val scene = currentScene()
            snapshot["scene"] = scene.name
            snapshot["currentScene"] = scene.name

            // 2. Capability 可用性
            val capabilities = registry.getAll().map { cap ->
                mapOf(
                    "name" to cap.name,
                    "available" to cap.isAvailable(),
                    "scenes" to cap.activeScenes().map { it.name }
                )
            }
            snapshot["capabilities"] = capabilities

            // 3. 命令可用性（常用命令）
            snapshot["canCapture"] = isCommandAvailable(AgentCommand.CapturePhoto())
            snapshot["canNavigate"] = isCommandAvailable(AgentCommand.NavigateTo(destination = ""))
            snapshot["canSettings"] = isCapabilityAvailable("settings")

            // 4. 时间戳
            snapshot["timestamp"] = System.currentTimeMillis()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to capture state snapshot", e)
            snapshot["error"] = e.message
        }

        return snapshot
    }

    /**
     * 等待条件满足（带超时）
     */
    suspend fun waitFor(
        condition: () -> Boolean,
        timeout: Duration = 10.seconds,
        pollInterval: Duration = 500.milliseconds
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout.inWholeMilliseconds) {
            if (condition()) {
                return true
            }
            delay(pollInterval.inWholeMilliseconds)
        }

        return false
    }

    /**
     * 等待场景切换完成
     */
    suspend fun waitForScene(
        expectedScene: SceneManager.Scene,
        timeout: Duration = 5.seconds
    ): Boolean {
        return waitFor(
            condition = { currentScene() == expectedScene },
            timeout = timeout,
            pollInterval = 200.milliseconds
        )
    }

    /**
     * 等待 Capability 可用
     */
    suspend fun waitForCapability(
        capabilityName: String,
        timeout: Duration = 5.seconds
    ): Boolean {
        return waitFor(
            condition = { isCapabilityAvailable(capabilityName) },
            timeout = timeout,
            pollInterval = 200.milliseconds
        )
    }


}
