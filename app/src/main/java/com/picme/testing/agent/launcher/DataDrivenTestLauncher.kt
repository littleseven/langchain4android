package com.picme.testing.agent.launcher

import android.content.Context
import com.picme.core.common.Logger
import com.picme.testing.agent.data.DataDrivenTestResult
import com.picme.testing.agent.runner.DataDrivenTestRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * 数据驱动测试启动器
 *
 * 从 MainActivity 或其他入口独立启动数据驱动测试，
 * 负责测试生命周期管理、状态监听和报告保存。
 */
class DataDrivenTestLauncher(private val context: Context) {

    companion object {
        private const val TAG = "DataDrivenTestLauncher"
        private const val REPORT_DIR = "PicMe_Agent_Test/reports"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runner: DataDrivenTestRunner? = null

    /**
     * 启动数据驱动测试
     *
     * @param testPath 测试用例路径（单个 JSON 文件或套件目录）
     * @param onComplete 测试完成回调（可选）
     */
    fun launch(testPath: String, onComplete: ((Boolean) -> Unit)? = null) {
        Logger.i(TAG, "Launching data-driven test: $testPath")

        runner = DataDrivenTestRunner(context)

        // 监听测试状态并输出结构化日志
        scope.launch {
            runner!!.state.collectLatest { state ->
                when (state) {
                    is DataDrivenTestRunner.RunnerState.Running -> {
                        Logger.i("DataDrivenTestRunner", "Running: ${state.caseId}")
                    }
                    is DataDrivenTestRunner.RunnerState.RunningSuite -> {
                        Logger.i("DataDrivenTestRunner", "Running suite: ${state.suiteName}, cases: ${state.totalCases}")
                    }
                    is DataDrivenTestRunner.RunnerState.Completed -> {
                        val result = state.result
                        when (result) {
                            is DataDrivenTestResult.Success -> {
                                Logger.i("DataDrivenTestRunner", "Case passed: ${result.caseId} in ${result.durationMs}ms")
                            }
                            is DataDrivenTestResult.Failure -> {
                                Logger.e("DataDrivenTestRunner", "Case failed: ${result.caseId} at step '${result.stepDescription}': ${result.reason}")
                            }
                        }
                    }
                    is DataDrivenTestRunner.RunnerState.SuiteCompleted -> {
                        val report = state.report
                        Logger.i("DataDrivenTestRunner", "SuiteCompleted: ${report.suiteName} - ${report.passedCount}/${report.totalCases} passed (${report.passRate * 100}%)")
                        saveReport(report)
                        onComplete?.invoke(report.failedCount == 0)
                    }
                    is DataDrivenTestRunner.RunnerState.Error -> {
                        Logger.e("DataDrivenTestRunner", "Error: ${state.message}")
                        onComplete?.invoke(false)
                    }
                    else -> {}
                }
            }
        }

        // 判断是单个用例还是套件并执行
        if (testPath.endsWith(".json")) {
            runner!!.runCase(testPath)
        } else {
            runner!!.runSuite(testPath)
        }
    }

    /**
     * 保存测试报告到外部存储
     */
    private fun saveReport(report: DataDrivenTestRunner.SuiteReport) {
        try {
            val dir = File(context.getExternalFilesDir(null), REPORT_DIR)
            dir.mkdirs()
            val file = File(dir, "${report.suiteName}_report_${System.currentTimeMillis()}.json")
            val json = buildString {
                appendLine("{")
                appendLine("    \"suiteName\": \"${report.suiteName}\",")
                appendLine("    \"totalCases\": ${report.totalCases},")
                appendLine("    \"passedCount\": ${report.passedCount},")
                appendLine("    \"failedCount\": ${report.failedCount},")
                appendLine("    \"passRate\": ${report.passRate},")
                appendLine("    \"timestamp\": ${System.currentTimeMillis()}")
                appendLine("}")
            }
            file.writeText(json)
            Logger.i("DataDrivenTestRunner", "Report saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Logger.e("DataDrivenTestRunner", "Failed to save report", e)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        runner?.release()
        scope.cancel()
    }
}
