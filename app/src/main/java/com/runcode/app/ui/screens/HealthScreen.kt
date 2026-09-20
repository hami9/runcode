package com.runcode.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.AccentRed
import com.runcode.app.ui.theme.DarkBorder
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.DarkSurfaceElevated
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import com.runcode.app.ui.theme.TextSecondary

@Composable
fun HealthScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val caps by viewModel.capabilities.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("System & Runtime Health", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("Device compatibility and execution governance", fontSize = 12.sp, color = TextSecondary)
                }

                IconButton(onClick = { viewModel.refreshCapabilities() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan)
                }
            }
        }

        // Hardware & Platform Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Host Platform Matrix", fontWeight = FontWeight.Bold, color = AccentCyan, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    HealthRow(label = "Android Version", value = "Android ${caps?.releaseVersion ?: "14"} (API ${caps?.androidApi ?: 34})")
                    HealthRow(label = "Supported ABIs", value = caps?.supportedAbis?.joinToString(", ") ?: "arm64-v8a")
                    HealthRow(label = "Foreground Service Type", value = "specialUse (Developer Runtime)")
                    HealthRow(label = "Python Interpreter", value = if (viewModel.isPythonAvailable) "CPython ${viewModel.pythonVersion}" else "Unavailable")
                }
            }
        }

        // Memory & Storage Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Memory & Resource Headroom", fontWeight = FontWeight.Bold, color = AccentGreen, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    val stats by viewModel.processStats.collectAsState()
                    HealthRow(label = "runcode PSS", value = String.format(java.util.Locale.US, "%.1f MB", stats.pssMb))
                    HealthRow(label = "Java heap", value = String.format(java.util.Locale.US, "%.1f MB / %d MB limit", stats.javaHeapMb, stats.heapLimitMb))
                    HealthRow(label = "Threads / PID", value = "${stats.threads} / ${stats.pid}")
                    HealthRow(label = "Available RAM", value = "${caps?.availableMemoryMb ?: 0} MB / ${caps?.totalMemoryMb ?: 0} MB")
                    HealthRow(label = "Low Memory Flag", value = if (caps?.isLowMemory == true) "YES (Warning)" else "Normal")
                    HealthRow(label = "Internal Storage Free", value = "${caps?.freeStorageMb ?: 0} MB")
                    HealthRow(label = "Active WakeLock", value = if (caps?.wakeLockActive == true) "HELD (Service Running)" else "Released")
                }
            }
        }

        // Reliability & Battery Optimization Guide
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Battery Policy & Background Execution", fontWeight = FontWeight.Bold, color = Color(0xFFFFB300), fontSize = 14.sp)
                        val isExempt = caps?.isIgnoringBatteryOptimizations == true
                        Text(
                            text = if (isExempt) "EXEMPT" else "OPTIMIZED",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = if (isExempt) AccentGreen else Color(0xFFFFB300)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Android Doze mode and OEM battery restrictions may terminate long-running processes when the screen is turned off. To maximize service uptime, exempt runcode from battery optimization.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Open Battery Settings", color = AccentCyan)
                    }
                }
            }
        }

        // Last crash, if the app died since it was last opened
        item {
            CrashReportCard(viewModel)
        }

        // MCP Bridge
        item {
            McpBridgeCard(viewModel)
        }

        // Tiered Runtime Status
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Supported Runtimes & Engine Tiers", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    val pythonOk = viewModel.isPythonAvailable
                    RuntimeTierItem(
                        name = "CPython ${viewModel.pythonVersion}",
                        tier = "Embedded interpreter (Chaquopy)",
                        status = if (pythonOk) "Available" else "Unavailable",
                        isOk = pythonOk
                    )
                    RuntimeTierItem(
                        name = "Telegram Bot Profile",
                        tier = "Runs bot.py on CPython — bring your own libraries",
                        status = if (pythonOk) "Available" else "Unavailable",
                        isOk = pythonOk
                    )
                    RuntimeTierItem(
                        name = "Static Web Server",
                        tier = "Built-in HTTP/1.1 file server",
                        status = "Available",
                        isOk = true
                    )
                    RuntimeTierItem(
                        name = "SQLite Database Engine",
                        tier = "Android platform SQLite",
                        status = "Available",
                        isOk = true
                    )
                    RuntimeTierItem(
                        name = "Shell (/system/bin/sh)",
                        tier = "App sandbox, no root, no PTY",
                        status = "Available",
                        isOk = true
                    )
                    RuntimeTierItem(
                        name = "pip / third-party packages",
                        tier = "Requires a build-time requirements list",
                        status = "Not enabled",
                        isOk = false
                    )
                    RuntimeTierItem(
                        name = "JavaScript / PHP runtimes",
                        tier = "Not implemented",
                        status = "Not available",
                        isOk = false
                    )
                }
            }
        }
    }
}

