package com.picme.domain.agent

import com.picme.agent.core.api.command.AgentCommand
import com.picme.agent.core.api.execution.ExecutionPlan
import com.picme.agent.core.api.execution.ExecutionResult
import com.picme.agent.core.api.execution.PlanStep
import com.picme.agent.core.api.execution.StepResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExecutionReporterImpl 单元测试
 */
class ExecutionReporterTest {

    @Test
    fun `step results are emitted correctly`() = runBlocking {
        val reporter = ExecutionReporterImpl()
        val collectedStepResults = mutableListOf<StepResult>()

        // Collect step results in a background job
        val collectJob = launch {
            reporter.stepResults.collect { stepResult ->
                collectedStepResults.add(stepResult)
            }
        }

        val planStep = PlanStep(
            step = 1,
            action = AgentCommand.CapturePhoto,
            description = "Take a photo"
        )
        val stepResult = StepResult.Executed(
            step = planStep,
            result = Result.success(Unit)
        )

        reporter.emitStepResult(stepResult)

        // Give some time for collection
        withTimeout(1000) {
            while (collectedStepResults.isEmpty()) {
                kotlinx.coroutines.delay(10)
            }
        }

        assertEquals(1, collectedStepResults.size)
        val collected = collectedStepResults[0]
        assertTrue(collected is StepResult.Executed)
        assertEquals(1, (collected as StepResult.Executed).step.step)

        collectJob.cancel()
    }

    @Test
    fun `final report is generated`() = runBlocking {
        val reporter = ExecutionReporterImpl()
        val collectedReports = mutableListOf<ExecutionResult>()

        // Collect execution results in a background job
        val collectJob = launch {
            reporter.executionResults.collect { result ->
                collectedReports.add(result)
            }
        }

        val executionResult = ExecutionResult(
            planId = "test-plan",
            stepResults = listOf(
                StepResult.Executed(
                    step = PlanStep(
                        step = 1,
                        action = AgentCommand.CapturePhoto,
                        description = "Step 1"
                    ),
                    result = Result.success(Unit)
                )
            )
        )

        reporter.report(executionResult)

        // Give some time for collection
        withTimeout(1000) {
            while (collectedReports.isEmpty()) {
                kotlinx.coroutines.delay(10)
            }
        }

        assertEquals(1, collectedReports.size)
        val collected = collectedReports[0]
        assertEquals("test-plan", collected.planId)
        assertEquals(1, collected.stepResults.size)
        assertTrue(collected.stepResults[0] is StepResult.Executed)

        collectJob.cancel()
    }

    @Test
    fun `multiple step results are emitted in order`() = runBlocking {
        val reporter = ExecutionReporterImpl()
        val collectedStepResults = mutableListOf<StepResult>()

        val collectJob = launch {
            reporter.stepResults.collect { stepResult ->
                collectedStepResults.add(stepResult)
            }
        }

        val step1 = PlanStep(step = 1, action = AgentCommand.CapturePhoto, description = "Step 1")
        val step2 = PlanStep(step = 2, action = AgentCommand.FlipCamera, description = "Step 2")
        val step3 = PlanStep(
            step = 3,
            action = AgentCommand.CapturePhoto,
            condition = "false",
            description = "Step 3"
        )

        reporter.emitStepResult(StepResult.Executed(step = step1, result = Result.success(Unit)))
        reporter.emitStepResult(StepResult.Executed(step = step2, result = Result.success(Unit)))
        reporter.emitStepResult(StepResult.Skipped(step = step3, reason = "Condition false"))

        withTimeout(1000) {
            while (collectedStepResults.size < 3) {
                kotlinx.coroutines.delay(10)
            }
        }

        assertEquals(3, collectedStepResults.size)
        assertEquals(1, (collectedStepResults[0] as StepResult.Executed).step.step)
        assertEquals(2, (collectedStepResults[1] as StepResult.Executed).step.step)
        assertEquals(3, (collectedStepResults[2] as StepResult.Skipped).step.step)

        collectJob.cancel()
    }

    @Test
    fun `report can be called multiple times`() = runBlocking {
        val reporter = ExecutionReporterImpl()
        val collectedReports = mutableListOf<ExecutionResult>()

        val collectJob = launch {
            reporter.executionResults.collect { result ->
                collectedReports.add(result)
            }
        }

        val result1 = ExecutionResult(
            planId = "plan-1",
            stepResults = emptyList()
        )
        val result2 = ExecutionResult(
            planId = "plan-2",
            stepResults = emptyList()
        )

        reporter.report(result1)
        reporter.report(result2)

        withTimeout(1000) {
            while (collectedReports.size < 2) {
                kotlinx.coroutines.delay(10)
            }
        }

        assertEquals(2, collectedReports.size)
        assertEquals("plan-1", collectedReports[0].planId)
        assertEquals("plan-2", collectedReports[1].planId)

        collectJob.cancel()
    }
}
