package com.runcode.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.runcode.app.ui.MainViewModel
import com.runcode.app.ui.navigation.Screen
import com.runcode.app.ui.screens.BackupsScreen
import com.runcode.app.ui.screens.DatabaseScreen
import com.runcode.app.ui.screens.EditorScreen
import com.runcode.app.ui.screens.HealthScreen
import com.runcode.app.ui.screens.HomeScreen
import com.runcode.app.ui.screens.ProjectsScreen
import com.runcode.app.ui.screens.ServicesScreen
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.DarkBg
import com.runcode.app.ui.theme.DarkBorder
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.RuncodeTheme
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import com.runcode.app.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RuncodeTheme {
                MainApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val userMessage by viewModel.userMessage.collectAsState()
    val instances by viewModel.instances.collectAsState()

    val runningCount = instances.values.count { it.isRunning }

    // Request notification permission on Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 4.dp
            ) {
                val navItems = listOf(
                    Screen.HOME,
                    Screen.PROJECTS,
                    Screen.EDITOR,
                    Screen.SERVICES,
                    Screen.DATABASE,
                    Screen.BACKUPS,
                    Screen.HEALTH
                )

                navItems.forEach { screen ->
                    val isSelected = currentScreen == screen
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentScreen = screen },
                        icon = {
                            if (screen == Screen.SERVICES && runningCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = AccentGreen) {
                                            Text(runningCount.toString(), color = Color.Black)
                                        }
                                    }
                                ) {
                                    Icon(screen.icon, contentDescription = screen.title)
                                }
                            } else {
                                Icon(screen.icon, contentDescription = screen.title)
                            }
                        },
                        label = {
                            Text(
                                text = screen.title,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Default
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentCyan,
                            selectedTextColor = AccentCyan,
                            indicatorColor = AccentCyan.copy(alpha = 0.15f),
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        )
                    )
                }
            }
        },
        containerColor = DarkBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkBg)
        ) {
            when (currentScreen) {
                Screen.HOME -> HomeScreen(viewModel = viewModel, onNavigate = { currentScreen = it })
                Screen.PROJECTS -> ProjectsScreen(viewModel = viewModel, onNavigate = { currentScreen = it })
                Screen.EDITOR -> EditorScreen(viewModel = viewModel)
                Screen.SERVICES -> ServicesScreen(viewModel = viewModel)
                Screen.DATABASE -> DatabaseScreen(viewModel = viewModel)
                Screen.BACKUPS -> BackupsScreen(viewModel = viewModel)
                Screen.HEALTH -> HealthScreen(viewModel = viewModel)
            }
        }
    }
}
