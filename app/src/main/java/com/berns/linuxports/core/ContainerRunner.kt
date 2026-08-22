package com.berns.linuxports.core

import android.content.Context
import com.berns.linuxports.model.Distro
import com.berns.linuxports.term.PtyProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Runs one command inside a container and streams its output back line by line. */
class ContainerRunner(context: Context) {

    private val proot = PRoot(context)

    suspend fun runToLog(
        distro: Distro,
        command: List<String>,
        user: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        proot.ensureShims()
        val argv = proot.command(distro, command, user = user, term = "dumb", extraEnv = extraEnv)
        val process = PtyProcess.start(argv, proot.hostEnv(), rows = 24, cols = 100)
        try {
            val buf = ByteArray(8192)
            val pending = StringBuilder()
            while (true) {
                val n = process.read(buf)
                if (n <= 0) break
                pending.append(String(buf, 0, n, Charsets.UTF_8))
                var idx = pending.indexOf("\n")
                while (idx >= 0) {
                    onLine(pending.substring(0, idx).trimEnd('\r'))
                    pending.delete(0, idx + 1)
                    idx = pending.indexOf("\n")
                }
                if (pending.length > 8192) {
                    onLine(pending.toString())
                    pending.setLength(0)
                }
            }
            if (pending.isNotEmpty()) onLine(pending.toString())
            process.waitFor()
        } finally {
            process.destroy()
        }
    }

    companion object {
        /** True once something is listening on the loopback port, i.e. the VNC server is up. */
        fun isPortOpen(port: Int, timeoutMs: Int = 400): Boolean = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }
}
