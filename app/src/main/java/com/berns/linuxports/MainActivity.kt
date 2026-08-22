package com.berns.linuxports

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.berns.linuxports.model.Distros
import com.berns.linuxports.ui.AppViewModel
import com.berns.linuxports.ui.BernsTheme
import com.berns.linuxports.ui.DesktopScreen
import com.berns.linuxports.ui.DistroScreen
import com.berns.linuxports.ui.HomeScreen
import com.berns.linuxports.ui.TerminalScreen

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForNotifications()
        setContent {
            BernsTheme {
                AppRoot()
            }
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun AppRoot(viewModel: AppViewModel = viewModel()) {
    var route by rememberSaveable { mutableStateOf(HOME) }

    val distro = remember(route) {
        route.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let { Distros.byKey(it) }
    }

    when {
        route == HOME || distro == null -> HomeScreen(
            viewModel = viewModel,
            onOpen = { route = "detail:${it.key}" }
        )

        route.startsWith("detail:") -> DistroScreen(
            distro = distro,
            viewModel = viewModel,
            onBack = { route = HOME },
            onOpenTerminal = { route = "term:${distro.key}" },
            onOpenDesktop = { route = "desk:${distro.key}" }
        )

        route.startsWith("term:") -> TerminalScreen(
            distro = distro,
            onBack = { route = "detail:${distro.key}" }
        )

        route.startsWith("desk:") -> DesktopScreen(
            distro = distro,
            viewModel = viewModel,
            onBack = { route = "detail:${distro.key}" }
        )
    }
}

private const val HOME = "home"
