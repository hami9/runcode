package com.runcode.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.runcode.app.backup.BackupFolderState
import com.runcode.app.backup.FolderBackups
import com.runcode.app.backup.StoredBackup
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupsScreen(
    viewModel: MainViewModel
) {
    val project by viewModel.selectedProject.collectAsState()
    val backups by viewModel.backupList.collectAsState()
    val preview by viewModel.backupPreview.collectAsState()
    val folder by viewModel.backupFolderState.collectAsState()
    val folderBackups by viewModel.folderBackups.collectAsState()
    val folderBusy by viewModel.folderBusy.collectAsState()

    var fileToRestore by remember { mutableStateOf<File?>(null) }
    var folderBackupToRestore by remember { mutableStateOf<StoredBackup?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.chooseBackupFolder(it) }
    }
    LaunchedEffect(folder.label) { viewModel.refreshFolderBackups() }

    // One scrolling list: the folder section and two backup lists do not fit a phone otherwise.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Backups & Portability", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("Create verified archives and restore project state", fontSize = 12.sp, color = TextSecondary)
                }

                project?.let { currentProj ->
                    Button(
                        onClick = { viewModel.createBackup(currentProj) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("backup_create_btn")
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Backup", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Security Notice Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Zero Secret Leak Policy", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                        Text(
                            "Backups exclude secrets, logs, and caches by default. Checksums are cryptographically verified before restore.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

        }

        item {
            BackupFolderCard(
                state = folder,
                busy = folderBusy,
                projectName = project?.name,
                onChoose = { pickFolder.launch(null) },
                onForget = { viewModel.forgetBackupFolder() },
                onBackup = { project?.let { viewModel.backupToFolder(it) } },
                onAutoChange = { viewModel.setAutoBackup(it) }
            )
        }

        // Verification result sits up here so it is visible without scrolling past every backup.
        preview?.let { prev ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (prev.isValid) AccentGreen.copy(alpha = 0.5f) else AccentRed.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (prev.isValid) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (prev.isValid) AccentGreen else AccentRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (prev.isValid) "Verification Passed" else "Verification Failed",
                                fontWeight = FontWeight.Bold,
                                color = if (prev.isValid) AccentGreen else AccentRed
                            )
                        }

                        // A failed verification with no reason on screen is useless; show why.
                        prev.error?.let { reason ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(reason, fontSize = 11.sp, color = AccentRed, lineHeight = 16.sp)
                        }

                        prev.manifest?.let { man ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Project: ${man.projectName} • Files: ${man.fileCount} • SHA-256: ${man.checksum.take(12)}...", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Payload entries: ${prev.fileList.joinToString(", ")}", fontSize = 11.sp, color = TextMuted, maxLines = 2)
                        }
                    }
                }
            }
        }

        if (folder.label != null) {
            item {
                Text("In the backup folder (${folderBackups.size})", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            }
            items(folderBackups, key = { it.id }) { backup ->
                BackupRow(
                    name = backup.name,
                    sizeBytes = backup.sizeBytes,
                    modifiedAt = backup.modifiedAt,
                    tag = backup.name,
                    onVerify = { viewModel.verifyFolderBackup(backup) },
                    onRestore = { folderBackupToRestore = backup }
                )
            }
        }

        item {
            Text("On this device (${backups.size})", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }

        if (backups.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No backups found for '${project?.name}'. Click 'New Backup' to generate one.", color = TextMuted)
                    }
                }
            }
        } else {
            items(backups, key = { it.absolutePath }) { file ->
                BackupRow(
                    name = file.name,
                    sizeBytes = file.length(),
                    modifiedAt = file.lastModified(),
                    tag = file.name,
                    onVerify = { viewModel.verifyBackup(file) },
                    onRestore = { fileToRestore = file }
                )
            }
        }

    }

    // Restore Confirmation Dialog
    if (fileToRestore != null || folderBackupToRestore != null) {
        val dismiss = { fileToRestore = null; folderBackupToRestore = null }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Restore Project Backup?", color = TextPrimary) },
            text = {
                Text(
                    "This will restore the project into a new staged instance safely. Restored code will NOT execute automatically until explicitly triggered.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        fileToRestore?.let { viewModel.restoreBackup(it) }
                        folderBackupToRestore?.let { viewModel.restoreFolderBackup(it) }
                        dismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("Confirm Restore", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun BackupRow(
    name: String,
    sizeBytes: Long,
    modifiedAt: Long,
    tag: String,
    onVerify: () -> Unit,
    onRestore: () -> Unit
) {
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(modifiedAt))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        // Filename and buttons stacked rather than side by side: a long .rcpkg name used to
        // squeeze the actions until "Verify" wrapped one letter per line and "Restore" fell
        // off the screen entirely.
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = name,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Size: ${sizeBytes / 1024} KB • Created: $dateStr",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onVerify,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).testTag("verify_backup_$tag")
                ) {
                    Text("Verify", color = AccentCyan, maxLines = 1)
                }
                Button(
                    onClick = onRestore,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).testTag("restore_backup_$tag")
                ) {
                    Text("Restore", color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** The user-chosen folder: Drive, OneDrive, a memory card or local storage, via the system picker. */
@Composable
private fun BackupFolderCard(
    state: BackupFolderState,
    busy: Boolean,
    projectName: String?,
    onChoose: () -> Unit,
    onForget: () -> Unit,
    onBackup: () -> Unit,
    onAutoChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Backup folder", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                    Text(
                        text = state.label ?: "Pick any folder, including Google Drive or OneDrive. No account needed here.",
                        fontSize = 11.sp,
                        color = if (state.label != null) AccentGreen else TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (state.label == null) {
                Button(
                    onClick = onChoose,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("backup_folder_choose_btn")
                ) {
                    Text("Choose folder", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onBackup,
                    enabled = !busy && projectName != null,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).testTag("backup_to_folder_btn")
                ) {
                    Text(
                        if (busy) "Working…" else "Back up to folder",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                TextButton(onClick = onChoose) { Text("Change", fontSize = 11.sp, color = AccentCyan) }
                TextButton(onClick = onForget) { Text("Forget", fontSize = 11.sp, color = TextMuted) }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Daily automatic backup", fontSize = 12.sp, color = TextPrimary)
                    Text(
                        text = "All projects, once a day while runcode is running. Keeps the last " +
                            "${FolderBackups.KEEP_AUTOMATIC} automatic copies per project.",
                        fontSize = 10.sp,
                        color = TextMuted,
                        lineHeight = 13.sp
                    )
                    if (state.lastAutoRun > 0) {
                        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(state.lastAutoRun))
                        Text("Last run $time: ${state.lastAutoResult ?: ""}", fontSize = 10.sp, color = TextSecondary)
                    }
                }
                Switch(
                    checked = state.autoEnabled,
                    onCheckedChange = onAutoChange,
                    modifier = Modifier.testTag("backup_auto_switch")
                )
            }
        }
    }
}
