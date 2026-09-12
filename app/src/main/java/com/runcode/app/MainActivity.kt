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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.runcode.app.ui.screens.TerminalScreen
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.AccentGreen
import com.runcode.app.ui.theme.DarkBg
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.RuncodeTheme
import com.runcode.app.ui.theme.TextMuted

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

    // Battery-optimisation state and free memory change while the user is away in
    // Settings, so re-read them every time the app returns to the foreground.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshCapabilities()
        onPauseOrDispose { }
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
            RuncodeBottomBar(
                current = currentScreen,
                runningCount = runningCount,
                onSelect = { currentScreen = it }
            )
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
                Screen.TERMINAL -> TerminalScreen(viewModel = viewModel)
                Screen.SERVICES -> ServicesScreen(viewModel = viewModel)
                Screen.DATABASE -> DatabaseScreen(viewModel = viewModel)
                Screen.BACKUPS -> BackupsScreen(viewModel = viewModel)
                Screen.HEALTH -> HealthScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * Eight destinations do not fit a phone width. Material's NavigationBar distributes its items
 * with weights, which collapses under the infinite width a horizontal scroll hands it, so the
 * bar is laid out by hand instead: fixed-width items in a scrolling row.
 */
@Composable
private fun RuncodeBottomBar(
    current: Screen,
    runningCount: Int,
    onSelect: (Screen) -> Unit
) {
    Surface(color = DarkSurface, tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            Screen.entries.forEach { screen ->
                val isSelected = current == screen
                val tint = if (isSelected) AccentCyan else TextMuted

                Column(
                    modifier = Modifier
                        .width(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(screen) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isSelected) AccentCyan.copy(alpha = 0.15f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        if (screen == Screen.SERVICES && runningCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(containerColor = AccentGreen) {
                                        Text(runningCount.toString(), color = Color.Black)
                                    }
                                }
                            ) {
                                Icon(screen.icon, contentDescription = screen.title, tint = tint, modifier = Modifier.size(22.dp))
                            }
                        } else {
                            Icon(screen.icon, contentDescription = screen.title, tint = tint, modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = screen.title,
                        fontSize = 10.sp,
                        maxLines = 1,
                        fontFamily = FontFamily.Default,
                        color = tint
                    )
                }
            }
        }
    }
}
