package com.picme.core.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val level: LogLevel,
    val message: String
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

object PicMeLogger {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, message: String) {
        log(LogLevel.DEBUG, tag, message)
        Log.d("PicMe:$tag", message)
    }

    fun i(tag: String, message: String) {
        log(LogLevel.INFO, tag, message)
        Log.i("PicMe:$tag", message)
    }

    fun w(tag: String, message: String) {
        log(LogLevel.WARN, tag, message)
        Log.w("PicMe:$tag", message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, "$message ${throwable?.localizedMessage ?: ""}")
        Log.e("PicMe:$tag", message, throwable)
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            level = level,
            message = message
        )
        val currentList = _logs.value.toMutableList()
        currentList.add(0, entry)
        if (currentList.size > 500) {
            currentList.removeAt(currentList.size - 1)
        }
        _logs.value = currentList
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
