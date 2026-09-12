package com.runcode.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.Project
import com.runcode.app.storage.FileNode
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.AccentRed
import com.runcode.app.ui.theme.CodeHighlightBg
import com.runcode.app.ui.theme.DarkBg
import com.runcode.app.ui.theme.DarkBorder
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.DarkSurfaceElevated
import com.runcode.app.ui.theme.TerminalBg
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import com.runcode.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun EditorScreen(
    viewModel: MainViewModel
) {
    val project by viewModel.selectedProject.collectAsState()
    val openTabs by viewModel.openTabs.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val editorContent by viewModel.editorContent.collectAsState()
    val isDirty by viewModel.isDirty.collectAsState()
    val projectFiles by viewModel.projectFiles.collectAsState()
    val instances by viewModel.instances.collectAsState()
    val logs by viewModel.logs.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showNewFileDialog by remember { mutableStateOf(false) }
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
                modifier = Modifier.width(300.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Files: ${project?.name}", fontWeight = FontWeight.Bold, color = TextPrimary)
                        IconButton(onClick = { showNewFileDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "New File", tint = AccentCyan)
                        }
                    }
                    Divider(color = DarkBorder, modifier = Modifier.padding(vertical = 8.dp))

                    LazyColumn {
                        items(projectFiles) { node ->
                            FileNodeItem(
                                node = node,
                                activeTab = activeTab,
                                onFileClick = { rel ->
                                    project?.let { viewModel.openFile(it.id, rel) }
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteClick = { rel ->
                                    viewModel.deleteCurrentFile(rel)
                                }
                            )
                        }
                    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "File Tree", tint = AccentCyan)
                    }
                    Text(
                        text = project?.name ?: "Editor",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    if (isDirty) {
                        Text(" *", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.undo() }) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.redo() }) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.saveCurrentFile() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = if (isDirty) AccentCyan else TextMuted, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { showBottomConsole = !showBottomConsole }) {
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
                ScrollableTabRow(
                    selectedTabIndex = activeTab?.let { openTabs.indexOf(it) } ?: 0,
                    containerColor = DarkSurfaceElevated,
                    edgePadding = 0.dp,
                    divider = {}
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
                    val lines = editorContent.lines()
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Line numbers column
                        Column(
                            modifier = Modifier
                                .width(42.dp)
                                .fillMaxHeight()
                                .background(DarkSurface)
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            lines.indices.take(1500).forEach { index ->
                                Text(
                                    text = "${index + 1} ",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    lineHeight = 20.sp
                                )
                            }
                        }

                        Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = DarkBorder)

                        // Editable code text field
                        BasicTextField(
                            value = editorContent,
                            onValueChange = { viewModel.updateEditorContent(it) },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = TextPrimary
                            ),
                            cursorBrush = SolidColor(AccentCyan),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .testTag("editor_text_input")
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No file open. Open a file from the left sidebar.", color = TextMuted)
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

    // New File Dialog
    if (showNewFileDialog) {
        var newFileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Create New File", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("File Name") },
                    placeholder = { Text("e.g. utils.py, config.json") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFileName.isNotBlank()) {
                            viewModel.createNewFile(newFileName.trim())
                            showNewFileDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text("Create", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
fun FileNodeItem(
    node: FileNode,
    activeTab: String?,
    onFileClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    if (node.isDirectory) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(node.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                node.children.forEach { child ->
                    FileNodeItem(child, activeTab, onFileClick, onDeleteClick)
                }
            }
        }
    } else {
        val isSelected = activeTab == node.relativePath
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) DarkSurface else Color.Transparent)
                .clickable { onFileClick(node.relativePath) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = node.name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (isSelected) AccentCyan else TextPrimary
                )
            }

            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = TextMuted,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onDeleteClick(node.relativePath) }
            )
        }
    }
}
