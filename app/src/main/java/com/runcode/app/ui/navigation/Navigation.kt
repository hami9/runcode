package com.runcode.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Dashboard),
    PROJECTS("Projects", Icons.Default.Folder),
    EDITOR("Editor", Icons.Default.Code),
    GIT("Git", Icons.Default.AccountTree),
    TERMINAL("Shell", Icons.Default.Terminal),
    SERVICES("Services", Icons.Default.PlayCircle),
    DATABASE("Data", Icons.Default.Storage),
    BACKUPS("Backups", Icons.Default.SettingsBackupRestore),
    HEALTH("System", Icons.Default.HealthAndSafety)
}
