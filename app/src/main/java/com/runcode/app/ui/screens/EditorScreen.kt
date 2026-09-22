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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.AccentRed
import com.runcode.app.ui.theme.DarkBg
import com.runcode.app.ui.theme.DarkBorder
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.DarkSurfaceElevated
import com.runcode.app.ui.theme.TerminalBg
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import com.runcode.app.ui.theme.TextSecondary
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    viewModel: MainViewModel
) {
    val project by viewModel.selectedProject.collectAsState()
    val openTabs by viewModel.openTabs.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val isDirty by viewModel.isDirty.collectAsState()
    val projectFiles by viewModel.projectFiles.collectAsState()
    val instances by viewModel.instances.collectAsState()
    val logs by viewModel.logs.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showBottomConsole by remember { mutableStateOf(false) }

    val isRunning = project?.let { instances[it.id]?.isRunning == true } ?: false

    if (project == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No project selected. Select a project in the Projects tab.", color = TextSecondary)
        }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = DarkSurfaceElevated,
                modifier = Modifier.width(320.dp)
            ) {
                project?.let { current ->
                    FilePane(
                        viewModel = viewModel,
                        project = current,
                        files = projectFiles,
                        activeTab = activeTab,
                        onOpenFile = { rel ->
                            viewModel.openFile(current.id, rel)
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
        ) {
            // Editor Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Takes whatever the buttons leave, so a long project name is shortened instead
                // of pushing the Run button off the screen.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "File Tree", tint = AccentCyan)
                    }
                    Text(
                        text = project?.name ?: "Editor",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDirty) {
                        Text(" *", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.undo() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.redo() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.saveCurrentFile() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = if (isDirty) AccentCyan else TextMuted, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { showBottomConsole = !showBottomConsole }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Terminal, contentDescription = "Console", tint = if (showBottomConsole) AccentCyan else TextSecondary, modifier = Modifier.size(20.dp))
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    project?.let { currentProj ->
                        if (isRunning) {
                            Button(
                                onClick = { viewModel.stopProject(currentProj.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRed.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("editor_stop_btn")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = AccentRed, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop", color = AccentRed)
                            }
                        } else {
                            Button(
                                onClick = {
                                    viewModel.runProject(currentProj)
                                    showBottomConsole = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("editor_run_btn")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Run", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Tabs Header
            if (openTabs.isNotEmpty()) {
                val selectedIndex = (activeTab?.let { openTabs.indexOf(it) } ?: 0).coerceAtLeast(0)
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    containerColor = DarkSurfaceElevated,
                    edgePadding = 0.dp,
                    divider = {},
                    // The default indicator indexes tabPositions[selectedTabIndex] directly, and it
                    // can recompose with the new index before the new tab has been measured, which
                    // crashed the app with IndexOutOfBounds whenever opening a file added a tab.
                    indicator = { tabPositions ->
                        if (selectedIndex in tabPositions.indices) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                                color = AccentCyan
                            )
                        }
                    }
                ) {
                    openTabs.forEach { tab ->
                        val fileName = File(tab).name
                        val isSelected = activeTab == tab
                        Tab(
                            selected = isSelected,
                            onClick = { project?.let { viewModel.openFile(it.id, tab) } },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = fileName + if (isSelected && isDirty) " *" else "",
                                        color = if (isSelected) AccentCyan else TextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close Tab",
                                        tint = TextMuted,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { viewModel.closeTab(tab) }
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // Main Editor Canvas
            Box(
                modifier = Modifier
                    .weight(if (showBottomConsole) 0.6f else 1f)
                    .fillMaxWidth()
                    .background(DarkBg)
            ) {
                if (activeTab != null) {
                    CodeEditor(
                        documentKey = activeTab ?: "",
                        content = viewModel.editorContent,
                        onContentChange = viewModel::updateEditorContent,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No file open. Tap the folder icon (top left) to browse files.", color = TextMuted)
                    }
                }
            }

            // Bottom Collapsible Console Drawer
            if (showBottomConsole) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.4f)
                        .background(TerminalBg)
                        .border(1.dp, DarkBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurface)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Execution Console", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary)
                        }

                        Row {
                            TextButton(onClick = { viewModel.clearLogs(project?.id) }) {
                                Text("Clear", fontSize = 11.sp, color = TextSecondary)
                            }
                            IconButton(onClick = { showBottomConsole = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    val projLogs = logs.filter { it.projectId == project?.id }.takeLast(200)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        items(projLogs) { event ->
                            val color = when (event.level) {
                                LogLevel.ERROR, LogLevel.STDERR -> AccentRed
                                LogLevel.WARN -> Color(0xFFFFB300)
                                LogLevel.STDOUT -> AccentGreen
                                LogLevel.SYSTEM -> AccentCyan
                                LogLevel.INFO -> TextPrimary
                            }
                            Text(
                                text = "[${event.level}] ${event.message}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = color,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

