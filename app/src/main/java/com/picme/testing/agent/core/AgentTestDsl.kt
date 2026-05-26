package com.picme.testing.agent.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Agent 测试 DSL
 *
 * 提供声明式、自然语言友好的测试用例编写方式。
 *
 * 示例：
 * ```kotlin
 * val testCase = agentTestCase<String>("TC-CAMERA-01", "拍照并验证") {
 *     category(TestCategory.CAMERA)
 *     priority(TestPriority.P0)
 *
 *     step("启动相机预览") {
 *         action { ctx ->
 *             appController.launchCamera()
 *         }
 *         assertState("预览已启动") { state ->
 *             state["isPreviewActive"] == true
 *         }
 *     }
 *
 *     step("触发拍照") {
 *         action { ctx ->
 *             appController.capture()
 *         }
 *         assertLogContains("PhotoProcessor", "process DONE", timeout = 5.seconds)
 *     }
 *
 *     output { ctx ->
 *         ctx.getMetadata("lastPhotoPath") ?: ""
 *     }
 * }
 * ```
 */
fun <T> agentTestCase(
    id: String,
    name: String,
    block: AgentTestCaseBuilder<T>.() -> Unit
): AgentTestCase<T> {
    val builder = AgentTestCaseBuilder<T>(id, name)
    builder.block()
    return builder.build()
}

class AgentTestCaseBuilder<T>(
    private val id: String,
    private val name: String
) {
    private var category: TestCategory = TestCategory.INTEGRATION
    private var priority: TestPriority = TestPriority.P0
    private var requiresDevice: Boolean = true
    private val steps = mutableListOf<TestStep>()
    private var outputProvider: (suspend (AgentTestContext) -> T)? = null

    fun category(value: TestCategory) {
        category = value
    }

    fun priority(value: TestPriority) {
        priority = value
    }

    fun requiresDevice(value: Boolean) {
        requiresDevice = value
    }

    fun step(description: String, block: TestStepBuilder.() -> Unit) {
        val builder = TestStepBuilder(description)
        builder.block()
        steps.add(builder.build())
    }

    fun output(provider: suspend (AgentTestContext) -> T) {
        outputProvider = provider
    }

    fun build(): AgentTestCase<T> = AgentTestCase(
        id = id,
        name = name,
        category = category,
        priority = priority,
        requiresDevice = requiresDevice,
        steps = steps,
        outputProvider = outputProvider
    )
}

class TestStepBuilder(private val description: String) {
    private var action: suspend (AgentTestContext) -> Unit = {}
    private val assertions = mutableListOf<TestAssertion>()
    private var timeout: Duration = 10.seconds
    private var autoScreenshot: Boolean = true

    fun action(block: suspend (AgentTestContext) -> Unit) {
        action = block
    }

    fun assertState(description: String, check: (Map<String, Any?>) -> Boolean) {
        assertions.add(TestAssertion { context ->
            val latestState = context.toSnapshot().stateSnapshots.lastOrNull()
            if (latestState == null) {
                AssertionResult.Failure("No state snapshot available for assertion: $description")
            } else if (check(latestState.state)) {
                AssertionResult.Success
            } else {
                AssertionResult.Failure("State assertion failed: $description")
            }
        })
    }

    fun assertLogContains(tag: String, pattern: String, timeout: Duration = 5.seconds) {
        assertions.add(TestAssertion { context ->
            val found = context.logs.any { log ->
                log.tag.contains(tag, ignoreCase = true) &&
                    log.message.contains(pattern, ignoreCase = true)
            }
            if (found) {
                AssertionResult.Success
            } else {
                AssertionResult.Failure("Log assertion failed: expected [$tag] to contain \"$pattern\"")
            }
        })
    }

    fun assertScreenshot(description: String, check: (ScreenshotRecord) -> Boolean) {
        assertions.add(TestAssertion { context ->
            val latestScreenshot = context.screenshots.lastOrNull()
            if (latestScreenshot == null) {
                AssertionResult.Failure("No screenshot available for assertion: $description")
            } else if (check(latestScreenshot)) {
                AssertionResult.Success
            } else {
                AssertionResult.Failure("Screenshot assertion failed: $description")
            }
        })
    }

    fun customAssertion(description: String, check: (AgentTestContext) -> AssertionResult) {
        assertions.add(TestAssertion { context ->
            check(context)
        })
    }

    fun timeout(duration: Duration) {
        timeout = duration
    }

    fun autoScreenshot(enabled: Boolean) {
        autoScreenshot = enabled
    }

    fun build(): TestStep = TestStep(
        description = description,
        action = action,
        assertions = assertions,
        timeout = timeout,
        autoScreenshot = autoScreenshot
    )
}

/**
 * 便捷断言工厂
 */
object AgentAsserts {

    fun previewIsActive(): TestAssertion = TestAssertion { context ->
        val state = context.toSnapshot().stateSnapshots.lastOrNull()?.state
        val isActive = state?.get("isPreviewActive") as? Boolean
        if (isActive == true) {
            AssertionResult.Success
        } else {
            AssertionResult.Failure("Camera preview is not active")
        }
    }

    fun photoSaved(): TestAssertion = TestAssertion { context ->
        val path = context.getMetadata("lastPhotoPath")
        if (!path.isNullOrBlank()) {
            AssertionResult.Success
        } else {
            AssertionResult.Failure("No photo was saved")
        }
    }

    fun gpuProcessCompleted(maxTimeMs: Long = 1000): TestAssertion = TestAssertion { context ->
        val log = context.logs.find { log ->
            log.tag.contains("PhotoProcessor", ignoreCase = true) &&
                log.message.contains("process DONE", ignoreCase = true)
        }
        if (log == null) {
            AssertionResult.Failure("GPU processing completion log not found")
        } else {
            val timeMs = log.message.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0
            if (timeMs <= maxTimeMs) {
                AssertionResult.Success
            } else {
                AssertionResult.Failure("GPU processing took ${timeMs}ms, exceeds ${maxTimeMs}ms")
            }
        }
    }

    fun beautySettingsApplied(smooth: Int? = null, whiten: Int? = null): TestAssertion =
        TestAssertion { context ->
            val state = context.toSnapshot().stateSnapshots.lastOrNull()?.state
            val actualSmooth = state?.get("beautySmooth") as? Float
            val actualWhiten = state?.get("beautyWhiten") as? Float

            if (smooth != null && actualSmooth != smooth.toFloat()) {
                AssertionResult.Failure("Beauty smooth expected $smooth but was $actualSmooth")
            } else if (whiten != null && actualWhiten != whiten.toFloat()) {
                AssertionResult.Failure("Beauty whiten expected $whiten but was $actualWhiten")
            } else {
                AssertionResult.Success
            }
        }
}
