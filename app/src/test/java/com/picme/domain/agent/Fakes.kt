package com.picme.domain.agent

import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.remote.ExecutionResult
import com.picme.domain.agent.remote.StepResult

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
