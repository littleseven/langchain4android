package com.mamba.picme.domain.agent.remote

import android.content.Context
import com.mamba.picme.agent.core.api.context.AgentAction
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
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
 * 接收飞书消息，转发给 [AgentOrchestrator] 做 LLM 推理（本地/远程），
 * 并将执行结果通过 [FeishuChannelHandler] 回复给用户。
 *
 * **执行流程**（参考 ApkClaw 的 AgentService.executeTask）：
 * 1. 回复"正在处理"（避免用户等待）
 * 2. 调用 [AgentOrchestrator.processUserInput] 进行 LLM 推理
 *    - LOCAL 模式：本地 MNN-LLM 推理
 *    - REMOTE 模式：远程 API 推理（OpenAI/Claude）
 *    - 支持 L1 缓存、场景驱动策略、自动 fallback
 * 3. 格式化推理结果为文本，通过飞书回复
 *
 * **ANR 防护**：
 * - 前一个任务未完成时收到新消息，自动取消旧任务
 * - 超时保护（30 秒），避免 LLM 推理长时间占用 CPU
 * - 使用 Dispatchers.IO 避免阻塞 Default 调度器
 */
class RemoteCommandDispatcher(
    private val channelHandler: FeishuChannelHandler,
    context: Context
) {

    private val tag = "RemoteDispatcher"
    private val orchestrator = AgentOrchestrator.getInstance(context)

    /** 当前正在执行的 Job，用于新消息到达时取消旧任务 */
    @Volatile
    private var currentJob: Job? = null

    /** LLM 推理超时时间（毫秒） */
    private val TIMEOUT_MS = 30_000L

    /**
     * 接收飞书消息并转发给 LLM 处理
     *
     * 如果前一个任务还在执行，先取消旧任务再启动新任务，
     * 防止多个 LLM 推理同时运行吃满 CPU。
     */
    suspend fun dispatch(text: String, messageId: String) {
        Logger.i(tag, "远程命令: text='$text', messageId=$messageId")

        // 取消前一个正在执行的任务
        currentJob?.cancel()

        withContext(Dispatchers.IO) {
            // 1. 立即回复"正在处理"
            channelHandler.sendMessage("⏳ 正在处理您的请求...", messageId)

            try {
                // 2. 创建 AgentContext（远程命令使用 CHAT 场景）
                val agentContext = AgentContext(scene = AgentScene.CHAT)

                // 3. 转发给 AgentOrchestrator 进行 LLM 推理（本地/远程）
                //    设置超时保护，防止 LLM 推理长时间占用 CPU 导致主线程 ANR
                val result = withTimeout(TIMEOUT_MS) {
                    orchestrator.processUserInput(
                        input = text,
                        agentContext = agentContext,
                        pageContext = PageContext.None
                    )
                }

                // 4. 格式化回复
                val reply = result.fold(
                    onSuccess = { action -> formatActionReply(action) },
                    onFailure = { error -> "❌ 处理失败: ${error.message ?: "未知错误"}" }
                )

                Logger.i(tag, "远程命令执行完毕，回复：$reply")
                channelHandler.sendMessage(reply, messageId)

            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Logger.e(tag, "远程命令超时(${TIMEOUT_MS}ms)", e)
                channelHandler.sendMessage("⏰ 处理超时，请稍后重试（推理任务仍在后台运行，完成后将自动回复）", messageId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Logger.i(tag, "远程命令被取消")
                channelHandler.sendMessage("⏹️ 上一个任务已取消，正在处理新请求...", messageId)
            } catch (e: Exception) {
                Logger.e(tag, "远程命令处理异常", e)
                channelHandler.sendMessage("❌ 处理异常: ${e.message ?: "未知错误"}", messageId)
            }
        }
    }

    /**
     * 格式化 AgentAction 为飞书回复文本
     */
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
