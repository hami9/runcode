package com.runcode.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runcode.app.runtime.DebugState
import com.runcode.app.runtime.DebugStatus
import com.runcode.app.runtime.DebugVariable
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.AccentRed
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import com.runcode.app.ui.theme.TextSecondary

/** Controls, variables, call stack and expression evaluation for a debug run. */
@Composable
fun DebugPanel(viewModel: MainViewModel, state: DebugState, modifier: Modifier = Modifier) {
    val evalResult by viewModel.debugEvalResult.collectAsState()
    var expression by remember { mutableStateOf("") }
    var showStack by remember { mutableStateOf(false) }
    val paused = state.status == DebugStatus.PAUSED

    Column(modifier = modifier.background(DarkSurface).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text = when (state.status) {
                DebugStatus.STARTING -> "Debugger starting…"
                DebugStatus.RUNNING -> "Running — stops at the next breakpoint"
                DebugStatus.PAUSED -> "Paused at ${state.file}:${state.line} in ${state.function}" +
                    if (state.reason == "breakpoint") " (breakpoint)" else ""
                DebugStatus.FINISHED -> "Debug run ended: ${state.outcome?.substringBefore(':')}"
                DebugStatus.IDLE -> ""
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (paused) AccentCyan else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Labels rather than icons: the meaning of step over/into/out is not obvious from glyphs.
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            DebugButton("Continue", paused, AccentGreen, "debug_continue") { viewModel.debugCommand("continue") }
            DebugButton("Over", paused, AccentCyan, "debug_over") { viewModel.debugCommand("next") }
            DebugButton("Into", paused, AccentCyan, "debug_into") { viewModel.debugCommand("step") }
            DebugButton("Out", paused, AccentCyan, "debug_out") { viewModel.debugCommand("return") }
            DebugButton("Stop", state.status == DebugStatus.PAUSED || state.status == DebugStatus.RUNNING, AccentRed, "debug_stop") {
                if (paused) viewModel.debugCommand("stop") else state.projectId?.let(viewModel::stopProject)
            }
            if (state.status == DebugStatus.FINISHED) {
                TextButton(onClick = { viewModel.dismissDebug() }) { Text("Close", fontSize = 12.sp, color = TextSecondary) }
            }
            if (paused) {
                TextButton(onClick = { showStack = !showStack }) {
                    Text(if (showStack) "Variables" else "Stack (${state.stack.size})", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }

        if (paused) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                if (showStack) {
                    items(state.stack) { frame ->
                        Text("${frame.function}  ${frame.file}:${frame.line}", fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    if (state.locals.isNotEmpty()) item { Section("Locals") }
                    items(state.locals) { VariableRow(it) }
                    if (state.globals.isNotEmpty()) item { Section("Globals") }
                    items(state.globals) { VariableRow(it) }
                    if (state.locals.isEmpty() && state.globals.isEmpty()) item { Section("No variables yet") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = expression,
                    onValueChange = { expression = it },
                    placeholder = { Text("Expression, e.g. len(items)", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.weight(1f).testTag("debug_eval_input")
                )
                TextButton(onClick = { viewModel.debugEvaluate(expression) }, enabled = expression.isNotBlank()) {
                    Text("Eval", color = AccentCyan)
                }
            }
            evalResult?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } else if (state.status == DebugStatus.RUNNING) {
            Text(
                "A script blocked in a C call, such as time.sleep or a network read, pauses on its next line.",
                fontSize = 10.sp,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun DebugButton(
    label: String,
    enabled: Boolean,
    color: androidx.compose.ui.graphics.Color,
    tag: String,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (enabled) color else TextMuted)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun VariableRow(variable: DebugVariable) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(variable.name, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AccentCyan)
        Text(variable.value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(variable.type, fontSize = 10.sp, color = TextMuted)
    }
}
