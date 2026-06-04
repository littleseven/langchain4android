package com.picme.testing.agent.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.picme.core.common.Logger
import com.picme.testing.agent.cases.BeautyAgentTestCases
import com.picme.testing.agent.cases.CameraAgentTestCases
import com.picme.testing.agent.device.DeviceTestController
import com.picme.testing.agent.runner.AgentTestRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.picme.testing.agent.core.AgentTestCase
import com.picme.testing.agent.core.AgentTestResult

/**
 * Agent 测试广播接收器
 *
 * 接收来自 AI Agent 的测试命令，通过广播触发设备端测试执行。
 *
 * ## 命令格式
 *
 * ```bash
 * # 运行相机模块全部测试
 * adb shell am broadcast -a com.picme.AGENT_TEST --es suite "camera"
 *
 * # 运行美颜模块全部测试
 * adb shell am broadcast -a com.picme.AGENT_TEST --es suite "beauty"
 *
 * # 运行 P0 回归测试
 * adb shell am broadcast -a com.picme.AGENT_TEST --es suite "p0"
 *
 * # 运行单个测试用例
 * adb shell am broadcast -a com.picme.AGENT_TEST --es case "TC-CAMERA-01"
 *
 * # 获取测试状态
 * adb shell am broadcast -a com.picme.AGENT_TEST --es action "status"
 *
 * # 导出报告
 * adb shell am broadcast -a com.picme.AGENT_TEST --es action "export_report"
 * ```
 *
 * ## 响应格式
 *
 * 测试结果通过广播发送回 AI Agent：
 * ```json
 * {
 *   "type": "test_result",
 *   "caseId": "TC-CAMERA-01",
 *   "status": "passed",
 *   "durationMs": 5234,
 *   "screenshots": ["/sdcard/..."]
 * }
 * ```
 */
class AgentTestBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AgentTestReceiver"
        const val ACTION_AGENT_TEST = "com.picme.AGENT_TEST"

        // 命令字段
        private const val EXTRA_SUITE = "suite"
        private const val EXTRA_CASE = "case"
        private const val EXTRA_ACTION = "action"

        // 响应 Action
        const val ACTION_TEST_RESPONSE = "com.picme.AGENT_TEST_RESPONSE"
        const val EXTRA_RESPONSE_JSON = "response_json"

        // 单例 Runner
        private var runner: AgentTestRunner? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun initialize(context: Context) {
            if (runner == null) {
                runner = AgentTestRunner(context.applicationContext)
            }
        }

        fun release() {
            runner?.release()
            runner = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AGENT_TEST) {
            Logger.w(TAG, "Unexpected action: ${intent.action}")
            return
        }

        initialize(context)
        val runner = runner ?: return
        val controller = DeviceTestController(context)

        when {
            intent.hasExtra(EXTRA_SUITE) -> handleSuiteCommand(intent, runner, context)
            intent.hasExtra(EXTRA_CASE) -> handleCaseCommand(intent, runner, controller, context)
            intent.hasExtra(EXTRA_ACTION) -> handleActionCommand(intent, runner, context)
            else -> {
                Logger.w(TAG, "No valid command found in intent")
                sendResponse(context, createErrorResponse("No valid command"))
            }
        }
    }

    private fun handleSuiteCommand(intent: Intent, runner: AgentTestRunner, context: Context) {
        val suite = intent.getStringExtra(EXTRA_SUITE) ?: return
        Logger.i(TAG, "Running suite: $suite")

        scope.launch {
            when (suite.lowercase()) {
                "camera" -> {
                    runner.runCameraTests { report ->
                        sendResponse(context, report.toJson().toString())
                    }
                }
                "beauty" -> {
                    runner.runBeautyTests { report ->
                        sendResponse(context, report.toJson().toString())
                    }
                }
                "p0", "regression" -> {
                    runner.runP0Regression { report ->
                        sendResponse(context, report.toJson().toString())
                    }
                }
                else -> {
                    sendResponse(context, createErrorResponse("Unknown suite: $suite"))
                }
            }
        }
    }

    private fun handleCaseCommand(
        intent: Intent,
        runner: AgentTestRunner,
        controller: DeviceTestController,
        context: Context
    ) {
        val caseId = intent.getStringExtra(EXTRA_CASE) ?: return
        Logger.i(TAG, "Running case: $caseId")

        scope.launch {
            val case = findCaseById(caseId, controller)
            if (case != null) {
                runner.runCase(case) { result ->
                    val json = when (result) {
                        is AgentTestResult.Success -> {
                            JSONObject().apply {
                                put("type", "test_result")
                                put("caseId", caseId)
                                put("status", "passed")
                                put("context", result.context.toString())
                            }.toString()
                        }
                        is AgentTestResult.Failure -> {
                            JSONObject().apply {
                                put("type", "test_result")
                                put("caseId", caseId)
                                put("status", "failed")
                                put("failedStep", result.failedStep)
                                put("reason", result.reason)
                                put("context", result.context.toString())
                            }.toString()
                        }
                    }
                    sendResponse(context, json)
                }
            } else {
                sendResponse(context, createErrorResponse("Case not found: $caseId"))
            }
        }
    }

    private fun handleActionCommand(intent: Intent, runner: AgentTestRunner, context: Context) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        Logger.i(TAG, "Handling action: $action")

        when (action.lowercase()) {
            "status" -> {
                val state = runner.state.value
                val progress = runner.progress.value
                val response = JSONObject().apply {
                    put("type", "status")
                    put("state", state::class.simpleName)
                    put("currentIndex", progress.currentIndex)
                    put("totalCases", progress.totalCases)
                }.toString()
                sendResponse(context, response)
            }
            "export_report" -> {
                val path = runner.exportJsonReport()
                val mdReport = runner.generateMarkdownReport()
                val response = JSONObject().apply {
                    put("type", "report")
                    put("jsonPath", path)
                    put("markdown", mdReport)
                }.toString()
                sendResponse(context, response)
            }
            else -> {
                sendResponse(context, createErrorResponse("Unknown action: $action"))
            }
        }
    }

    private fun findCaseById(caseId: String, controller: DeviceTestController): AgentTestCase<*>? {
        val allCases = mutableListOf<AgentTestCase<*>>()
        allCases.addAll(CameraAgentTestCases.allCases(controller))
        allCases.addAll(BeautyAgentTestCases.allCases(controller))
        return allCases.find { it.id == caseId }
    }

    private fun sendResponse(context: Context, json: String) {
        try {
            val responseIntent = Intent(ACTION_TEST_RESPONSE).apply {
                putExtra(EXTRA_RESPONSE_JSON, json)
                `package` = context.packageName
            }
            context.sendBroadcast(responseIntent)
            Logger.i(TAG, "Response sent: ${json.take(200)}...")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to send response", e)
        }
    }

    private fun createErrorResponse(message: String): String {
        return JSONObject().apply {
            put("type", "error")
            put("message", message)
        }.toString()
    }
}
