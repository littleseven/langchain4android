package com.picme.testing.agent.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow


import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Agent 测试框架核心
 *
 * 面向 AI Agent 的声明式测试框架，支持：
 * - 自然语言描述的测试步骤
 * - 自动状态断言与验证
 * - 截屏/日志的自动收集
 * - 失败自动诊断与上下文保留
 */
object AgentTestFramework {

    private const val TAG = "PicMe:AgentTest"

    private val _eventFlow = MutableSharedFlow<AgentTestEvent>(
        extraBufferCapacity = 256
    )
    val eventFlow: SharedFlow<AgentTestEvent> = _eventFlow.asSharedFlow()

    /**
     * 执行一个完整的 Agent 测试用例
     */
    suspend fun <T> runTestCase(
        case: AgentTestCase<T>
    ): AgentTestResult<T> {
        val context = AgentTestContext(case.id, case.name)
        emit(AgentTestEvent.CaseStarted(case.id, case.name))

        try {
            val steps = case.steps
            steps.forEachIndexed { index, step ->
                context.currentStep = index
                emit(AgentTestEvent.StepStarted(case.id, index, step.description))

                val stepResult = executeStep(step, context)

                if (stepResult is StepResult.Failure) {
                    emit(AgentTestEvent.StepFailed(case.id, index, step.description, stepResult.reason))
                    val failureResult = AgentTestResult.Failure<T>(
                        caseId = case.id,
                        failedStep = index,
                        reason = stepResult.reason,
                        context = context.toSnapshot()
                    )
                    emit(AgentTestEvent.CaseFailed(case.id, failureResult))
                    return failureResult
                }

                emit(AgentTestEvent.StepPassed(case.id, index, step.description))
            }

            val output = case.outputProvider?.invoke(context) ?: Unit as T
            val successResult = AgentTestResult.Success(case.id, output, context.toSnapshot())
            emit(AgentTestEvent.CasePassed(case.id, successResult))
            return successResult

        } catch (error: Exception) {
            val failureResult = AgentTestResult.Failure<T>(
                caseId = case.id,
                failedStep = context.currentStep,
                reason = error.message ?: "Unknown error: ${error::class.simpleName}",
                context = context.toSnapshot()
            )
            emit(AgentTestEvent.CaseFailed(case.id, failureResult))
            return failureResult
        }
    }

    private suspend fun executeStep(
        step: TestStep,
        context: AgentTestContext
    ): StepResult {
        return try {
            val result = withTimeoutOrNull(step.timeout) {
                step.action(context)
            }

            if (result == null) {
                return StepResult.Failure("Step timed out after ${step.timeout}")
            }

            // 执行断言
            step.assertions.forEach { assertion ->
                val assertionResult = assertion.check(context)
                if (assertionResult is AssertionResult.Failure) {
                    return StepResult.Failure(assertionResult.message)
                }
            }

            StepResult.Success
        } catch (error: Exception) {
            StepResult.Failure(error.message ?: "Step execution failed: ${error::class.simpleName}")
        }
    }

    private fun emit(event: AgentTestEvent) {
        _eventFlow.tryEmit(event)
    }
}

/**
 * 测试用例定义
 */
data class AgentTestCase<T>(
    val id: String,
    val name: String,
    val category: TestCategory,
    val priority: TestPriority = TestPriority.P0,
    val requiresDevice: Boolean = true,
    val steps: List<TestStep>,
    val outputProvider: (suspend (AgentTestContext) -> T)? = null
)

/**
 * 测试步骤
 */
data class TestStep(
    val description: String,
    val action: suspend (AgentTestContext) -> Unit,
    val assertions: List<TestAssertion> = emptyList(),
    val timeout: Duration = 10.seconds,
    val autoScreenshot: Boolean = true
)

/**
 * 测试断言
 */
fun interface TestAssertion {
    fun check(context: AgentTestContext): AssertionResult
}

/**
 * 断言结果
 */
