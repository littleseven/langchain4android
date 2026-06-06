package com.picme.testing.agent.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 数据驱动测试用例模型
 *
 * 纯 JSON/YAML 驱动的测试用例定义，支持动态加载。
 */
@JsonClass(generateAdapter = true)
data class DataDrivenTestCase(
    @Json(name = "caseId")
    val caseId: String,

    @Json(name = "name")
    val name: String,

    @Json(name = "category")
    val category: String = "INTEGRATION",

    @Json(name = "priority")
    val priority: String = "P0",

    @Json(name = "steps")
    val steps: List<TestStepJson>
)

/**
 * 测试步骤 JSON 模型
 */
@JsonClass(generateAdapter = true)
data class TestStepJson(
    @Json(name = "description")
    val description: String,

    @Json(name = "if")
    val condition: String? = null,

    @Json(name = "then")
    val actions: List<ActionJson>? = null,

    @Json(name = "action")
    val singleAction: ActionJson? = null,

    @Json(name = "wait")
    val wait: WaitJson? = null,

    @Json(name = "assert")
    val assertions: Map<String, String>? = null,

    @Json(name = "screenshot")
    val screenshot: String? = null,

    @Json(name = "collect")
    val collect: List<String>? = null,

    @Json(name = "delay")
    val delayMs: Long? = null
) {
    /**
     * 获取所有动作（兼容单 action 和 then 数组）
     */
    fun allActions(): List<ActionJson> {
        val list = mutableListOf<ActionJson>()
        singleAction?.let { list.add(it) }
        actions?.let { list.addAll(it) }
        return list
    }
}

/**
 * 动作 JSON 模型
 *
 * 新协议格式: {"method": "xxx", "params": {...}}
 * 所有参数统一放在 params 对象中。
 */
@JsonClass(generateAdapter = true)
data class ActionJson(
    @Json(name = "method")
    val method: String,

    @Json(name = "params")
    val params: Map<String, Any?>? = null
) {
    /**
     * 从 params 中安全获取 String 参数
     */
    fun stringParam(key: String): String? = params?.get(key) as? String

    /**
     * 从 params 中安全获取 Float 参数
     */
    fun floatParam(key: String): Float? = (params?.get(key) as? Number)?.toFloat()

    /**
     * 从 params 中安全获取 Int 参数
     */
    fun intParam(key: String): Int? = (params?.get(key) as? Number)?.toInt()

    /**
     * 从 params 中安全获取 Boolean 参数
     */
    fun booleanParam(key: String): Boolean? = params?.get(key) as? Boolean

    /**
     * 从 params 中安全获取 String 列表参数
     */
    fun stringListParam(key: String): List<String>? = params?.get(key) as? List<String>
}

/**
 * 等待条件 JSON 模型
 */
@JsonClass(generateAdapter = true)
data class WaitJson(
    @Json(name = "condition")
    val condition: String,

    @Json(name = "timeout")
    val timeout: Long = 5000
)

/**
 * 性能指标快照
 */
data class PerformanceSnapshot(
    val timestamp: Long,
    val fps: Map<String, Any>? = null,
    val memory: Map<String, Any>? = null
)

/**
 * 数据驱动测试结果
 */
sealed class DataDrivenTestResult {
    abstract val caseId: String

    data class Success(
        override val caseId: String,
        val durationMs: Long
    ) : DataDrivenTestResult()

    data class Failure(
        override val caseId: String,
        val failedStep: Int,
        val stepDescription: String,
        val reason: String,
        val context: TestExecutionContext
    ) : DataDrivenTestResult()
}

/**
 * 测试执行上下文（轻量级，用于失败诊断）
 */
data class TestExecutionContext(
    val caseId: String,
    val caseName: String,
    val currentStep: Int = -1,
    val logs: List<TestLogEntry> = emptyList(),
    val stateSnapshots: List<TestStateSnapshot> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val screenshots: List<ScreenshotRecord> = emptyList(),
    val performanceSnapshots: List<PerformanceSnapshot> = emptyList()
) {
    fun addLog(tag: String, message: String): TestExecutionContext {
        return copy(
            logs = logs + TestLogEntry(tag, message, System.currentTimeMillis())
        )
    }

    fun addStateSnapshot(state: Map<String, Any?>): TestExecutionContext {
        return copy(
            stateSnapshots = stateSnapshots + TestStateSnapshot(System.currentTimeMillis(), state)
        )
    }

    fun setMetadata(key: String, value: String): TestExecutionContext {
        return copy(metadata = metadata + (key to value))
    }

    fun addScreenshot(name: String, path: String): TestExecutionContext {
        return copy(
            screenshots = screenshots + ScreenshotRecord(name, path, System.currentTimeMillis())
        )
    }

    fun addPerformanceSnapshot(snapshot: PerformanceSnapshot): TestExecutionContext {
        return copy(
            performanceSnapshots = performanceSnapshots + snapshot
        )
    }
}

data class TestLogEntry(val tag: String, val message: String, val timestamp: Long)
data class TestStateSnapshot(val timestamp: Long, val state: Map<String, Any?>)
data class ScreenshotRecord(val name: String, val path: String, val timestamp: Long)
