package com.runcode.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.RestartPolicy
import com.runcode.app.settings.EnvironmentEdit
import com.runcode.app.settings.ProjectSettings
import com.runcode.app.ui.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun ProjectSettingsDialog(project: Project, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var port by remember { mutableStateOf(project.network.port.toString()) }
    var cpu by remember { mutableStateOf(project.maxCpuPercent.toString()) }
    var heap by remember { mutableStateOf(project.maxHeapMb.toString()) }
    var idle by remember { mutableStateOf(project.idleTimeoutMinutes.toString()) }
    var policy by remember { mutableStateOf(project.restartPolicy) }
    var boot by remember { mutableStateOf(project.startOnBoot) }
    var environment by remember { mutableStateOf(ProjectSettings.from(project).environment) }
    var policyExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        title = { Text("Project settings · ${project.name}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Stop the service before saving. New settings apply on its next run.")
                SettingsNumber("Port", port, !saving) { port = it }
                Column {
                    TextButton(onClick = { policyExpanded = true }, enabled = !saving) {
                        Text("Restart: ${policy.name}")
                    }
                    DropdownMenu(expanded = policyExpanded, onDismissRequest = { policyExpanded = false }) {
                        RestartPolicy.entries.forEach { candidate ->
                            DropdownMenuItem(text = { Text(candidate.name) }, onClick = {
                                policy = candidate
                                policyExpanded = false
                            })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = boot, onCheckedChange = { boot = it }, enabled = !saving)
                    Text("Start on device boot")
                }
                Text("Limits: 0 means unlimited. CPU measures the service thread; heap is shared by the whole app. Idle timeout applies to static websites.")
                SettingsNumber("CPU limit (%)", cpu, !saving) { cpu = it }
                SettingsNumber("App Java heap limit (MB)", heap, !saving) { heap = it }
                SettingsNumber("Static web idle timeout (minutes)", idle, !saving) { idle = it }
                HorizontalDivider()
                Text("Environment variables", style = MaterialTheme.typography.titleMedium)
                Text("Secret values are encrypted on this device. Leave an existing secret blank to keep it; enter a value to replace it.")
                environment.forEachIndexed { index, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = item.key,
                            onValueChange = { key -> environment = environment.toMutableList().also { it[index] = item.copy(key = key) } },
                            label = { Text("Name") }, singleLine = true, enabled = !saving,
                            readOnly = item.isSecret && item.value == null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = item.value ?: "",
                            onValueChange = { value -> environment = environment.toMutableList().also {
                                it[index] = item.copy(value = if (item.isSecret && value.isEmpty() &&
                                    project.environment.any { old -> old.isSecret && old.key == item.key }) null else value)
                            } },
                            label = { Text(if (item.isSecret && item.value == null) "Secret (keep or replace)" else "Value") },
                            visualTransformation = if (item.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (item.isSecret) KeyboardType.Password else KeyboardType.Text),
                            singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.isSecret, enabled = !saving, onCheckedChange = { secret ->
                                environment = environment.toMutableList().also { it[index] = item.copy(isSecret = secret, value = "") }
                            })
                            Text("Secret", Modifier.weight(1f))
                            TextButton(enabled = !saving, onClick = { environment = environment.filterIndexed { i, _ -> i != index } }) {
                                Text("Remove")
                            }
                        }
                    }
                }
                TextButton(enabled = !saving, onClick = { environment = environment + EnvironmentEdit("", "", false) }) {
                    Text("Add variable")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                val numbers = listOf(port, cpu, heap, idle).map { it.toIntOrNull() }
                if (numbers.any { it == null }) {
                    error = "Enter whole numbers for the port and limits (0 disables a limit)"
                } else {
                    saving = true
                    scope.launch {
                        error = viewModel.updateProjectSettings(project.id, ProjectSettings(
                            numbers[0]!!, policy, boot, numbers[1]!!, numbers[2]!!, numbers[3]!!, environment
                        ))
                        saving = false
                        if (error == null) onDismiss()
                    }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsNumber(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
