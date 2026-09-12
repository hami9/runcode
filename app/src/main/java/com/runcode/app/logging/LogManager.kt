package com.runcode.app.logging

import android.content.Context
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.RuntimeEvent
import com.runcode.app.security.SecretRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

class LogManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val maxMemoryEntries = 1500
    private val buffer = ConcurrentLinkedDeque<RuntimeEvent>()
    private val _eventsFlow = MutableStateFlow<List<RuntimeEvent>>(emptyList())
    val eventsFlow: StateFlow<List<RuntimeEvent>> = _eventsFlow.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(projectId: String, serviceName: String, level: LogLevel, rawMessage: String) {
        val sanitized = SecretRedactor.redact(rawMessage)
        val event = RuntimeEvent(
            id = System.nanoTime(),
            timestamp = System.currentTimeMillis(),
            projectId = projectId,
            serviceName = serviceName,
            level = level,
            message = sanitized
        )

        buffer.addLast(event)
        while (buffer.size > maxMemoryEntries) {
            buffer.pollFirst()
        }
        _eventsFlow.value = buffer.toList()

        // Append to project log file asynchronously
        scope.launch {
            try {
                val logDir = File(context.filesDir, "projects/$projectId/logs")
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "runtime.log")

                // Rotate if > 2MB
                if (logFile.exists() && logFile.length() > 2 * 1024 * 1024) {
                    val backupFile = File(logDir, "runtime.log.1")
                    if (backupFile.exists()) backupFile.delete()
                    logFile.renameTo(backupFile)
                }

                FileWriter(logFile, true).use { writer ->
                    val timeStr = timeFormat.format(Date(event.timestamp))
                    writer.write("[$timeStr] [${event.level.name}] [${event.serviceName}] ${event.message}\n")
                }
            } catch (_: Exception) {
                // Ignore disk log failure to protect runtime execution
            }
        }
    }

    fun getLogsForProject(projectId: String): List<RuntimeEvent> {
        return buffer.filter { it.projectId == projectId }
    }

    fun clear() {
        buffer.clear()
        _eventsFlow.value = emptyList()
    }

    fun clearProject(projectId: String) {
        buffer.removeIf { it.projectId == projectId }
        _eventsFlow.value = buffer.toList()
    }

    fun exportLogs(projectId: String? = null): String {
        val list = if (projectId.isNullOrEmpty()) buffer.toList() else buffer.filter { it.projectId == projectId }
        return buildString {
            list.forEach { event ->
                val timeStr = timeFormat.format(Date(event.timestamp))
                append("[$timeStr] [${event.level}] [${event.serviceName}] ${event.message}\n")
            }
        }
    }
}
