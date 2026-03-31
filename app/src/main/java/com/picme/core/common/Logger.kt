package com.picme.core.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志条目数据模型
 *
 * @property timestamp 时间戳（格式：HH:mm:ss.SSS）
 * @property tag 日志标签（自动添加 PicMe: 前缀）
 * @property level 日志级别
 * @property message 日志内容
 */
data class LogEntry(
    val timestamp: String,
    val tag: String,
    val level: LogLevel,
    val message: String
)

/**
 * 日志级别枚举
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * PicMe 统一日志系统
 *
 * 职责：
 * 1. 统一日志格式（PicMe:[ModuleName]）
 * 2. 缓存日志供调试界面展示（最多 500 条）
 * 3. 封装 Android Log API
 *
 * 使用方式：
 * ```kotlin
 * Logger.d("Camera", "Preview started")
 * Logger.i("Storage", "Saved image: $path")
 * Logger.w("AI", "Face detection timeout")
 * Logger.e("Network", "Connection failed", exception)
 * ```
 *
 * 规范：
 * - 标签使用模块名（Camera、Gallery、AI 等）
 * - 禁止直接使用 android.util.Log
 * - 关键状态流转必须记录日志
 */
object Logger {
    private const val TAG_PREFIX = "PicMe:"
    private const val MAX_LOG_ENTRIES = 500

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Debug 级别日志
     *
     * @param tag 模块标签（不含 PicMe: 前缀）
     * @param message 日志内容
     */
    fun d(tag: String, message: String) {
        logToMemory(LogLevel.DEBUG, tag, message)
        Log.d("$TAG_PREFIX$tag", message)
    }

    /**
     * Info 级别日志
     *
     * @param tag 模块标签（不含 PicMe: 前缀）
     * @param message 日志内容
     */
    fun i(tag: String, message: String) {
        logToMemory(LogLevel.INFO, tag, message)
        Log.i("$TAG_PREFIX$tag", message)
    }

    /**
     * Warning 级别日志
     *
     * @param tag 模块标签（不含 PicMe: 前缀）
     * @param message 日志内容
     */
    fun w(tag: String, message: String) {
        logToMemory(LogLevel.WARN, tag, message)
        Log.w("$TAG_PREFIX$tag", message)
    }

    /**
     * Warning 级别日志（带异常）
     *
     * @param tag 模块标签（不含 PicMe: 前缀）
     * @param message 日志内容
     * @param throwable 异常对象
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        val fullMessage = "$message: ${throwable.localizedMessage}"
        logToMemory(LogLevel.WARN, tag, fullMessage)
        Log.w("$TAG_PREFIX$tag", message, throwable)
    }

    /**
     * Error 级别日志
     *
     * @param tag 模块标签（不含 PicMe: 前缀）
     * @param message 日志内容
     * @param throwable 异常对象（可选）
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.localizedMessage}"
        } else {
            message
        }
        logToMemory(LogLevel.ERROR, tag, fullMessage)

        if (throwable != null) {
            Log.e("$TAG_PREFIX$tag", message, throwable)
        } else {
            Log.e("$TAG_PREFIX$tag", message)
        }
    }

    /**
     * 记录日志到内存缓存
     */
    private fun logToMemory(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            level = level,
            message = message
        )

        val currentList = _logs.value.toMutableList()
        currentList.add(0, entry)

        // 维护最大条目数限制
        if (currentList.size > MAX_LOG_ENTRIES) {
            currentList.removeAt(currentList.size - 1)
        }

        _logs.value = currentList
    }

    /**
     * 清空内存中的日志缓存
     */
    fun clear() {
        _logs.value = emptyList()
    }
}

/**
 * 向后兼容的别名
 * 逐步迁移到 Logger 后可移除
 */
@Deprecated(
    message = "Use Logger instead",
    replaceWith = ReplaceWith("Logger", "com.picme.core.common.Logger"),
    level = DeprecationLevel.WARNING
)
typealias PicMeLogger = Logger
