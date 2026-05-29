package com.picme.domain.agent.remote

import com.picme.domain.agent.model.AgentCommand

/**
 * 执行计划（L3 Plan-and-Execute 模式）
 *
 * @property planId 计划唯一标识
 * @property steps 执行步骤列表
 * @property description 计划描述
 */
data class ExecutionPlan(
    val planId: String,
    val steps: List<PlanStep>,
    val description: String = ""
)

/**
 * 计划执行步骤
 *
 * @property step 步骤序号（从 1 开始）
 * @property action 要执行的命令
 * @property condition 执行条件（可选，为 null 时无条件执行）
 * @property description 步骤描述
 * @property delayMs 执行后延迟（毫秒，给 UI 反应时间）
 */
data class PlanStep(
    val step: Int,
    val action: AgentCommand,
    val condition: String? = null,
    val description: String = "",
    val delayMs: Long = 0
)

/**
 * 步骤执行结果
 */
sealed class StepResult {
    /**
     * 步骤已执行
     */
    data class Executed(val step: PlanStep, val result: Result<Unit>) : StepResult()

    /**
     * 步骤被跳过（条件不满足）
     */
    data class Skipped(val step: PlanStep, val reason: String) : StepResult()

    /**
     * 步骤执行失败
     */
    data class Failed(val step: PlanStep, val error: Throwable) : StepResult()
}

/**
 * 计划执行结果
 *
 * @property planId 计划 ID
 * @property stepResults 各步骤执行结果
 */
data class ExecutionResult(
    val planId: String,
    val stepResults: List<StepResult>
) {
    /**
     * 是否全部成功
     */
    val isSuccess: Boolean
        get() = stepResults.all { it is StepResult.Executed && it.result.isSuccess }

    /**
     * 执行成功的步骤数
     */
    val successCount: Int
        get() = stepResults.count { it is StepResult.Executed && it.result.isSuccess }

    /**
     * 被跳过的步骤数
     */
    val skippedCount: Int
        get() = stepResults.count { it is StepResult.Skipped }

    /**
     * 失败的步骤数
     */
    val failedCount: Int
        get() = stepResults.count { it is StepResult.Failed ||
            (it is StepResult.Executed && it.result.isFailure) }
}
