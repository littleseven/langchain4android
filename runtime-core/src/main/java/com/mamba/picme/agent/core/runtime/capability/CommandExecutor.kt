package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.PageContext
import kotlinx.coroutines.withTimeout

/**
 * 命令执行器
 *
 * 负责命令执行、超时控制和异常处理。
 */
class CommandExecutor(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val ERROR_CODE_TIMEOUT = -32002
        const val ERROR_CODE_EXECUTION_FAILED = -32005
    }

    /**
     * 执行命令（带超时）
     */
    suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        capability: Capability
    ): Result<AgentAction> {
        return try {
            withTimeout(timeoutMs) {
                capability.execute(command, context, pageContext)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(CapabilityExecutionException(
                "Command execution timed out after ${timeoutMs}ms",
                ERROR_CODE_TIMEOUT,
                e
            ))
        } catch (e: Exception) {
            Result.failure(CapabilityExecutionException(
                "Command execution failed: ${e.message}",
                ERROR_CODE_EXECUTION_FAILED,
                e
            ))
        }
    }

    class CapabilityExecutionException(
        message: String,
        val errorCode: Int,
        cause: Throwable?
    ) : Exception(message, cause)
}
