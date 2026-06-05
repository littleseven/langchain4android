package com.picme.testing.agent.engine

import com.picme.beauty.api.BeautySettings
import com.picme.core.common.Logger
import com.picme.domain.agent.CapabilityRegistry
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.model.MediaType
import com.picme.testing.agent.data.ActionJson
import com.picme.testing.agent.data.DataDrivenTestCase
import com.picme.testing.agent.data.DataDrivenTestResult
import com.picme.testing.agent.data.TestExecutionContext
import com.picme.testing.agent.data.TestStepJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Agent 测试引擎
 *
 * 纯数据驱动，支持动态加载 JSON 测试用例并执行。
 * 直接复用 CapabilityRegistry 分发 AgentCommand，获得结构化执行反馈。
 */
class AgentTestEngine(
    private val registry: CapabilityRegistry,
    private val probe: AgentStateProbe
) {

    companion object {
        private const val TAG = "AgentTestEngine"
    }

    /**
     * 执行数据驱动测试用例
     */
    suspend fun execute(
        case: DataDrivenTestCase,
        agentContext: AgentContext = AgentContext(scene = com.picme.domain.agent.model.AgentScene.CAMERA)
    ): DataDrivenTestResult {
        val startTime = System.currentTimeMillis()
        var context = TestExecutionContext(caseId = case.caseId, caseName = case.name)

        Logger.i(TAG, "[START] ${case.caseId}: ${case.name}")

        case.steps.forEachIndexed { index, step ->
            context = context.copy(currentStep = index)
            Logger.d(TAG, "  Step $index: ${step.description}")

            // 1. 条件判断
            if (step.condition != null) {
                val conditionMet = evaluateCondition(step.condition, probe)
                context = context.addLog("Test", "Condition '${step.condition}' = $conditionMet")
                if (!conditionMet) {
                    Logger.d(TAG, "  Step $index skipped: condition not met")
                    return@forEachIndexed
                }
            }

            // 2. 执行命令序列
            val actions = step.allActions()
            actions.forEach { action ->
                val command = parseToAgentCommand(action)
                Logger.d(TAG, "  Executing: ${action.method}")

                val result = registry.dispatch(command, agentContext)
                context = context.addLog(
                    "Test",
                    "Command ${action.method} result: ${result.isSuccess}"
                )

                // 记录 AgentAction 反馈
                result.getOrNull()?.let { actionResult ->
                    when (actionResult) {
                        is AgentAction.Success -> {
                            context = context.addLog("Test", "Action: Success")
                        }
                        is AgentAction.Error -> {
                            context = context.addLog(
                                "Test",
                                "Action: Error - ${actionResult.message}"
                            )
                        }
                        is AgentAction.TextReply -> {
                            context = context.addLog(
                                "Test",
                                "Action: TextReply - ${actionResult.message}"
                            )
                        }
                        is AgentAction.BatchResult -> {
                            context = context.addLog(
                                "Test",
                                "Action: BatchResult - ${actionResult.results.size} sub-results, success=${actionResult.isSuccess}"
                            )
                        }
                    }
                }

                result.exceptionOrNull()?.let { error ->
                    Logger.e(TAG, "Command ${action.method} failed", error)
                    context = context.addLog("Test", "Exception: ${error.message}")
                }
            }

            // 3. 等待条件
            step.wait?.let { wait ->
                Logger.d(TAG, "  Waiting: ${wait.condition} (timeout: ${wait.timeout}ms)")
                val completed = withTimeoutOrNull(wait.timeout.milliseconds) {
                    while (!evaluateCondition(wait.condition, probe)) {
                        delay(200)
                    }
                    true
                } ?: false

                context = context.addLog("Test", "Wait completed: $completed")
                if (!completed) {
                    val failure = DataDrivenTestResult.Failure(
                        caseId = case.caseId,
                        failedStep = index,
                        stepDescription = step.description,
                        reason = "Wait timeout: ${wait.condition}",
                        context = context
                    )
                    Logger.e(TAG, "[FAIL] ${case.caseId}: Wait timeout at step $index")
                    return failure
                }
            }

            // 4. 断言验证
            step.assertions?.forEach { (key, expected) ->
                val passed = evaluateAssertion(key, expected, probe, context)
                context = context.addLog("Test", "Assertion '$key' = '$expected': $passed")
                if (!passed) {
                    val failure = DataDrivenTestResult.Failure(
                        caseId = case.caseId,
                        failedStep = index,
                        stepDescription = step.description,
                        reason = "Assertion failed: $key expected '$expected'",
                        context = context
                    )
                    Logger.e(TAG, "[FAIL] ${case.caseId}: Assertion failed at step $index")
                    return failure
                }
            }

            // 5. 记录状态快照
            context = context.addStateSnapshot(captureCurrentState(probe))
        }

        val duration = System.currentTimeMillis() - startTime
        Logger.i(TAG, "[PASS] ${case.caseId} in ${duration}ms")
        return DataDrivenTestResult.Success(case.caseId, duration)
    }

    /**
     * 将 ActionJson 解析为 AgentCommand
     */
    private fun parseToAgentCommand(action: ActionJson): AgentCommand {
        return when (action.method.lowercase()) {
            "capture" -> AgentCommand.CapturePhoto()
            "flip_camera" -> AgentCommand.FlipCamera()
            "toggle_recording" -> AgentCommand.ToggleRecording()
            "navigate_to" -> AgentCommand.NavigateTo(
                destination = action.stringParam("destination")
                    ?: throw IllegalArgumentException("navigate_to requires destination")
            )
            "go_back" -> AgentCommand.GoBack()
            "adjust_beauty" -> AgentCommand.AdjustBeauty(
                settings = BeautySettings(
                    enabled = true,
                    smoothing = action.floatParam("smoothing") ?: 0f,
                    whitening = action.floatParam("whitening") ?: 0f,
                    slimFace = action.floatParam("slimFace") ?: 0f,
                    bigEyes = action.floatParam("bigEyes") ?: 0f
                )
            )
            "switch_filter" -> AgentCommand.SwitchFilter(
                filterType = parseFilterType(action.stringParam("filter") ?: "none")
            )
            "switch_style" -> AgentCommand.SwitchStyle(
                styleFilter = parseStyleFilter(action.stringParam("style") ?: "none")
            )
            "switch_scene" -> AgentCommand.SwitchScene(
                sceneName = action.stringParam("scene") ?: "none"
            )
            "switch_ratio" -> AgentCommand.SwitchRatio(
                ratio = action.stringParam("ratio") ?: "4_3"
            )
            "adjust_exposure" -> AgentCommand.AdjustExposure(
                exposure = action.intParam("exposure") ?: 0
            )
            "adjust_zoom" -> AgentCommand.AdjustZoom(
                zoomRatio = action.floatParam("zoom") ?: 1.0f
            )
            "switch_mode" -> AgentCommand.SwitchMode(
                mode = parseMediaType(action.stringParam("mode") ?: "photo")
            )
            "switch_face_engine" -> AgentCommand.SwitchFaceEngine(
                engine = action.stringParam("engine")
                    ?: throw IllegalArgumentException("switch_face_engine requires engine")
            )
            "toggle_setting" -> AgentCommand.ToggleSetting(
                settingKey = action.stringParam("settingKey")
                    ?: throw IllegalArgumentException("toggle_setting requires settingKey"),
                enabled = action.booleanParam("enabled") ?: true
            )
            "change_theme" -> AgentCommand.ChangeTheme(
                theme = action.stringParam("theme") ?: "light"
            )
            "change_language" -> AgentCommand.ChangeLanguage(
                language = action.stringParam("language") ?: "zh"
            )
            "download_model" -> AgentCommand.DownloadModel(
                modelId = action.stringParam("modelId")
                    ?: throw IllegalArgumentException("download_model requires modelId")
            )
            "view_media" -> AgentCommand.ViewMedia(mediaId = action.stringParam("mediaId"))
            "delete_media" -> AgentCommand.DeleteMedia(
                mediaIds = action.stringListParam("mediaIds") ?: emptyList()
            )
            "share_media" -> AgentCommand.ShareMedia(
                mediaIds = action.stringListParam("mediaIds") ?: emptyList()
            )
            "select_media" -> AgentCommand.SelectMedia(
                mediaId = action.stringParam("mediaId")
                    ?: throw IllegalArgumentException("select_media requires mediaId"),
                selected = action.booleanParam("selected") ?: true
            )
            "search_media" -> AgentCommand.SearchMedia(
                query = action.stringParam("query")
                    ?: throw IllegalArgumentException("search_media requires query")
            )
            "favorite_media" -> AgentCommand.FavoriteMedia(
                mediaId = action.stringParam("mediaId")
                    ?: throw IllegalArgumentException("favorite_media requires mediaId"),
                favorite = action.booleanParam("favorite") ?: true
            )
            else -> throw IllegalArgumentException("Unknown method: ${action.method}")
        }
    }

    /**
     * 评估条件表达式
     *
     * 支持简单条件：scene == 'CAMERA', lensFacing == 'back', isProcessing == false
     */
    private fun evaluateCondition(condition: String, probe: AgentStateProbe): Boolean {
        return try {
            val state = probe.captureStateSnapshot()

            // 解析简单条件: key == 'value' 或 key != 'value'
            val regex = "(.+?)\\s*(==|!=)\\s*'(.+?)'".toRegex()
            val match = regex.find(condition.trim())

            if (match != null) {
                val (key, op, expected) = match.destructured
                val actual = when (key.trim()) {
                    "scene" -> state["scene"]
                    "lensFacing" -> state["lensFacing"]
                    "captureMode" -> state["captureMode"]
                    "aspectRatio" -> state["aspectRatio"]
                    "currentFilter" -> state["currentFilter"]
                    "currentScene" -> state["currentScene"]
                    "isProcessing" -> state["isProcessing"]
                    "beautyEnabled" -> state["beautyEnabled"]
                    else -> state[key.trim()]
                }

                return when (op.trim()) {
                    "==" -> actual.toString() == expected
                    "!=" -> actual.toString() != expected
                    else -> false
                }
            }

            // 支持无比较的条件：如 "processing == false"（布尔值）
            val boolRegex = "(.+?)\\s*==\\s*(true|false)".toRegex()
            val boolMatch = boolRegex.find(condition.trim())
            if (boolMatch != null) {
                val (key, expected) = boolMatch.destructured
                val actual = state[key.trim()]
                return actual.toString().toBooleanStrictOrNull() == expected.toBooleanStrictOrNull()
            }

            false
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to evaluate condition: $condition", e)
            false
        }
    }

    /**
     * 评估断言
     */
    private fun evaluateAssertion(
        key: String,
        expected: String,
        probe: AgentStateProbe,
        context: TestExecutionContext
    ): Boolean {
        return try {
            when (key) {
                "scene" -> probe.currentScene().name == expected
                "commandResult" -> {
                    // 检查最后一条命令是否返回 Success
                    val lastLog = context.logs.lastOrNull { it.message.contains("Action: Success") }
                    lastLog != null
                }
                "lensFacing" -> {
                    val state = probe.captureStateSnapshot()
                    state["lensFacing"] == expected
                }
                "beautySmooth" -> {
                    val state = probe.captureStateSnapshot()
                    val actual = (state["beautySmooth"] as? Number)?.toFloat() ?: 0f
                    evaluateNumericAssertion(actual, expected)
                }
                "beautyWhiten" -> {
                    val state = probe.captureStateSnapshot()
                    val actual = (state["beautyWhiten"] as? Number)?.toFloat() ?: 0f
                    evaluateNumericAssertion(actual, expected)
                }
                "gpuProcessTimeMs" -> {
                    val state = probe.captureStateSnapshot()
                    val actual = (state["gpuProcessTimeMs"] as? Number)?.toLong() ?: -1L
                    evaluateNumericAssertion(actual.toFloat(), expected)
                }
                "isProcessing" -> {
                    val state = probe.captureStateSnapshot()
                    state["isProcessing"].toString() == expected
                }
                else -> {
                    val state = probe.captureStateSnapshot()
                    state[key].toString() == expected
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Assertion evaluation failed: $key = $expected", e)
            false
        }
    }

    /**
     * 数值断言评估（支持 >, <, >=, <=, ==）
     */
    private fun evaluateNumericAssertion(actual: Float, expected: String): Boolean {
        val regex = "(>=|<=|>|<|==)\\s*(.+)".toRegex()
        val match = regex.find(expected.trim())

        return if (match != null) {
            val (op, valueStr) = match.destructured
            val expectedValue = valueStr.trim().toFloatOrNull() ?: return false
            when (op) {
                ">=" -> actual >= expectedValue
                "<=" -> actual <= expectedValue
                ">" -> actual > expectedValue
                "<" -> actual < expectedValue
                "==" -> actual == expectedValue
                else -> false
            }
        } else {
            // 纯数值比较
            actual == expected.toFloatOrNull()
        }
    }

    /**
     * 捕获当前状态快照
     */
    private fun captureCurrentState(probe: AgentStateProbe): Map<String, Any?> {
        return try {
            probe.captureStateSnapshot()
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }

    // 辅助解析函数
    private fun parseFilterType(filter: String) = com.picme.beauty.api.FilterType.valueOf(
        filter.uppercase().replace("LEICA_CLASSIC", "LEICA_CLASSIC")
            .replace("LEICA_VIBRANT", "LEICA_VIBRANT")
            .replace("LEICA_BW", "LEICA_BW")
    )

    private fun parseStyleFilter(style: String) = com.picme.beauty.api.StyleFilter.valueOf(
        style.uppercase()
    )

    private fun parseMediaType(mode: String) = when (mode.lowercase()) {
        "photo" -> MediaType.PHOTO
        "video" -> MediaType.VIDEO
        else -> MediaType.PHOTO
    }
}