/**
 * Android's crash dialog says only "Something went wrong", and the stack trace lives in
 * logcat where a phone user cannot reach it. Show it here instead.
 */
@Composable
private fun CrashReportCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    val crash by viewModel.lastCrash.collectAsState()
    val report = crash ?: return
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Last crash", fontWeight = FontWeight.Bold, color = AccentRed, fontSize = 14.sp)
            Text(
                text = report.lineSequence().take(6).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = TextSecondary
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Collapse" else "Show full trace", fontSize = 11.sp, color = AccentCyan)
                }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("runcode_crash", report))
                }) {
                    Text("Copy", fontSize = 11.sp, color = AccentCyan)
                }
                TextButton(onClick = { viewModel.dismissCrashReport() }) {
                    Text("Dismiss", fontSize = 11.sp, color = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun McpBridgeCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.mcpState.collectAsState()
    val token by viewModel.mcpToken.collectAsState()
    val allowLan by viewModel.mcpAllowLan.collectAsState()
    var revealToken by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (state.isRunning) AccentGreen.copy(alpha = 0.5f) else DarkBorder
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("MCP Bridge", fontWeight = FontWeight.Bold, color = AccentCyan, fontSize = 14.sp)
                    Text(
                        text = "Let an AI client drive this device over Model Context Protocol",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                Text(
                    text = if (state.isRunning) "ONLINE" else "OFFLINE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = if (state.isRunning) AccentGreen else TextMuted
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val endpoint = if (state.isRunning) {
                if (state.allowLan) "http://${viewModel.mcpLanAddress()}:${state.port}/mcp"
                else "http://127.0.0.1:${state.port}/mcp"
            } else {
                "—"
            }
            HealthRow(label = "Endpoint", value = endpoint)
            HealthRow(label = "Bound to", value = if (state.isRunning) state.boundAddress else "—")
            HealthRow(label = "Requests served", value = state.requestCount.toString())
            state.lastError?.let { HealthRow(label = "Last error", value = it) }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Access token (required on every call)", fontSize = 11.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (revealToken) token else "•".repeat(24),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { revealToken = !revealToken }) {
                    Text(if (revealToken) "Hide" else "Show", fontSize = 11.sp, color = AccentCyan)
                }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("runcode_mcp_token", token))
                }) {
                    Text("Copy", fontSize = 11.sp, color = AccentCyan)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Expose on local network", fontSize = 12.sp, color = TextPrimary)
                    Text(
                        text = if (allowLan) {
                            "Anyone on this Wi-Fi who has the token can run shell commands here."
                        } else {
                            "Loopback only — pair over adb forward or a tunnel."
                        },
                        fontSize = 10.sp,
                        color = if (allowLan) Color(0xFFFFB300) else TextMuted
                    )
                }
                Switch(
                    checked = allowLan,
                    onCheckedChange = { viewModel.setMcpAllowLan(it) },
                    modifier = Modifier.testTag("mcp_lan_switch")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.toggleMcpServer() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRunning) AccentRed.copy(alpha = 0.2f) else AccentGreen
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("mcp_toggle_btn")
                ) {
                    Text(
                        text = if (state.isRunning) "Stop bridge" else "Start bridge",
                        color = if (state.isRunning) AccentRed else Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.regenerateMcpToken() },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("New token", color = AccentCyan)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "12 tools: projects, files, services, logs, shell, Python and SQL. " +
                    "Point your MCP client at the endpoint above with header " +
                    "Authorization: Bearer <token>.",
                fontSize = 10.sp,
                color = TextMuted,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun HealthRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = TextSecondary)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    }
    Divider(color = DarkBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
fun RuntimeTierItem(name: String, tier: String, status: String, isOk: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(tier, fontSize = 11.sp, color = TextMuted)
        }
        Text(
            text = status,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isOk) AccentGreen else TextMuted
        )
    }
    Divider(color = DarkBorder.copy(alpha = 0.5f))
}
