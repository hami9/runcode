package com.runcode.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val project by viewModel.selectedProject.collectAsState()
    val databases by viewModel.discoveredDbs.collectAsState()
    val selectedDb by viewModel.selectedDb.collectAsState()
    val tables by viewModel.dbTables.collectAsState()
    val queryResult by viewModel.queryResult.collectAsState()

    var sqlInput by remember { mutableStateOf("SELECT * FROM tasks LIMIT 25") }
    var dbDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Project Database Manager", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text("Inspect SQLite schema, tables & run SQL queries", fontSize = 12.sp, color = TextSecondary)
            }

            IconButton(onClick = { project?.let { viewModel.refreshDatabases(it) } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (databases.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No SQLite database found in '${project?.name}'", color = TextSecondary, fontWeight = FontWeight.Medium)
                    Text("Create a SQLite project or add a .sqlite file in the project's data/ directory.", color = TextMuted, fontSize = 12.sp)
                }
            }
            return
        }

        // Database Selector & Table Chips
        ExposedDropdownMenuBox(
            expanded = dbDropdownExpanded,
            onExpandedChange = { dbDropdownExpanded = !dbDropdownExpanded }
        ) {
            OutlinedTextField(
                value = selectedDb?.name ?: "Select database",
                onValueChange = {},
                readOnly = true,
                label = { Text("Database File") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dbDropdownExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            ExposedDropdownMenu(
                expanded = dbDropdownExpanded,
                onDismissRequest = { dbDropdownExpanded = false }
            ) {
                databases.forEach { dbFile ->
                    DropdownMenuItem(
                        text = { Text("${dbFile.name} (${dbFile.length() / 1024} KB)") },
                        onClick = {
                            viewModel.selectDatabase(dbFile)
                            dbDropdownExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Table List
        if (tables.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tables.forEach { table ->
                    SuggestionChip(
                        onClick = {
                            sqlInput = "SELECT * FROM `${table.name}` LIMIT 25"
                            viewModel.executeSql(sqlInput)
                        },
                        label = {
                            Text("${table.name} (${table.rowCount} rows)", fontSize = 11.sp)
                        },
                        icon = {
                            Icon(Icons.Default.TableChart, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(14.dp))
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = DarkSurfaceElevated,
                            labelColor = TextPrimary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SQL Input Box
        OutlinedTextField(
            value = sqlInput,
            onValueChange = { sqlInput = it },
            placeholder = { Text("Enter SQL query (e.g. SELECT * FROM table)", color = TextMuted) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sql_query_input"),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary),
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface,
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = DarkBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Query Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { viewModel.executeSql(sqlInput) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("db_execute_sql_btn")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Execute SQL", color = Color.Black, fontWeight = FontWeight.Bold)
            }

            queryResult?.let { res ->
                Text(
                    text = if (res.error != null) "Error" else "${res.affectedRows} rows in ${res.durationMs}ms",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (res.error != null) AccentRed else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Results View
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            queryResult?.let { res ->
                if (res.error != null) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(text = "SQL Error: ${res.error}", color = AccentRed, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                } else if (res.columns.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "Statement executed successfully. Affected rows: ${res.affectedRows}", color = AccentGreen, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    val horizontalScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(horizontalScroll)
                            .padding(8.dp)
                    ) {
                        // Header Row
                        Row(
                            modifier = Modifier
                                .background(DarkSurfaceElevated)
                                .padding(vertical = 6.dp)
                        ) {
                            res.columns.forEach { col ->
                                Text(
                                    text = col,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentCyan,
                                    modifier = Modifier.width(130.dp).padding(horizontal = 6.dp)
                                )
                            }
                        }

                        Divider(color = DarkBorder)

                        // Rows
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(res.rows) { row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    row.forEach { cell ->
                                        Text(
                                            text = cell,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = TextPrimary,
                                            modifier = Modifier.width(130.dp).padding(horizontal = 6.dp)
                                        )
                                    }
                                }
                                Divider(color = DarkBorder.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Run a query to inspect database records", color = TextMuted)
            }
        }
    }
}
