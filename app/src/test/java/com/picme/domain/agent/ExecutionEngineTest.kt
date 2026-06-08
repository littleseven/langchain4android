package com.picme.domain.agent

import com.picme.agent.core.api.command.AgentCommand
import com.picme.agent.core.runtime.execution.ExecutionState
import com.picme.agent.core.api.execution.ExecutionPlan
import com.picme.agent.core.api.execution.PlanStep
import com.picme.agent.core.api.execution.StepResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ExecutionEngine 单元测试
 */
class ExecutionEngineTest {

    private lateinit var fakeDispatcher: FakeCommandDispatcher
    private lateinit var fakeReporter: FakeExecutionReporter
    private lateinit var executionEngine: ExecutionEngine

    @Before
    fun setUp() {
        fakeDispatcher = FakeCommandDispatcher()
        fakeReporter = FakeExecutionReporter()
        executionEngine = ExecutionEngine(
            dispatcher = fakeDispatcher,
            reporter = fakeReporter
        )
    }

    @Test
    fun `execute simple plan with 2 steps all succeed`() = runBlocking {
        val plan = ExecutionPlan(
            planId = "test-plan-1",
            steps = listOf(
                PlanStep(
                    step = 1,
                    action = AgentCommand.CapturePhoto,
                    description = "Take a photo"
                ),
                PlanStep(
                    step = 2,
                    action = AgentCommand.FlipCamera,
                    description = "Flip camera"
                )
            ),
            description = "Test plan with 2 steps"
        )

        val result = executionEngine.execute(plan)

        assertEquals("test-plan-1", result.planId)
        assertEquals(2, result.stepResults.size)
        assertEquals(2, result.successCount)
        assertEquals(0, result.skippedCount)
        assertEquals(0, result.failedCount)
        assertTrue(result.isSuccess)

        val step1 = result.stepResults[0]
        assertTrue(step1 is StepResult.Executed)
        assertTrue((step1 as StepResult.Executed).result.isSuccess)

        val step2 = result.stepResults[1]
        assertTrue(step2 is StepResult.Executed)
        assertTrue((step2 as StepResult.Executed).result.isSuccess)

        // Verify reporter received step results
        assertEquals(2, fakeReporter.stepResults.size)
        assertEquals(1, fakeReporter.reports.size)
        assertEquals("test-plan-1", fakeReporter.reports[0].planId)
    }

    @Test
    fun `skip step when condition is false`() = runBlocking {
        val plan = ExecutionPlan(
            planId = "test-plan-skip",
            steps = listOf(
                PlanStep(
                    step = 1,
                    action = AgentCommand.CapturePhoto,
                    description = "Take a photo"
                ),
                PlanStep(
                    step = 2,
                    action = AgentCommand.FlipCamera,
                    condition = "false",
                    description = "Flip camera (should be skipped)"
                ),
                PlanStep(
                    step = 3,
                    action = AgentCommand.CapturePhoto,
                    description = "Take another photo"
                )
            ),
            description = "Test plan with skipped step"
        )

        val result = executionEngine.execute(plan)

        assertEquals("test-plan-skip", result.planId)
        assertEquals(3, result.stepResults.size)
        assertEquals(2, result.successCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0, result.failedCount)
        assertTrue(result.isSuccess)

        val step1 = result.stepResults[0]
        assertTrue(step1 is StepResult.Executed)

        val step2 = result.stepResults[1]
        assertTrue(step2 is StepResult.Skipped)
        assertEquals("false", (step2 as StepResult.Skipped).step.condition)

        val step3 = result.stepResults[2]
        assertTrue(step3 is StepResult.Executed)
    }

    @Test
    fun `cancel execution mid-way`() = runBlocking {
        val plan = ExecutionPlan(
            planId = "test-plan-cancel",
            steps = listOf(
                PlanStep(
                    step = 1,
                    action = AgentCommand.CapturePhoto,
                    description = "Step 1"
                ),
                PlanStep(
                    step = 2,
                    action = AgentCommand.FlipCamera,
                    delayMs = 500,
                    description = "Step 2 with delay"
                ),
                PlanStep(
                    step = 3,
                    action = AgentCommand.CapturePhoto,
                    description = "Step 3"
                )
            ),
            description = "Test plan with cancellation"
        )

        val job = launch {
            executionEngine.execute(plan)
        }

        // Wait for step 1 to complete
        delay(50)

        // Cancel execution
        executionEngine.cancel()

        job.join()

        val result = fakeReporter.reports.last()
        assertEquals("test-plan-cancel", result.planId)
        // Step 1 should have executed, steps 2 and 3 should not
        assertTrue(result.stepResults.size >= 1)

        // Verify final state is Cancelled
        val finalState = executionEngine.stateFlow.value
        assertTrue(finalState is ExecutionState.Cancelled)
    }

    @Test
    fun `execute step with null condition executes unconditionally`() = runBlocking {
        val plan = ExecutionPlan(
            planId = "test-plan-null-condition",
            steps = listOf(
                PlanStep(
                    step = 1,
                    action = AgentCommand.CapturePhoto,
                    condition = null,
                    description = "Step with null condition"
                )
            ),
            description = "Test null condition"
        )

        val result = executionEngine.execute(plan)

        assertEquals(1, result.successCount)
        assertEquals(0, result.skippedCount)
        assertTrue(result.stepResults[0] is StepResult.Executed)
    }

    @Test
    fun `execute step with true condition executes`() = runBlocking {
        val plan = ExecutionPlan(
            planId = "test-plan-true-condition",
            steps = listOf(
                PlanStep(
                    step = 1,
                    action = AgentCommand.CapturePhoto,
                    condition = "true",
                    description = "Step with true condition"
                )
            ),
            description = "Test true condition"
        )

        val result = executionEngine.execute(plan)

        assertEquals(1, result.successCount)
        assertEquals(0, result.skippedCount)
        assertTrue(result.stepResults[0] is StepResult.Executed)
    }

    @Test
    fun `reset clears state to idle`() = runBlocking {
        val plan = ExecutionPlan(
            planId = "test-plan-reset",
            steps = listOf(
                PlanStep(
                    step = 1,
                    action = AgentCommand.CapturePhoto,
                    description = "Step 1"
                )
            ),
            description = "Test reset"
        )

        executionEngine.execute(plan)
        executionEngine.reset()

        assertTrue(executionEngine.stateFlow.value is ExecutionState.Idle)
    }
}
