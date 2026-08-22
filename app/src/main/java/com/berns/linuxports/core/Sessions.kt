package com.berns.linuxports.core

import android.content.Context
import com.berns.linuxports.model.Distro
import com.berns.linuxports.term.PtyProcess
import com.berns.linuxports.term.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** A shell attached to a pseudo-terminal inside a container. */
class TerminalSession(
    context: Context,
    val distro: Distro,
    rows: Int = 24,
    cols: Int = 80
) {
    private val proot = PRoot(context)
    val emulator = TerminalEmulator(rows, cols)

    private var process: PtyProcess? = null
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        if (process != null) return
        proot.ensureShims()
        val argv = proot.command(
            distro,
            listOf("/bin/bash", "-l"),
            user = Installer.CONTAINER_USER,
            term = "xterm-256color"
        )
        val p = PtyProcess.start(argv, proot.hostEnv(), rows = emulator.rows, cols = emulator.cols)
        process = p
        _running.value = true

        thread(name = "berns-term-${distro.key}", isDaemon = true) {
            val buf = ByteArray(16384)
            try {
                while (true) {
                    val n = p.read(buf)
                    if (n <= 0) break
                    synchronized(emulator) { emulator.feed(buf, n) }
                    _revision.value = emulator.revision
                }
            } catch (_: Throwable) {
            } finally {
                _running.value = false
                _revision.value = _revision.value + 1
            }
        }
    }

    fun write(text: String) = process?.write(text)

    fun write(bytes: ByteArray) = process?.write(bytes)

    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        synchronized(emulator) { emulator.resize(rows, cols) }
        process?.resize(rows, cols)
        _revision.value = _revision.value + 1
    }

    fun close() {
        process?.destroy()
        process = null
        _running.value = false
    }
}

enum class DesktopState { STOPPED, STARTING, READY, FAILED }

/** An Xvnc server plus an Xfce session, running inside a container. */
class DesktopSession(
    private val context: Context,
    val distro: Distro,
    val display: Int,
    val geometry: String,
    val dpi: Int
) {
    val port: Int get() = 5900 + display

    private val proot = PRoot(context)
    private var process: PtyProcess? = null

    private val _state = MutableStateFlow(DesktopState.STOPPED)
    val state: StateFlow<DesktopState> = _state.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (_state.value == DesktopState.STARTING || _state.value == DesktopState.READY) return
        _state.value = DesktopState.STARTING
        _log.value = emptyList()
        proot.ensureShims()

        val argv = proot.command(
            distro,
            listOf("/usr/local/bin/berns-desktop", display.toString(), geometry, dpi.toString()),
            user = Installer.CONTAINER_USER,
            term = "dumb"
        )
        val p = PtyProcess.start(argv, proot.hostEnv(), rows = 24, cols = 100)
        process = p

        thread(name = "berns-desktop-${distro.key}", isDaemon = true) {
            val buf = ByteArray(8192)
            val pending = StringBuilder()
            try {
                while (true) {
                    val n = p.read(buf)
                    if (n <= 0) break
                    pending.append(String(buf, 0, n, Charsets.UTF_8))
                    var i = pending.indexOf("\n")
                    while (i >= 0) {
                        appendLog(pending.substring(0, i).trimEnd('\r'))
                        pending.delete(0, i + 1)
                        i = pending.indexOf("\n")
                    }
                }
            } catch (_: Throwable) {
            } finally {
                if (pending.isNotEmpty()) appendLog(pending.toString())
                if (_state.value != DesktopState.STOPPED) {
                    _state.value = if (_state.value == DesktopState.READY) DesktopState.STOPPED else DesktopState.FAILED
                }
            }
        }

        scope.launch {
            // Xvnc needs a few seconds to bind; give it a generous window on slow devices.
            repeat(120) {
                delay(500)
                if (_state.value == DesktopState.FAILED || _state.value == DesktopState.STOPPED) return@launch
                if (withContext(Dispatchers.IO) { ContainerRunner.isPortOpen(port) }) {
                    _state.value = DesktopState.READY
                    appendLog("berns: VNC is listening on 127.0.0.1:$port")
                    return@launch
                }
            }
            if (_state.value == DesktopState.STARTING) {
                _state.value = DesktopState.FAILED
                appendLog("berns: the VNC server did not come up in time")
            }
        }
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        _log.value = (_log.value + line).takeLast(400)
    }

    fun stop() {
        _state.value = DesktopState.STOPPED
        process?.destroy()
        process = null
    }
}

/**
 * Owns every live container session. A singleton because the sessions have to outlive the
 * activity: rotating the phone or switching apps must not kill a running desktop.
 */
object SessionManager {

    private val terminals = mutableMapOf<String, TerminalSession>()
    private val desktops = mutableMapOf<String, DesktopSession>()

    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    @Synchronized
    fun terminal(context: Context, distro: Distro, rows: Int, cols: Int): TerminalSession {
        val existing = terminals[distro.key]
        if (existing != null && existing.running.value) {
            existing.resize(rows, cols)
            return existing
        }
        val session = TerminalSession(context.applicationContext, distro, rows, cols)
        terminals[distro.key] = session
        session.start()
        refresh()
        return session
    }

    @Synchronized
    fun closeTerminal(distro: Distro) {
        terminals.remove(distro.key)?.close()
        refresh()
    }

    @Synchronized
    fun desktop(context: Context, distro: Distro, display: Int, geometry: String, dpi: Int): DesktopSession {
        val existing = desktops[distro.key]
        if (existing != null && existing.state.value != DesktopState.STOPPED &&
            existing.state.value != DesktopState.FAILED
        ) {
            return existing
        }
        val session = DesktopSession(context.applicationContext, distro, display, geometry, dpi)
        desktops[distro.key] = session
        session.start()
        refresh()
        return session
    }

    @Synchronized
    fun desktopOrNull(distro: Distro): DesktopSession? = desktops[distro.key]

    @Synchronized
    fun stopDesktop(distro: Distro) {
        desktops.remove(distro.key)?.stop()
        refresh()
    }

    @Synchronized
    fun stopAll(distro: Distro) {
        terminals.remove(distro.key)?.close()
        desktops.remove(distro.key)?.stop()
        refresh()
    }

    @Synchronized
    fun stopEverything() {
        terminals.values.forEach { it.close() }
        desktops.values.forEach { it.stop() }
        terminals.clear()
        desktops.clear()
        refresh()
    }

    @Synchronized
    fun isRunning(distro: Distro): Boolean =
        terminals[distro.key]?.running?.value == true ||
            desktops[distro.key]?.state?.value?.let { it == DesktopState.READY || it == DesktopState.STARTING } == true

    private fun refresh() {
        val keys = mutableSetOf<String>()
        terminals.forEach { (k, v) -> if (v.running.value) keys += k }
        desktops.forEach { (k, v) -> if (v.state.value != DesktopState.STOPPED) keys += k }
        _active.value = keys
    }
}
