package com.runcode.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.RuntimeInstance
import com.runcode.app.domain.models.ServiceState
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.AccentRed
import com.runcode.app.ui.theme.DarkBorder
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.DarkSurfaceElevated
import com.runcode.app.ui.theme.TerminalBg
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import com.runcode.app.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ServicesScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val instances by viewModel.instances.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val projects by viewModel.projects.collectAsState()

    var selectedLevelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var logSearch by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()

    val filteredLogs = logs.filter { event ->
        (selectedLevelFilter == null || event.level == selectedLevelFilter) &&
                (logSearch.isBlank() || event.message.contains(logSearch, ignoreCase = true) || event.serviceName.contains(logSearch, ignoreCase = true))
    }

    LaunchedEffect(filteredLogs.size) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Service Supervisor", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text("Local process runtime, health & log stream", fontSize = 12.sp, color = TextSecondary)
            }

            if (instances.values.any { it.isRunning }) {
                OutlinedButton(
                    onClick = {
                        instances.values.filter { it.isRunning }.forEach { inst ->
                            viewModel.stopProject(inst.projectId)
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Stop All", color = AccentRed)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Active Instances Cards Strip
        if (instances.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(instances.values.toList()) { inst ->
                    InstanceSupervisorCard(
                        instance = inst,
                        onStop = { viewModel.stopProject(inst.projectId) },
                        onRestart = {
                            projects.find { it.id == inst.projectId }?.let { p ->
                                viewModel.restartProject(p)
                            }
                        }
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.25f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No services configured yet. Start a project to observe it here.", color = TextMuted, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Terminal Log Viewer
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Log Bar Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Terminal Logs", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("(${filteredLogs.size})", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { autoScroll = !autoScroll }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                if (autoScroll) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Auto-scroll toggle",
                                tint = if (autoScroll) AccentCyan else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                val text = filteredLogs.joinToString("\n") { "[${it.level}] ${it.serviceName}: ${it.message}" }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("runcode_logs", text))
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { viewModel.clearLogs() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Filters Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurfaceElevated)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedLevelFilter == null,
                        onClick = { selectedLevelFilter = null },
                        label = { Text("ALL", fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentCyan.copy(alpha = 0.2f))
                    )
                    FilterChip(
                        selected = selectedLevelFilter == LogLevel.STDOUT,
                        onClick = { selectedLevelFilter = LogLevel.STDOUT },
                        label = { Text("STDOUT", fontSize = 10.sp) }
                    )
                    FilterChip(
                        selected = selectedLevelFilter == LogLevel.STDERR,
                        onClick = { selectedLevelFilter = LogLevel.STDERR },
                        label = { Text("STDERR", fontSize = 10.sp) }
                    )
                    FilterChip(
                        selected = selectedLevelFilter == LogLevel.INFO,
                        onClick = { selectedLevelFilter = LogLevel.INFO },
                        label = { Text("INFO", fontSize = 10.sp) }
                    )
                    FilterChip(
                        selected = selectedLevelFilter == LogLevel.ERROR,
                        onClick = { selectedLevelFilter = LogLevel.ERROR },
                        label = { Text("ERROR", fontSize = 10.sp) }
                    )
                }

                // Log Lines Stream
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(filteredLogs) { event ->
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(event.timestamp))
                        val levelColor = when (event.level) {
                            LogLevel.ERROR, LogLevel.STDERR -> AccentRed
                            LogLevel.WARN -> Color(0xFFFFB300)
                            LogLevel.STDOUT -> AccentGreen
                            LogLevel.SYSTEM -> AccentCyan
                            LogLevel.INFO -> TextPrimary
                        }

                        Row(modifier = Modifier.padding(vertical = 1.dp)) {
                            Text(
                                text = "$timeStr ",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "[${event.level.name.take(3)}] ",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = levelColor
                            )
                            Text(
                                text = "${event.serviceName}: ",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = AccentCyan.copy(alpha = 0.8f)
                            )
                            Text(
                                text = event.message,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InstanceSupervisorCard(
    instance: RuntimeInstance,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    val stateColor = when (instance.state) {
        ServiceState.RUNNING -> AccentGreen
        ServiceState.PREPARING, ServiceState.STARTING, ServiceState.RESTARTING -> Color(0xFFFFB300)
        ServiceState.FAILED -> AccentRed
        ServiceState.STOPPED, ServiceState.STOPPING, ServiceState.DEGRADED -> TextMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(instance.projectName, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "[${instance.state.name}]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = stateColor
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Port: ${instance.port} • Uptime: ${instance.uptimeSeconds}s • RAM: ${instance.memoryEstimateMb}MB • Restarts: ${instance.restartCount}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                if (instance.lastError != null) {
                    Text(
                        text = "Error: ${instance.lastError}",
                        fontSize = 11.sp,
                        color = AccentRed
                    )
                }
            }

            Row {
                IconButton(onClick = onRestart, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = AccentCyan, modifier = Modifier.size(18.dp))
                }
                if (instance.isRunning) {
                    IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = AccentRed, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
