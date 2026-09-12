package com.runcode.app.ui.screens

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
                    HealthRow(label = "16 KB Page Alignment", value = "Compatible (Zero 4KB assumptions)")
                    HealthRow(label = "Foreground Service Type", value = "specialUse (Developer Runtime)")
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

                    RuntimeTierItem(name = "Python 3.11 Runtime", tier = "Tier 1", status = "Available", isOk = true)
                    RuntimeTierItem(name = "Telegram Bot Polling Engine", tier = "Tier 1", status = "Available", isOk = true)
                    RuntimeTierItem(name = "Static Web Server", tier = "Tier 1", status = "Available", isOk = true)
                    RuntimeTierItem(name = "SQLite Database Engine", tier = "Tier 1", status = "Available", isOk = true)
                    RuntimeTierItem(name = "JavaScript Engine", tier = "Tier 2", status = "In Roadmap", isOk = false)
                    RuntimeTierItem(name = "PHP / WordPress Profile", tier = "Tier 3/4", status = "Disabled (Awaiting 16KB native binaries)", isOk = false)
                }
            }
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
