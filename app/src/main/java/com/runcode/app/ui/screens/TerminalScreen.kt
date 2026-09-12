package com.runcode.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.AccentRed
import com.runcode.app.ui.theme.DarkBorder
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.TerminalBg
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import com.runcode.app.ui.theme.TextSecondary

@Composable
fun TerminalScreen(viewModel: MainViewModel) {
    val lines by viewModel.terminalLines.collectAsState()
    val isRunning by viewModel.terminalRunning.collectAsState()
    val workingDir by viewModel.terminalWorkingDir.collectAsState()
    val project by viewModel.selectedProject.collectAsState()

    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Terminal", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(
                    text = if (isRunning) "sh · ${project?.name ?: "app sandbox"}" else "no session",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            if (isRunning) {
                Button(
                    onClick = { viewModel.stopTerminal() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("terminal_stop_btn")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = AccentRed, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("End", color = AccentRed)
                }
            } else {
                Button(
                    onClick = { viewModel.startTerminal() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("terminal_start_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Start", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = workingDir.ifBlank { "—" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextMuted,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.clearTerminal() }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            if (lines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Start a session to get a shell in the app sandbox.",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(lines, key = { it.id }) { line ->
                        Text(
                            text = line.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = if (line.isInput) AccentCyan else TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            placeholder = { Text("ls -la", color = TextMuted, fontFamily = FontFamily.Monospace) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextPrimary),
            singleLine = true,
            enabled = isRunning,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSend = {
                    if (command.isNotBlank()) {
                        viewModel.sendTerminalCommand(command)
                        command = ""
                    }
                }
            ),
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (command.isNotBlank()) {
                            viewModel.sendTerminalCommand(command)
                            command = ""
                        }
                    },
                    enabled = isRunning
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Run",
                        tint = if (isRunning) AccentCyan else TextMuted
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("terminal_input"),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface,
                disabledContainerColor = DarkSurface,
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = DarkBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
    }
}
