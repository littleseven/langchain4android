package com.mamba.picme.domain.agent.remote

import android.content.Context
import android.view.WindowManager
import com.mamba.picme.agent.core.api.context.AgentAction
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.api.policy.AiAgentMode
import com.mamba.picme.agent.core.api.context.PageContext
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 远程命令调度器
 *
 * 接收飞书消息，统一通过 [AgentOrchestrator.processFeishuInput] 处理，
 * 使用 ReAct 循环完成应用内 UI 自动化，并将结果通过 [FeishuChannelHandler] 回复给用户。
 *
 * **架构（2026-06-18，ADR-006 Phase 5）**：
 * - ReAct Agent 生命周期由 [AgentConfigurator] 管理（懒创建、缓存、清理）
 * - [AgentOrchestrator] 提供统一的 `processFeishuInput()` 入口
 * - 本调度器仅负责：消息接收 → 调用 Orchestrator → 结果回复
 *
 * **ANR 防护**：
 * - 前一个任务未完成时收到新消息，自动取消旧任务
 * - 超时保护（120 秒），避免 LLM 推理长时间占用 CPU
 * - 使用 Dispatchers.IO 避免阻塞 Default 调度器
 */
class RemoteCommandDispatcher(
    private val channelHandler: FeishuChannelHandler,
    context: Context
) {

    private val tag = "RemoteDispatcher"
    private val appContext = context.applicationContext
    private val orchestrator = AgentOrchestrator.getInstance(context)

    /** 当前正在执行的 Job，用于新消息到达时取消旧任务 */
    @Volatile
    private var currentJob: Job? = null

    /** ReAct 循环超时（毫秒）— 多轮交互需要更长 timeout */
    private val TIMEOUT_MS = 120_000L

    /**
     * 接收飞书消息并启动 ReAct Agent 处理
     *
     * 统一通过 [AgentOrchestrator.processFeishuInput] 执行 ReAct 循环，
     * 当 Agent 不可用时回退到原有 [AgentOrchestrator.processUserInput] 路径。
     */
    suspend fun dispatch(text: String, messageId: String) {
        Logger.i(tag, "远程命令: text='$text', messageId=$messageId")
        currentJob?.cancel()

        withContext(Dispatchers.IO) {
            channelHandler.sendMessage("⏳ 正在处理您的请求...", messageId)

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                // ── ReAct Agent 路径（统一走 AgentOrchestrator）──
                try {
                    val result = withTimeout(TIMEOUT_MS) {
                        orchestrator.processFeishuInput(text, wm, TIMEOUT_MS)
                    }
                    val reply = result.fold(
                        onSuccess = { it },
                        onFailure = { error -> "❌ ${error.message ?: "未知错误"}" }
                    )
                    Logger.i(tag, "远程命令执行完毕，回复：$reply")
                    channelHandler.sendMessage(reply, messageId)
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    channelHandler.sendMessage("⏰ 处理超时（${TIMEOUT_MS / 1000}秒），请稍后重试", messageId)
                }
            } else {
                // ── 回退路径 ──
                Logger.i(tag, "WindowManager 不可用，回退到原有路径")
                fallbackProcess(text, messageId)
            }
        }
    }

    /**
     * 回退到原有的 AgentOrchestrator 流程
     */
    private suspend fun fallbackProcess(text: String, messageId: String) {
        try {
            val agentContext = AgentContext(scene = AgentScene.CHAT)
            orchestrator.pushModeOverride(AiAgentMode.REMOTE)
            try {
                val result = withTimeout(30_000L) {
                    orchestrator.processUserInput(
                        input = text,
                        agentContext = agentContext,
                        pageContext = PageContext.None
                    )
                }
                val reply = result.fold(
                    onSuccess = { action -> formatActionReply(action) },
                    onFailure = { error -> "❌ 处理失败: ${error.message ?: "未知错误"}" }
                )
                channelHandler.sendMessage(reply, messageId)
            } finally {
                orchestrator.popModeOverride()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            channelHandler.sendMessage("⏰ 处理超时，请稍后重试", messageId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            channelHandler.sendMessage("⏹️ 上一个任务已取消，正在处理新请求...", messageId)
        } catch (e: Exception) {
            channelHandler.sendMessage("❌ 处理异常: ${e.message ?: "未知错误"}", messageId)
        }
    }

    private fun formatActionReply(action: AgentAction): String {
        return when (action) {
            is AgentAction.TextReply -> action.message
            is AgentAction.Success -> "✅ 操作已执行"
            is AgentAction.Error -> "❌ ${action.message}"
            is AgentAction.BatchResult -> {
                val parts = action.results.mapIndexed { i, r ->
                    when (r) {
                        is AgentAction.Success -> "  ✅ 步骤${i + 1}: 成功"
                        is AgentAction.TextReply -> "  💬 步骤${i + 1}: ${r.message}"
                        is AgentAction.Error -> "  ❌ 步骤${i + 1}: ${r.message}"
                        is AgentAction.BatchResult -> "  📦 步骤${i + 1}: 批量完成"
                    }
                }
                "📋 批量执行结果:\n${parts.joinToString("\n")}"
            }
        }
    }
}
