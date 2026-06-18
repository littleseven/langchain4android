package com.mamba.picme.domain.agent.remote

import android.content.Context
import android.view.WindowManager
import com.mamba.picme.agent.core.api.context.AgentAction
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.api.policy.AiAgentMode
import com.mamba.picme.agent.core.api.context.PageContext
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.react.InAppAgentCallback
import com.mamba.picme.agent.core.react.InAppAgentConfig
import com.mamba.picme.agent.core.react.InAppAgentService
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CompletableDeferred
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
 * **ReAct Agent 集成（2026-06）**：
 * 远程命令现在优先使用 [InAppAgentService]（ReAct 循环），
 * 通过 get_screen_info → LLM 思考 → 执行工具 → 验证的循环完成 UI 自动化。
 *
 * **执行流程**（参考 ApkClaw 的 AgentService.executeTask）：
 * 1. 回复"正在处理"（避免用户等待）
 * 2. 调用 [InAppAgentService.executeTask] 启动 ReAct 循环
 *    - LLM 分析屏幕 → 决定操作 → 执行工具 → 重新观察
 *    - 支持死亡循环检测、Token 优化
 * 3. ReAct 回调通知结果，通过飞书回复
 *
 * **回退机制**：当 ReAct Agent 不可用时（如 Activity 未就绪），
 * 回退到原有的 [AgentOrchestrator.processUserInput] 路径。
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
    private val appContext = context.applicationContext
    private val orchestrator = AgentOrchestrator.getInstance(context)

    /** 当前正在执行的 Job，用于新消息到达时取消旧任务 */
    @Volatile
    private var currentJob: Job? = null

    /** ReAct 循环超时（毫秒）— 多轮交互需要更长 timeout */
    private val TIMEOUT_MS = 120_000L

    /** ReAct Agent（懒创建，首次飞书消息到达时初始化） */
    private val reactAgent: InAppAgentService? by lazy {
        try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                Logger.w(tag, "WindowManager 不可用，ReAct Agent 禁用")
                return@lazy null
            }
            val cfg = com.mamba.picme.agent.core.api.android.RemoteModelConfig.TENCENT_SCF_DEFAULT
            val agentCfg = InAppAgentConfig.Builder()
                .apiKey(cfg.apiKey)
                .baseUrl(cfg.baseUrl)
                .modelName(cfg.modelId)
                .gatewayToken(com.mamba.picme.BuildConfig.TENCENT_SCF_APP_TOKEN)
                .build()
            val agent = InAppAgentService(agentCfg, wm, object : InAppAgentCallback {
                override fun onLoopStart(iteration: Int) {
                    Logger.d(tag, "ReAct 迭代 #$iteration")
                }
                override fun onContent(iteration: Int, content: String) {}
                override fun onToolCall(iteration: Int, toolName: String, args: String) {
                    Logger.d(tag, "Agent → $toolName(${args.take(100)})")
                }
                override fun onToolResult(iteration: Int, toolName: String, result: String) {
                    Logger.d(tag, "Agent ← $toolName → ${result.take(80)}")
                }
                override fun onComplete(iteration: Int, summary: String, totalTokens: Int) {
                    Logger.i(tag, "Agent 完成: 共 ${iteration} 轮, ${totalTokens} tokens")
                }
                override fun onError(iteration: Int, error: Throwable, totalTokens: Int) {
                    Logger.e(tag, "Agent 错误: ${error.message}")
                }
            }, appContext)
            agent.initialize()
            Logger.i(tag, "ReAct Agent 就绪")
            agent
        } catch (e: Exception) {
            Logger.e(tag, "ReAct Agent 初始化失败", e)
            null
        }
    }

    /**
     * 接收飞书消息并启动 ReAct Agent 处理
     *
     * 优先使用 [InAppAgentService] 执行多轮 ReAct 循环（Observe→Think→Act→Verify），
     * 当 Agent 不可用时回退到原有 [AgentOrchestrator.processUserInput] 路径。
     */
    suspend fun dispatch(text: String, messageId: String) {
        Logger.i(tag, "远程命令: text='$text', messageId=$messageId")
        currentJob?.cancel()

        withContext(Dispatchers.IO) {
            channelHandler.sendMessage("⏳ 正在处理您的请求...", messageId)

            val agent = reactAgent
            if (agent != null && !agent.isRunning()) {
                // ── ReAct Agent 路径 ──
                try {
                    val reply = CompletableDeferred<String>()
                    agent.executeTask(text, object : InAppAgentCallback {
                        override fun onLoopStart(iteration: Int) {}
                        override fun onContent(iteration: Int, content: String) {}
                        override fun onToolCall(iteration: Int, toolName: String, args: String) {
                            Logger.d(tag, "Agent → $toolName(${args.take(100)})")
                        }
                        override fun onToolResult(iteration: Int, toolName: String, result: String) {}
                        override fun onComplete(iteration: Int, summary: String, totalTokens: Int) {
                            Logger.i(tag, "Agent 完成: 共${iteration}轮, ${totalTokens}tokens")
                            reply.complete("✅ $summary")
                        }
                        override fun onError(iteration: Int, error: Throwable, totalTokens: Int) {
                            reply.complete("❌ ${error.message ?: "未知错误"}")
                        }
                    })
                    val result = withTimeout(TIMEOUT_MS) { reply.await() }
                    Logger.i(tag, "远程命令执行完毕，回复：$result")
                    channelHandler.sendMessage(result, messageId)
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    agent.cancel()
                    channelHandler.sendMessage("⏰ 处理超时（${TIMEOUT_MS / 1000}秒），请稍后重试", messageId)
                }
            } else {
                // ── 回退路径 ──
                Logger.i(tag, "ReAct Agent 不可用，回退到原有路径")
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
