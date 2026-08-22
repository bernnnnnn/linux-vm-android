package com.berns.linuxports.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.berns.linuxports.core.Phase
import com.berns.linuxports.core.Prefs
import com.berns.linuxports.core.SessionManager
import com.berns.linuxports.model.Distro

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistroScreen(
    distro: Distro,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit
) {
    val installedKeys by viewModel.installed.collectAsState()
    val progressMap by viewModel.progress.collectAsState()
    val logMap by viewModel.logs.collectAsState()

    val installed = distro.key in installedKeys
    val progress = progressMap[distro.key]
    val logs = logMap[distro.key].orEmpty()
    val busy = progress?.phase in BUSY
    val accent = Color(distro.accent)

    var confirmRemove by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(distro.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            distro.edition,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Text(
                distro.tagline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(distro.desktop) })
                AssistChip(onClick = {}, label = { Text("~${distro.installedSizeMb} MB") })
            }
            Spacer(Modifier.height(18.dp))

            when {
                busy -> InstallProgressCard(
                    accent = accent,
                    label = progress?.message.orEmpty(),
                    fraction = progress?.fraction,
                    onCancel = { viewModel.cancelInstall(distro) }
                )

                installed -> ReadyCard(
                    distro = distro,
                    accent = accent,
                    prefs = viewModel.prefs,
                    onOpenDesktop = onOpenDesktop,
                    onOpenTerminal = onOpenTerminal,
                    onRemove = { confirmRemove = true }
                )

                else -> NotInstalledCard(
                    accent = accent,
                    error = progress?.takeIf { it.phase == Phase.FAILED }?.message,
                    canInstall = viewModel.runtimeAvailable,
                    onInstall = { viewModel.install(distro) }
                )
            }

            if (logs.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Build log", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LogView(logs)
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${distro.name}?") },
            text = {
                Text(
                    "This deletes the whole container, including anything you saved inside it. " +
                        "Files in /mnt/android are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.uninstall(distro)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun InstallProgressCard(accent: Color, label: String, fraction: Float?, onCancel: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun NotInstalledCard(accent: Color, error: String?, canInstall: Boolean, onInstall: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Not installed yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Installing downloads an Ubuntu base image and then builds the desktop inside " +
                    "it. Budget a few hundred megabytes of download and 10-30 minutes on the " +
                    "first run - it is doing a real distribution install.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!canInstall) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "This build has no proot runtime bundled, so it cannot install anything. " +
                        "Build the APK with tools/fetch-prebuilts.sh first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onInstall,
                enabled = canInstall,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)
            ) { Text("Install") }
        }
    }
}

@Composable
private fun ReadyCard(
    distro: Distro,
    accent: Color,
    prefs: Prefs,
    onOpenDesktop: () -> Unit,
    onOpenTerminal: () -> Unit,
    onRemove: () -> Unit
) {
    val running = SessionManager.active.collectAsState().value.contains(distro.key)
    var geometry by remember { mutableStateOf(prefs.geometry(distro)) }

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(10.dp)
                        .height(10.dp)
                        .background(if (running) accent else Color.Gray, RoundedCornerShape(5.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (running) "Session running" else "Installed and idle",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onOpenDesktop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)
            ) { Text("Open desktop") }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenTerminal, modifier = Modifier.fillMaxWidth()) {
                Text("Open terminal")
            }

            Spacer(Modifier.height(18.dp))
            Text("Screen size", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Prefs.GEOMETRIES.take(3).forEach { option ->
                    FilterChip(
                        selected = geometry == option,
                        onClick = {
                            geometry = option
                            prefs.setGeometry(distro, option)
                        },
                        label = { Text(option, fontSize = 12.sp) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Prefs.GEOMETRIES.drop(3).forEach { option ->
                    FilterChip(
                        selected = geometry == option,
                        onClick = {
                            geometry = option
                            prefs.setGeometry(distro, option)
                        },
                        label = { Text(option, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "VNC password: ${prefs.vncPassword(distro)}  (loopback only)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running) {
                    OutlinedButton(onClick = { SessionManager.stopAll(distro) }) { Text("Stop session") }
                }
                TextButton(onClick = onRemove) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun LogView(lines: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex)
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F12))
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .padding(12.dp)
        ) {
            items(lines) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFFB8C6C2)
                )
            }
        }
    }
}

private val BUSY = setOf(
    Phase.PREPARING, Phase.DOWNLOADING, Phase.VERIFYING,
    Phase.EXTRACTING, Phase.CONFIGURING, Phase.SETUP
)