sealed class AssertionResult {
    data object Success : AssertionResult()
    data class Failure(val message: String) : AssertionResult()
}

/**
 * 步骤执行结果
 */
sealed class StepResult {
    data object Success : StepResult()
    data class Failure(val reason: String) : StepResult()
}

/**
 * 测试用例执行结果
 */
sealed class AgentTestResult<T> {
    abstract val caseId: String
    abstract val context: TestContextSnapshot

    data class Success<T>(
        override val caseId: String,
        val output: T,
        override val context: TestContextSnapshot
    ) : AgentTestResult<T>()

    data class Failure<T>(
        override val caseId: String,
        val failedStep: Int,
        val reason: String,
        override val context: TestContextSnapshot
    ) : AgentTestResult<T>()
}

/**
 * 测试上下文（执行过程中累积状态）
 */
class AgentTestContext(
    val caseId: String,
    val caseName: String
) {
    var currentStep: Int = -1
    private val _screenshots = mutableListOf<ScreenshotRecord>()
    private val _logs = mutableListOf<LogRecord>()
    private val _stateSnapshots = mutableListOf<StateSnapshot>()
    private val _metadata = mutableMapOf<String, String>()

    val screenshots: List<ScreenshotRecord> get() = _screenshots.toList()
    val logs: List<LogRecord> get() = _logs.toList()

    fun addScreenshot(name: String, path: String) {
        _screenshots.add(ScreenshotRecord(name, path, System.currentTimeMillis()))
    }

    fun addLog(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        _logs.add(LogRecord(tag, message, level, System.currentTimeMillis()))
    }

    fun addStateSnapshot(state: Map<String, Any?>) {
        _stateSnapshots.add(StateSnapshot(System.currentTimeMillis(), state))
    }

    fun setMetadata(key: String, value: String) {
        _metadata[key] = value
    }

    fun getMetadata(key: String): String? = _metadata[key]

    fun toSnapshot(): TestContextSnapshot = TestContextSnapshot(
        caseId = caseId,
        caseName = caseName,
        currentStep = currentStep,
        screenshots = _screenshots.toList(),
        logs = _logs.toList(),
        stateSnapshots = _stateSnapshots.toList(),
        metadata = _metadata.toMap()
    )
}

/**
 * 上下文快照（用于失败诊断）
 */
data class TestContextSnapshot(
    val caseId: String,
    val caseName: String,
    val currentStep: Int,
    val screenshots: List<ScreenshotRecord>,
    val logs: List<LogRecord>,
    val stateSnapshots: List<StateSnapshot>,
    val metadata: Map<String, String>
)

data class ScreenshotRecord(val name: String, val path: String, val timestamp: Long)
data class LogRecord(val tag: String, val message: String, val level: LogLevel, val timestamp: Long)
data class StateSnapshot(val timestamp: Long, val state: Map<String, Any?>)

enum class LogLevel { DEBUG, INFO, WARN, ERROR }
enum class TestCategory { CAMERA, GALLERY, BEAUTY, SETTINGS, PERFORMANCE, INTEGRATION }
enum class TestPriority { P0, P1, P2 }

/**
 * 测试事件流（用于 UI 展示或日志记录）
 */
sealed class AgentTestEvent {
    abstract val caseId: String

    data class CaseStarted(override val caseId: String, val caseName: String) : AgentTestEvent()
    data class CasePassed(override val caseId: String, val result: AgentTestResult.Success<*>) : AgentTestEvent()
    data class CaseFailed(override val caseId: String, val result: AgentTestResult.Failure<*>) : AgentTestEvent()
    data class StepStarted(override val caseId: String, val stepIndex: Int, val description: String) : AgentTestEvent()
    data class StepPassed(override val caseId: String, val stepIndex: Int, val description: String) : AgentTestEvent()
    data class StepFailed(override val caseId: String, val stepIndex: Int, val description: String, val reason: String) : AgentTestEvent()
}
