package com.mmd.microuter.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val level: LogLevel
)

enum class LogLevel { INFO, ERROR, WARN, DEBUG }

object AppLogger {
    // Limit history to prevent OutOfMemory errors
    private const val MAX_LOGS = 500
    
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Helper functions
    fun i(tag: String, message: String) = addLog(tag, message, LogLevel.INFO)
    fun e(tag: String, message: String, tr: Throwable? = null) {
        val msg = if (tr != null) "$message\n${tr.stackTraceToString()}" else message
        addLog(tag, msg, LogLevel.ERROR)
    }
    fun w(tag: String, message: String) = addLog(tag, message, LogLevel.WARN)
    fun d(tag: String, message: String) = addLog(tag, message, LogLevel.DEBUG)

    private fun addLog(tag: String, message: String, level: LogLevel) {
        // 1. Log to standard Android Logcat (so you still see it in ADB)
        when (level) {
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.DEBUG -> Log.d(tag, message)
        }

        // 2. Add to In-App State
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            tag = tag,
            message = message,
            level = level
        )

        _logs.update {
            // Add new log at the top, keep list size constant
            (listOf(entry) + it).take(MAX_LOGS)
        }
    }
    
    fun clear() {
        _logs.value = emptyList()
    }
}
