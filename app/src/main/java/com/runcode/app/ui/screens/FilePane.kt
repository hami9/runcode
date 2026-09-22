package com.runcode.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runcode.app.domain.models.Project
import com.runcode.app.storage.FileNode
import com.runcode.app.storage.ProjectStorage
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.AccentRed
import com.runcode.app.ui.theme.DarkBorder
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import com.runcode.app.ui.theme.TextSecondary

/** A pending file-manager dialog. */
private sealed interface FileDialog {
    data class NewFile(val parent: String) : FileDialog
    data class NewFolder(val parent: String) : FileDialog
    data class Rename(val node: FileNode) : FileDialog
    data class Move(val node: FileNode) : FileDialog
    data class Delete(val node: FileNode) : FileDialog
}

private data class FileRow(val node: FileNode, val depth: Int)

/**
 * The project's file manager: the whole project tree, with create, rename, move, delete,
 * import from the phone and export back out through the system file picker.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilePane(
    viewModel: MainViewModel,
    project: Project,
    files: List<FileNode>,
    activeTab: String?,
    onOpenFile: (String) -> Unit
) {
    // Keyed on the project so switching projects starts from a clean tree.
    var expanded by rememberSaveable(project.id) { mutableStateOf(listOf("source")) }
    var targetDir by rememberSaveable(project.id) { mutableStateOf("source") }
    var dialog by remember(project.id) { mutableStateOf<FileDialog?>(null) }
    var menuFor by remember(project.id) { mutableStateOf<String?>(null) }
    var headerMenuOpen by remember { mutableStateOf(false) }

    // The picker can outlive this composition (activity recreation), so remember across it.
    var pendingImportDir by rememberSaveable { mutableStateOf("source") }
    var pendingExportPath by rememberSaveable { mutableStateOf<String?>(null) }

    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importFiles(uris, pendingImportDir)
    }
    val createDocument = remember { CreateDocumentWithType() }
    val exportFile = rememberLauncherForActivityResult(createDocument) { uri ->
        val path = pendingExportPath
        if (uri != null && path != null) viewModel.exportFile(path, uri)
        pendingExportPath = null
    }
    val exportProject = rememberLauncherForActivityResult(createDocument) { uri ->
        if (uri != null) viewModel.exportProject(uri)
    }

    fun startImport(dir: String) {
        pendingImportDir = dir
        expanded = (expanded + withAncestors(dir)).distinct()
        importFiles.launch(arrayOf("*/*"))
    }

    /** Opens every folder down to [path], so whatever was just created there is visible. */
    fun reveal(path: String) {
        expanded = (expanded + withAncestors(path)).distinct()
    }

    val entryPath = "source/${project.entryPoint}"
    val rows = remember(files, expanded) { visibleRows(files, expanded.toSet(), 0) }

    // The target folder can be deleted, renamed or moved; fall back instead of pointing at nothing.
    LaunchedEffect(files) {
        if (files.isNotEmpty() && targetDir !in allDirectories(files)) targetDir = "source"
    }

    Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Files", fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    project.name,
                    fontSize = 11.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { dialog = FileDialog.NewFile(targetDir) }, modifier = Modifier.testTag("files_new_file")) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "New file", tint = AccentCyan)
            }
            IconButton(onClick = { dialog = FileDialog.NewFolder(targetDir) }, modifier = Modifier.testTag("files_new_folder")) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder", tint = AccentCyan)
            }
            IconButton(onClick = { startImport(targetDir) }, modifier = Modifier.testTag("files_import")) {
                Icon(Icons.Default.UploadFile, contentDescription = "Import files from this phone", tint = AccentCyan)
            }
            Box {
                IconButton(onClick = { headerMenuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
                }
                DropdownMenu(expanded = headerMenuOpen, onDismissRequest = { headerMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Export project (.zip)") },
                        onClick = {
                            headerMenuOpen = false
                            exportProject.launch(safeFileName(project.name) + ".zip" to "application/zip")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        onClick = {
                            headerMenuOpen = false
                            viewModel.refreshProjectFiles(project.id)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Collapse all") },
                        onClick = {
                            headerMenuOpen = false
                            expanded = emptyList()
                        }
                    )
                }
            }
        }

        Text(
            text = "New files go to $targetDir/",
            fontSize = 11.sp,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, end = 8.dp))

        LazyColumn(modifier = Modifier.testTag("files_tree")) {
            items(rows, key = { it.node.relativePath }) { row ->
                val node = row.node
                val path = node.relativePath
                val isOpen = path in expanded
                val isStructural = row.depth == 0 && node.isDirectory && node.name in ProjectStorage.STRUCTURAL_DIRS
                val highlighted = if (node.isDirectory) path == targetDir else path == activeTab

                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (highlighted) DarkSurface else Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    if (node.isDirectory) {
                                        expanded = if (isOpen) expanded - path else expanded + path
                                        targetDir = path
                                    } else {
                                        onOpenFile(path)
                                    }
                                },
                                onLongClick = { menuFor = path }
                            )
                            .padding(start = (4 + row.depth * 14).dp, top = 2.dp, bottom = 2.dp)
                            .testTag("file_row_$path"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (node.isDirectory) {
                            Icon(
                                if (isOpen) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                        Icon(
                            iconFor(node, isOpen),
                            contentDescription = null,
                            tint = if (node.isDirectory) AccentCyan else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = node.name,
                            fontFamily = if (node.isDirectory) FontFamily.Default else FontFamily.Monospace,
                            fontWeight = if (isStructural) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 12.sp,
                            color = if (!node.isDirectory && path == activeTab) AccentCyan else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (path == entryPath) {
                            Text(
                                "ENTRY",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentGreen,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AccentGreen.copy(alpha = 0.12f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        if (!node.isDirectory) {
                            Text(ProjectStorage.formatSize(node.size), fontSize = 10.sp, color = TextMuted)
                        }
                        IconButton(onClick = { menuFor = path }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${node.name}", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }

                    DropdownMenu(expanded = menuFor == path, onDismissRequest = { menuFor = null }) {
                        if (node.isDirectory) {
                            DropdownMenuItem(text = { Text("New file here") }, onClick = {
                                menuFor = null
                                dialog = FileDialog.NewFile(path)
                            })
                            DropdownMenuItem(text = { Text("New folder here") }, onClick = {
                                menuFor = null
                                dialog = FileDialog.NewFolder(path)
                            })
                            DropdownMenuItem(text = { Text("Import files here") }, onClick = {
                                menuFor = null
                                startImport(path)
                            })
                        } else {
                            DropdownMenuItem(text = { Text("Open") }, onClick = {
                                menuFor = null
                                onOpenFile(path)
                            })
                            if (path.startsWith("source/") && path != entryPath) {
                                DropdownMenuItem(text = { Text("Set as entry point") }, onClick = {
                                    menuFor = null
                                    viewModel.setEntryPoint(path)
                                })
                            }
                            DropdownMenuItem(text = { Text("Save a copy to phone…") }, onClick = {
                                menuFor = null
                                pendingExportPath = path
                                exportFile.launch(node.name to mimeTypeOf(node.name))
                            })
                        }
                        if (!isStructural) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                menuFor = null
                                dialog = FileDialog.Rename(node)
                            })
                            DropdownMenuItem(text = { Text("Move to…") }, onClick = {
                                menuFor = null
                                dialog = FileDialog.Move(node)
                            })
                            DropdownMenuItem(text = { Text("Delete", color = AccentRed) }, onClick = {
                                menuFor = null
                                dialog = FileDialog.Delete(node)
                            })
                        }
                    }
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit
        is FileDialog.NewFile -> NameDialog(
            title = "New file",
            confirmLabel = "Create",
            initial = "",
            hint = "Created in ${current.parent}/. Use a/b.py to make folders too.",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                reveal((current.parent + "/" + name.trim().trim('/')).substringBeforeLast('/'))
                viewModel.createNewFile(current.parent, name)
            }
        )
        is FileDialog.NewFolder -> NameDialog(
            title = "New folder",
            confirmLabel = "Create",
            initial = "",
            hint = "Created in ${current.parent}/",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                reveal((current.parent + "/" + name.trim().trim('/')).substringBeforeLast('/'))
                viewModel.createFolder(current.parent, name)
            }
        )
        is FileDialog.Rename -> NameDialog(
            title = "Rename",
            confirmLabel = "Rename",
            initial = current.node.name,
            hint = current.node.relativePath,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                viewModel.renamePath(current.node.relativePath, name)
            }
        )
        is FileDialog.Move -> MoveDialog(
            node = current.node,
            files = files,
            onDismiss = { dialog = null },
            onConfirm = { destination ->
                dialog = null
                reveal(destination)
                viewModel.movePath(current.node.relativePath, destination)
            }
        )
        is FileDialog.Delete -> {
            val node = current.node
            val count = if (node.isDirectory) countFiles(node) else 0
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Delete ${node.name}?", color = TextPrimary) },
                text = {
                    Text(
                        when {
                            !node.isDirectory -> "${node.relativePath} will be deleted. This cannot be undone."
                            count == 0 -> "The empty folder ${node.relativePath} will be deleted."
                            else -> "${node.relativePath} and the $count file(s) inside it will be deleted. This cannot be undone."
                        } + if (node.relativePath == entryPath) "\n\nThis is the project's entry point." else "",
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            dialog = null
                            viewModel.deletePath(node.relativePath)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        modifier = Modifier.testTag("files_confirm_delete")
                    ) {
                        Text("Delete", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text("Cancel", color = TextSecondary) }
                }
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // Preselect the name without its extension, the part people usually change.
    val stemEnd = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                supportingText = { Text(hint, fontSize = 11.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag("files_name_input")
            )
        },
        confirmButton = {
            Button(
                onClick = { if (value.text.isNotBlank()) onConfirm(value.text.trim()) },
                enabled = value.text.isNotBlank() && value.text.trim() != initial,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                modifier = Modifier.testTag("files_name_confirm")
            ) {
                Text(confirmLabel, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
private fun MoveDialog(
    node: FileNode,
    files: List<FileNode>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val currentParent = node.relativePath.substringBeforeLast('/', "")
    // Anywhere except where it already is, and never into itself.
    val destinations = remember(files, node) {
        allDirectories(files).filter { dir ->
            dir != currentParent &&
                dir != node.relativePath &&
                !dir.startsWith(node.relativePath + "/")
        }
    }
    var selected by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${node.name}", color = TextPrimary) },
        text = {
            if (destinations.isEmpty()) {
                Text("There is no other folder to move it to. Create one first.", color = TextSecondary)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(destinations) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selected = dir }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == dir,
                                onClick = { selected = dir },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                            )
                            Text("$dir/", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("Move", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

private fun visibleRows(nodes: List<FileNode>, expanded: Set<String>, depth: Int): List<FileRow> =
    nodes.flatMap { node ->
        val row = listOf(FileRow(node, depth))
        if (node.isDirectory && node.relativePath in expanded) row + visibleRows(node.children, expanded, depth + 1) else row
    }

/** [path] and every folder above it: `source/a/b` gives source, source/a and source/a/b. */
private fun withAncestors(path: String): List<String> {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
}

private fun allDirectories(nodes: List<FileNode>): List<String> =
    nodes.filter { it.isDirectory }.flatMap { listOf(it.relativePath) + allDirectories(it.children) }

private fun countFiles(node: FileNode): Int =
    node.children.sumOf { if (it.isDirectory) countFiles(it) else 1 }

private fun iconFor(node: FileNode, isOpen: Boolean): ImageVector {
    if (node.isDirectory) return if (isOpen) Icons.Default.FolderOpen else Icons.Default.Folder
    return when (node.name.substringAfterLast('.', "").lowercase()) {
        "py", "js", "ts", "html", "htm", "css", "json", "sh", "kt", "java", "xml", "yaml", "yml", "toml" -> Icons.Default.Code
        "db", "sqlite", "sqlite3" -> Icons.Default.Storage
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico" -> Icons.Default.Image
        else -> Icons.Default.Description
    }
}

private fun mimeTypeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

private fun safeFileName(name: String): String =
    name.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
        .joinToString("")
        .trim('_')
        .ifEmpty { "project" }

/**
 * ACTION_CREATE_DOCUMENT with the MIME type chosen per launch. The stock contract fixes the
 * type when the launcher is created, which does not work for "save a copy" of arbitrary files.
 */
private class CreateDocumentWithType : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
