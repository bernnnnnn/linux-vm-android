package com.berns.linuxports.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.berns.linuxports.core.Abi
import com.berns.linuxports.core.InstallProgress
import com.berns.linuxports.core.Installer
import com.berns.linuxports.core.PRoot
import com.berns.linuxports.core.Paths
import com.berns.linuxports.core.Phase
import com.berns.linuxports.core.Prefs
import com.berns.linuxports.core.SessionManager
import com.berns.linuxports.model.Distro
import com.berns.linuxports.model.Distros
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val paths = Paths(app)
    private val installer = Installer(app)
    val prefs = Prefs(app)

    private val _progress = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())
    val progress: StateFlow<Map<String, InstallProgress>> = _progress.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val logs: StateFlow<Map<String, List<String>>> = _logs.asStateFlow()

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    val runtimeAvailable: Boolean = PRoot(app).isAvailable
    val architecture: String = "${Abi.androidAbi} (${Abi.debianArch})"
    val freeSpaceMb: Long get() = paths.freeSpaceMb()

    init {
        refreshInstalled()
    }

    fun refreshInstalled() {
        _installed.value = Distros.all.filter { paths.isInstalled(it) }.map { it.key }.toSet()
    }

    fun phase(distro: Distro): Phase = _progress.value[distro.key]?.phase ?: Phase.IDLE

    fun isBusy(distro: Distro): Boolean = phase(distro) in BUSY_PHASES

    fun install(distro: Distro) {
        if (jobs[distro.key]?.isActive == true) return
        _logs.value = _logs.value + (distro.key to emptyList())
        jobs[distro.key] = viewModelScope.launch {
            runCatching {
                installer.install(
                    distro = distro,
                    vncPassword = prefs.vncPassword(distro),
                    onProgress = { p -> _progress.value = _progress.value + (distro.key to p) },
                    onLog = { line -> appendLog(distro, line) }
                )
            }
            refreshInstalled()
        }
    }

    fun cancelInstall(distro: Distro) {
        jobs[distro.key]?.cancel()
        jobs.remove(distro.key)
        _progress.value = _progress.value + (distro.key to InstallProgress(Phase.IDLE, "Cancelled"))
        appendLog(distro, "installation cancelled")
    }

    fun uninstall(distro: Distro) {
        SessionManager.stopAll(distro)
        viewModelScope.launch {
            installer.uninstall(distro)
            _progress.value = _progress.value - distro.key
            _logs.value = _logs.value - distro.key
            refreshInstalled()
        }
    }

    private fun appendLog(distro: Distro, line: String) {
        val current = _logs.value[distro.key].orEmpty()
        _logs.value = _logs.value + (distro.key to (current + line).takeLast(600))
    }

    companion object {
        private val BUSY_PHASES = setOf(
            Phase.PREPARING, Phase.DOWNLOADING, Phase.VERIFYING,
            Phase.EXTRACTING, Phase.CONFIGURING, Phase.SETUP
        )
    }
}
