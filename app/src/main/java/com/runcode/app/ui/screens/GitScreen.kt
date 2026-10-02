package com.runcode.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runcode.app.git.GitStatus
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.AccentRed
import com.runcode.app.ui.theme.DarkBorder
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import com.runcode.app.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Amber = Color(0xFFFFB300)

/** Git for the selected project's source/ folder. */
@Composable
fun GitScreen(viewModel: MainViewModel) {
    val project by viewModel.selectedProject.collectAsState()
    val state by viewModel.git.collectAsState()
    var dialog by remember { mutableStateOf<GitDialog?>(null) }

    LaunchedEffect(project?.id) { viewModel.refreshGit() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Git", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        text = project?.let { "${it.name} · source/" } ?: "Select a project first",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { dialog = GitDialog.Settings }) { Text("Settings", color = AccentCyan, fontSize = 12.sp) }
                TextButton(onClick = { dialog = GitDialog.Clone }, modifier = Modifier.testTag("git_clone_btn")) {
                    Text("Clone", color = AccentCyan, fontSize = 12.sp)
                }
            }
        }

        state.busy?.let { label ->
            item { Text("$label…", fontSize = 12.sp, color = AccentCyan) }
        }
        state.error?.let { error ->
            item { Text(error, fontSize = 12.sp, color = AccentRed, lineHeight = 16.sp) }
        }

        if (project == null) return@LazyColumn

        if (!state.isRepository) {
            item {
                GitCard {
                    Text("Not a repository yet", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                    Text(
                        "Initialising creates a repository in source/ on the main branch, with a .gitignore for " +
                            "Python caches. data/, logs and secrets stay out of it.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.gitInit() },
                        enabled = state.busy == null,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        modifier = Modifier.testTag("git_init_btn")
                    ) { Text("Initialise repository", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
            return@LazyColumn
        }

        val status = state.status ?: return@LazyColumn
        item { BranchCard(status, state.busy == null, onBranch = { dialog = GitDialog.Branch }, onRemote = { dialog = GitDialog.Remote },
            onPush = { viewModel.gitPush() }, onPull = { viewModel.gitPull() }) }
        item { ChangesCard(status, state.busy == null, onStageAll = { viewModel.gitStageAll() }) }
        item { CommitCard(status, state.busy == null, onCommit = { message, stageAll -> viewModel.gitCommit(message, stageAll) }) }

        item { Text("History (${state.log.size})", style = MaterialTheme.typography.titleMedium, color = TextPrimary) }
        items(state.log, key = { it.sha }) { commit ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(commit.message, fontSize = 12.sp, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(commit.time * 1000))
                Text("${commit.sha} · ${commit.author.substringBefore(" <")} · $time",
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
            }
        }
    }

    when (dialog) {
        GitDialog.Settings -> SettingsDialog(viewModel) { dialog = null }
        GitDialog.Clone -> TextDialog(
            title = "Clone a repository",
            label = "https://github.com/user/repo.git",
            hint = "Creates a new project. Private repositories use the token from Settings.",
            confirm = "Clone"
        ) { url -> dialog = null; url?.let { viewModel.gitClone(it) } }
        GitDialog.Remote -> TextDialog(
            title = "Remote (origin)",
            label = "https://github.com/user/repo.git",
            initial = state.status?.remote ?: "",
            hint = "Do not put a token in the URL; it goes in Settings.",
            confirm = "Save"
        ) { url -> dialog = null; url?.let { viewModel.gitSetRemote(it) } }
        GitDialog.Branch -> BranchDialog(state.branches, state.status?.branch) { name, create ->
            dialog = null
            name?.let { viewModel.gitCheckout(it, create) }
        }
        null -> Unit
    }
}

private enum class GitDialog { Settings, Clone, Remote, Branch }

@Composable
private fun GitCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun BranchCard(
    status: GitStatus,
    enabled: Boolean,
    onBranch: () -> Unit,
    onRemote: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit
) {
    GitCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(status.branch ?: "(detached)", fontWeight = FontWeight.Bold, color = AccentGreen, fontSize = 14.sp)
                val sync = when {
                    status.remote == null -> "no remote"
                    status.ahead == null -> "not pushed yet"
                    status.ahead == 0 && status.behind == 0 -> "up to date with origin"
                    else -> "${status.ahead} to push · ${status.behind} to pull"
                }
                Text("${status.head ?: "no commits"} · $sync", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
            }
            TextButton(onClick = onBranch, enabled = enabled) { Text("Branch", color = AccentCyan, fontSize = 12.sp) }
        }
        Text(
            text = status.remote ?: "No remote set",
            fontSize = 11.sp,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRemote, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("Remote", color = AccentCyan, maxLines = 1)
            }
            OutlinedButton(onClick = onPull, enabled = enabled && status.remote != null, modifier = Modifier.weight(1f)) {
                Text("Pull", color = AccentCyan, maxLines = 1)
            }
            Button(
                onClick = onPush,
                enabled = enabled && status.remote != null && status.head != null,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                modifier = Modifier.weight(1f).testTag("git_push_btn")
            ) { Text("Push", color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
}

@Composable
private fun ChangesCard(status: GitStatus, enabled: Boolean, onStageAll: () -> Unit) {
    GitCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (status.isClean) "No changes" else "Changes",
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            if (status.unstaged.isNotEmpty() || status.untracked.isNotEmpty()) {
                TextButton(onClick = onStageAll, enabled = enabled) { Text("Stage all", color = AccentCyan, fontSize = 12.sp) }
            }
        }
        status.added.forEach { FileLine("A", it, AccentGreen) }
        status.modified.forEach { FileLine("M", it, AccentGreen) }
        status.deleted.forEach { FileLine("D", it, AccentGreen) }
        status.unstaged.forEach { FileLine("M", it, Amber) }
        status.untracked.forEach { FileLine("?", it, TextMuted) }
        if (!status.isClean) {
            Text("Green is staged, amber changed, grey new.", fontSize = 10.sp, color = TextMuted)
        }
    }
}

@Composable
private fun FileLine(mark: String, path: String, color: Color) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(mark, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color, modifier = Modifier.width(16.dp))
        Text(path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CommitCard(status: GitStatus, enabled: Boolean, onCommit: (String, Boolean) -> Unit) {
    var message by remember { mutableStateOf("") }
    var stageAll by remember { mutableStateOf(true) }
    val hasWork = if (stageAll) !status.isClean else status.staged.isNotEmpty()
    GitCard {
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Commit message") },
            modifier = Modifier.fillMaxWidth().testTag("git_commit_message"),
            minLines = 2
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = stageAll, onCheckedChange = { stageAll = it })
            Text("Stage all changes first", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            Button(
                onClick = { onCommit(message, stageAll); message = "" },
                enabled = enabled && hasWork && message.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                modifier = Modifier.testTag("git_commit_btn")
            ) { Text("Commit", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SettingsDialog(viewModel: MainViewModel, onDone: () -> Unit) {
    val identity = remember { viewModel.gitIdentity }
    var name by remember { mutableStateOf(identity.name) }
    var email by remember { mutableStateOf(identity.email) }
    var token by remember { mutableStateOf("") }
    var clearToken by remember { mutableStateOf(false) }
    val hasToken = remember { viewModel.gitHasToken }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Git settings", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitHub shows commits under the account whose email matches.", fontSize = 11.sp, color = TextSecondary)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Author name") }, singleLine = true)
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Author email") }, singleLine = true)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; clearToken = false },
                    label = { Text(if (hasToken) "New token (one is saved)" else "GitHub token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Text(
                    "A fine-grained token with Contents read and write. It is kept in the Keystore vault and never shown again.",
                    fontSize = 10.sp,
                    color = TextMuted,
                    lineHeight = 13.sp
                )
                if (hasToken) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearToken, onCheckedChange = { clearToken = it; if (it) token = "" })
                        Text("Remove the saved token", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.saveGitSettings(name, email, when {
                    clearToken -> ""
                    token.isNotBlank() -> token
                    else -> null
                })
                onDone()
            }, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = TextSecondary) } }
    )
}

@Composable
private fun TextDialog(
    title: String,
    label: String,
    hint: String,
    confirm: String,
    initial: String = "",
    onDone: (String?) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true)
                Text(hint, fontSize = 11.sp, color = TextMuted)
            }
        },
        confirmButton = {
            Button(onClick = { onDone(value.trim()) }, enabled = value.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text(confirm, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel", color = TextSecondary) } }
    )
}

@Composable
private fun BranchDialog(branches: List<String>, current: String?, onDone: (String?, Boolean) -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null, false) },
        title = { Text("Branches", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                branches.forEach { branch ->
                    TextButton(onClick = { onDone(branch, false) }, enabled = branch != current) {
                        Text(if (branch == current) "● $branch" else branch,
                            color = if (branch == current) AccentGreen else AccentCyan, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedTextField(value = newName, onValueChange = { newName = it.trim() },
                    label = { Text("New branch from here") }, singleLine = true)
                Text("Switching refuses to overwrite uncommitted changes.", fontSize = 10.sp, color = TextMuted)
            }
        },
        confirmButton = {
            Button(onClick = { onDone(newName, true) }, enabled = newName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text("Create", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null, false) }) { Text("Close", color = TextSecondary) } }
    )
}
