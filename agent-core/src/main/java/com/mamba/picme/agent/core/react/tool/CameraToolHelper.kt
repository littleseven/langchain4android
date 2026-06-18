package com.mamba.picme.agent.core.react.tool

import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentAction
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.local.parser.LocalCommandParser
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 相机工具辅助类。
 *
 * 统一处理相机工具的执行前准备：
 * 1. 检查当前场景，如果不在 CAMERA 场景，先导航到相机页
 * 2. 等待场景切换和 CameraCapability 绑定完成
 * 3. 构建 AgentCommand 并通过 CapabilityRegistry 分发执行
 *
 * 这是解决 ReAct Agent 远程控制时"相机命令在 CHAT 场景找不到 Capability"问题的关键：
 * CameraCapability 是页面级 Capability，只在 CameraScreen 中注册，不在全局 registry 中。
 * 当 App 不在相机页时，CapabilityRegistry.findCapabilityForCommand() 找不到 CameraCapability，
 * 导致命令被入队但永远不被执行（因为没有导航触发）。
 *
 * 此辅助类在工具层主动触发导航，确保相机命令能在正确的场景下执行。
 */
object CameraToolHelper {

    private const val TAG = "CameraToolHelper"
    private const val NAVIGATION_TIMEOUT_MS = 5000L
    private const val SCENE_POLL_INTERVAL_MS = 100L
    private const val CAPABILITY_WAIT_MS = 300L

    /**
     * 执行相机命令，确保在相机场景下执行。
     *
     * @param method 命令方法名（如 "capture", "switch_filter"）
     * @param params 命令参数 Map
     * @param buildCommandJson 构建命令 JSON 的 lambda
     * @param onSuccess 成功时的结果消息
     * @param onError 失败时的错误消息前缀
     * @return ToolResult
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun executeCameraCommand(
        method: String,
        params: Map<String, Any>,
        buildCommandJson: () -> String,
        onSuccess: (String) -> String,
        onError: (String) -> String
    ): ToolResult {
        return try {
            val registry = CapabilityRegistry.getInstance()
            val sceneManager = SceneManager.getInstance()

            // 1. 如果当前不在 CAMERA 场景，先导航到相机页
            val currentScene = sceneManager.currentScene.value
            if (currentScene != SceneManager.Scene.CAMERA) {
                Logger.i(TAG, "Current scene is $currentScene, navigating to CAMERA first")

                val navCommandJson = JSONObject().apply {
                    put("method", "navigate_to")
                    put("params", JSONObject().apply {
                        put("destination", "camera")
                    })
                }.toString()

                val navContext = AgentContext(scene = AgentScene.CAMERA)
                val navCommand = LocalCommandParser.parseCommandByMethod(
                    method = "navigate_to",
                    json = navCommandJson,
                    context = navContext,
                    fallbackText = ""
                )

                val navDeferred = GlobalScope.future {
                    registry.dispatch(navCommand, navContext, null)
                }
                val navResult = navDeferred.get(NAVIGATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                if (navResult.isFailure) {
                    return ToolResult.error("Navigation to camera failed: ${navResult.exceptionOrNull()?.message}")
                }

                // 2. 等待场景切换为 CAMERA
                val waitStart = System.currentTimeMillis()
                var waitCount = 0
                runBlocking {
                    while (sceneManager.currentScene.value != SceneManager.Scene.CAMERA &&
                        waitCount < (NAVIGATION_TIMEOUT_MS / SCENE_POLL_INTERVAL_MS).toInt()
                    ) {
                        delay(SCENE_POLL_INTERVAL_MS)
                        waitCount++
                    }
                }

                val elapsed = System.currentTimeMillis() - waitStart
                if (sceneManager.currentScene.value != SceneManager.Scene.CAMERA) {
                    return ToolResult.error("Timeout waiting for CAMERA scene after ${elapsed}ms")
                }
                Logger.i(TAG, "Scene switched to CAMERA after ${elapsed}ms")

                // 3. 额外等待 CameraCapability 绑定完成（delegate 初始化）
                runBlocking { delay(CAPABILITY_WAIT_MS) }
            }

            // 4. 构建并执行目标命令
            val commandJson = buildCommandJson()
            val context = AgentContext(scene = AgentScene.CAMERA)
            val command = LocalCommandParser.parseCommandByMethod(
                method = method,
                json = commandJson,
                context = context,
                fallbackText = ""
            )

            val deferred = GlobalScope.future {
                registry.dispatch(command, context, null)
            }
            val result = deferred.get(5, TimeUnit.SECONDS)

            result.fold(
                onSuccess = { action ->
                    val message = when (action) {
                        is AgentAction.Success -> onSuccess(method)
                        is AgentAction.TextReply -> action.message
                        else -> onSuccess(method)
                    }
                    ToolResult.success(message)
                },
                onFailure = { error ->
                    ToolResult.error(onError(error.message ?: "Unknown error"))
                }
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Camera command execution error", e)
            ToolResult.error("Camera command error: ${e.message}")
        }
    }
}
