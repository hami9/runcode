package com.runcode.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.domain.models.ServiceState
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.navigation.Screen
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
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigate: (Screen) -> Unit
) {
    val projects by viewModel.projects.collectAsState()
    val instances by viewModel.instances.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()

    val runningCount = instances.values.count { it.isRunning }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF00E5FF).copy(alpha = 0.08f), Color.Transparent)
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (runningCount > 0) AccentGreen else AccentCyan)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "RUNCODE LOCAL RUNTIME",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentCyan
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Developer Environment",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }

                            Button(
                                onClick = { onNavigate(Screen.PROJECTS) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("home_new_project_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Stats Strip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatPill(
                                label = "ACTIVE SERVICES",
                                value = runningCount.toString(),
                                color = if (runningCount > 0) AccentGreen else TextMuted
                            )
                            StatPill(
                                label = "PROJECTS",
                                value = projects.size.toString(),
                                color = AccentCyan
                            )
                            StatPill(
                                label = "FREE STORAGE",
                                value = "${capabilities?.freeStorageMb ?: 0} MB",
                                color = TextPrimary
                            )
                            StatPill(
                                label = "FREE RAM",
                                value = "${capabilities?.availableMemoryMb ?: 0} MB",
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }

        // Quick Launch Templates
        item {
            Text(
                text = "Quick Starter Profiles",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickProfileCard(
                    title = "Python",
                    icon = Icons.Default.Code,
                    color = AccentCyan,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.createProject("Python App", ProjectProfile.PYTHON_SCRIPT)
                    onNavigate(Screen.EDITOR)
                }
                QuickProfileCard(
                    title = "Telegram",
                    icon = Icons.Default.SmartToy,
                    color = AccentGreen,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.createProject("Telegram Bot", ProjectProfile.TELEGRAM_BOT)
                    onNavigate(Screen.EDITOR)
                }
                QuickProfileCard(
                    title = "Static Web",
                    icon = Icons.Default.Web,
                    color = Color(0xFFFFB300),
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.createProject("Web Page", ProjectProfile.STATIC_WEB)
                    onNavigate(Screen.EDITOR)
                }
                QuickProfileCard(
                    title = "SQLite",
                    icon = Icons.Default.Storage,
                    color = Color(0xFFB388FF),
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.createProject("SQLite CRUD", ProjectProfile.SQLITE_APP)
                    onNavigate(Screen.DATABASE)
                }
            }
        }

        // Running Services Spotlight
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Running Services (${runningCount})",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                if (runningCount > 0) {
                    Text(
                        text = "View All →",
                        color = AccentCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { onNavigate(Screen.SERVICES) }
                            .padding(4.dp)
                    )
                }
            }
        }

        if (runningCount == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No active local runtimes", color = TextSecondary, fontWeight = FontWeight.Medium)
                        Text("Launch a project below to start a supervised local service", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(instances.values.filter { it.isRunning }.toList()) { inst ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(AccentGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(inst.projectName, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Port: ${inst.port} • Uptime: ${inst.uptimeSeconds}s • RAM: ${inst.memoryEstimateMb}MB",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }

                        Button(
                            onClick = { viewModel.stopProject(inst.projectId) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("home_stop_svc_${inst.projectId}")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = AccentRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop", color = AccentRed)
                        }
                    }
                }
            }
        }

        // Recent Projects
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Projects",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Browse All →",
                    color = AccentCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onNavigate(Screen.PROJECTS) }
                        .padding(4.dp)
                )
            }
        }

        items(projects.take(4)) { proj ->
            val instance = instances[proj.id]
            val isRunning = instance?.isRunning == true

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.selectProject(proj)
                        onNavigate(Screen.EDITOR)
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(proj.name, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            ProfileBadge(proj.profile)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Entrypoint: ${proj.entryPoint} • Port: ${proj.network.port}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRunning) {
                            OutlinedButton(
                                onClick = { viewModel.stopProject(proj.id) },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Stop", color = AccentRed)
                            }
                        } else {
                            Button(
                                onClick = { viewModel.runProject(proj) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("home_run_${proj.id}")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Run", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatPill(label: String, value: String, color: Color) {
    Column {
        Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted)
        Text(text = value, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun QuickProfileCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = title, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
    }
}

@Composable
fun ProfileBadge(profile: ProjectProfile) {
    val (bgColor, textColor) = when (profile) {
        ProjectProfile.PYTHON_SCRIPT -> Color(0xFF00E5FF).copy(alpha = 0.15f) to Color(0xFF00E5FF)
        ProjectProfile.TELEGRAM_BOT -> Color(0xFF00E676).copy(alpha = 0.15f) to Color(0xFF00E676)
        ProjectProfile.PYTHON_HTTP -> Color(0xFF448AFF).copy(alpha = 0.15f) to Color(0xFF448AFF)
        ProjectProfile.STATIC_WEB -> Color(0xFFFFB300).copy(alpha = 0.15f) to Color(0xFFFFB300)
        ProjectProfile.SQLITE_APP -> Color(0xFFB388FF).copy(alpha = 0.15f) to Color(0xFFB388FF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(profile.displayName, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = textColor)
    }
}
