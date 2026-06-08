package com.picme.domain.agent

import com.picme.agent.core.api.context.AgentAction
import com.picme.agent.core.api.command.AgentCommand
import com.picme.agent.core.api.context.AgentContext
import com.picme.agent.core.api.context.PageContext
import com.picme.agent.core.api.execution.ExecutionResult
import com.picme.agent.core.api.execution.StepResult

/**
 * 测试用的 Fake CommandDispatcher
 *
 * 通过 CommandDispatcher 接口注入，不依赖 CapabilityRegistry 的私有构造函数。
 */
class FakeCommandDispatcher : CommandDispatcher {

    var shouldSucceed = true
    var lastCommand: AgentCommand? = null

    override suspend fun dispatch(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        lastCommand = command
        return if (shouldSucceed) {
            Result.success(AgentAction.Success(command))
        } else {
            Result.failure(RuntimeException("Fake dispatch failure"))
        }
    }
}

/**
 * 测试用的 Fake ExecutionReporter
 */
class FakeExecutionReporter : ExecutionReporter {

    val reports = mutableListOf<ExecutionResult>()
    val stepResults = mutableListOf<StepResult>()

    override fun report(result: ExecutionResult) {
        reports.add(result)
    }

    override suspend fun emitStepResult(stepResult: StepResult) {
        stepResults.add(stepResult)
    }
}
