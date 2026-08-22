package com.berns.linuxports.core

import android.content.Context
import android.net.ConnectivityManager
import com.berns.linuxports.model.Distro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

enum class Phase { IDLE, PREPARING, DOWNLOADING, VERIFYING, EXTRACTING, CONFIGURING, SETUP, DONE, FAILED }

data class InstallProgress(
    val phase: Phase,
    val message: String = "",
    /** 0f..1f for phases that can be measured, null for the ones that cannot. */
    val fraction: Float? = null
)

class InstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

class Installer(private val context: Context) {

    private val paths = Paths(context)
    private val proot = PRoot(context)

    suspend fun install(
        distro: Distro,
        vncPassword: String,
        onProgress: (InstallProgress) -> Unit,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress(InstallProgress(Phase.PREPARING, "Getting ready"))
            require(Abi.isSupported) { "unsupported CPU architecture: ${Abi.androidAbi}" }
            if (!proot.isAvailable) {
                throw InstallException(
                    "The proot runtime is missing from this build. Run tools/fetch-prebuilts.sh " +
                        "before building the APK."
                )
            }
            paths.ensureDirs()
            proot.ensureShims()

            val free = paths.freeSpaceMb()
            if (free < distro.installedSizeMb) {
                throw InstallException(
                    "Not enough free space: ${distro.displayName} needs about " +
                        "${distro.installedSizeMb} MB and ${free} MB is available."
                )
            }

            val distroDir = paths.distroDir(distro)
            val rootfs = paths.rootfs(distro)
            if (rootfs.exists()) deleteRecursively(rootfs)
            distroDir.mkdirs()
            rootfs.mkdirs()

            val arch = Abi.debianArch
            val fileName = distro.base.fileName(arch)
            val archive = File(paths.downloads, fileName)

            val expected = fetchExpectedSha256(distro, fileName, onLog)
            if (!(archive.isFile && expected != null && sha256(archive) == expected)) {
                download(distro.base.url(arch), archive) { done, total ->
                    onProgress(
                        InstallProgress(
                            Phase.DOWNLOADING,
                            "Downloading the ${arch} base image  ${mb(done)} / ${if (total > 0) mb(total) else "?"}",
                            if (total > 0) done.toFloat() / total else null
                        )
                    )
                }
                if (expected != null) {
                    onProgress(InstallProgress(Phase.VERIFYING, "Verifying the download"))
                    val actual = sha256(archive)
                    if (actual != expected) {
                        archive.delete()
                        throw InstallException("Checksum mismatch on $fileName - the download was corrupt.")
                    }
                    onLog("checksum ok: $expected")
                } else {
                    onLog("warning: could not fetch SHA256SUMS, skipping verification")
                }
            } else {
                onLog("reusing the verified image already in the cache")
            }

            coroutineContext.ensureActive()
            onProgress(InstallProgress(Phase.EXTRACTING, "Unpacking the root filesystem", 0f))
            extractTarGz(archive, rootfs) { fraction, entries ->
                onProgress(
                    InstallProgress(
                        Phase.EXTRACTING,
                        "Unpacking the root filesystem  ($entries files)",
                        fraction
                    )
                )
            }

            onProgress(InstallProgress(Phase.CONFIGURING, "Writing the container configuration"))
            configureRootfs(distro, rootfs, onLog)

            onProgress(InstallProgress(Phase.SETUP, "Installing the desktop - this takes a while"))
            runSetup(distro, vncPassword, onLog)

            paths.stampFile(distro).writeText(
                "distro=${distro.key}\narch=$arch\nbase=${distro.base.point}\ninstalled=${System.currentTimeMillis()}\n"
            )
            onProgress(InstallProgress(Phase.DONE, "${distro.displayName} is ready", 1f))
        } catch (e: Throwable) {
            onLog("FAILED: ${e.message}")
            onProgress(InstallProgress(Phase.FAILED, e.message ?: "Installation failed"))
            throw e
        }
    }

    fun uninstall(distro: Distro) {
        deleteRecursively(paths.distroDir(distro))
    }

    /**
     * Re-runs the maintenance script against a container that is already installed.
     *
     * Setup scripts only run once, at install time, so a fix shipped in a later version of
     * the app would otherwise never reach an existing container - and reinstalling means
     * downloading and rebuilding the whole distribution again.
     */
    suspend fun repair(
        distro: Distro,
        vncPassword: String,
        onProgress: (InstallProgress) -> Unit,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress(InstallProgress(Phase.PREPARING, "Checking the container"))
            val rootfs = paths.rootfs(distro)
            if (!paths.isInstalled(distro) || !File(rootfs, "etc").isDirectory) {
                throw InstallException("${distro.displayName} is not installed.")
            }
            proot.ensureShims()

            context.assets.open("setup/common.sh").use { input ->
                File(rootfs, "root/berns-common.sh").outputStream().use { input.copyTo(it) }
            }
            context.assets.open("setup/repair.sh").use { input ->
                File(rootfs, "root/berns-repair.sh").outputStream().use { input.copyTo(it) }
            }
            File(rootfs, "root/berns-repair.sh").setExecutable(true, false)
            // DNS can go stale when the phone changes network between sessions.
            File(rootfs, "etc/resolv.conf")
                .writeText(dnsServers().joinToString("\n", postfix = "\n") { "nameserver $it" })

            onProgress(InstallProgress(Phase.SETUP, "Updating ${distro.name} - this takes a few minutes"))
            val log = paths.logFile(distro)
            val exit = ContainerRunner(context).runToLog(
                distro = distro,
                command = listOf("/bin/bash", "/root/berns-repair.sh"),
                extraEnv = mapOf(
                    "BERNS_USER" to CONTAINER_USER,
                    "BERNS_VNC_PASS" to vncPassword
                )
            ) { line ->
                log.appendText(line + "\n")
                onLog(line)
            }
            if (exit != 0) throw InstallException("The update script exited with code $exit.")
            onProgress(InstallProgress(Phase.DONE, "${distro.displayName} is up to date", 1f))
        } catch (e: Throwable) {
            onLog("FAILED: ${e.message}")
            onProgress(InstallProgress(Phase.FAILED, e.message ?: "Update failed"))
            throw e
        }
    }

    // ---------------------------------------------------------------- download

    private suspend fun fetchExpectedSha256(distro: Distro, fileName: String, onLog: (String) -> Unit): String? =
        runCatching {
            withContext(Dispatchers.IO) {
                val text = openStream(distro.base.sumsUrl()).bufferedReader().use { it.readText() }
                text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.endsWith(" *$fileName") || it.endsWith("  $fileName") }
                    ?.substringBefore(' ')
            }
        }.onFailure { onLog("could not fetch SHA256SUMS: ${it.message}") }.getOrNull()

    private suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val existing = if (part.isFile) part.length() else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BernsLinuxPorts/1.0")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        conn.connect()
        val resuming = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (conn.responseCode !in 200..299) {
            throw InstallException("Download failed (HTTP ${conn.responseCode}) for $url")
        }
        val contentLength = conn.contentLengthLong
        val total = if (contentLength > 0) contentLength + (if (resuming) existing else 0L) else -1L
        if (!resuming && existing > 0) part.delete()

        var done = if (resuming) existing else 0L
        conn.inputStream.use { input ->
            FileOutputStream(part, resuming).use { out ->
                val buf = ByteArray(256 * 1024)
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 1_000_000) {
                        onProgress(done, total)
                        lastReport = done
                    }
                }
            }
        }
        onProgress(done, total)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw InstallException("Could not finalise the download")
    }

    private fun openStream(url: String): InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BernsLinuxPorts/1.0")
        }
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for $url")
        return conn.inputStream
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- extract

    private suspend fun extractTarGz(archive: File, target: File, onProgress: (Float, Int) -> Unit) {
        val totalCompressed = archive.length().coerceAtLeast(1)
        val counting = CountingInputStream(BufferedInputStream(archive.inputStream(), 256 * 1024))
        var entries = 0

        TarArchiveInputStream(GZIPInputStream(counting, 64 * 1024)).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry as TarArchiveEntry? ?: break
                val name = entry.name.removePrefix("./")
                if (name.isEmpty() || name == ".") continue
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    continue // never let an archive escape the rootfs
                }
                when {
                    entry.isDirectory -> out.mkdirs()

                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        if (out.exists() || Files.isSymbolicLink(out.toPath())) out.delete()
                        runCatching { Files.createSymbolicLink(out.toPath(), File(entry.linkName).toPath()) }
                    }

                    entry.isLink -> {
                        // Hard links become symlinks; proot's --link2symlink puts them back
                        // together for anything inside the container that cares.
                        out.parentFile?.mkdirs()
                        if (out.exists()) out.delete()
                        val linkTarget = File(target, entry.linkName.removePrefix("./"))
                        runCatching { Files.createLink(out.toPath(), linkTarget.toPath()) }
                            .recoverCatching { linkTarget.copyTo(out, overwrite = true) }
                    }

                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> tar.copyTo(fos, 128 * 1024) }
                        val mode = entry.mode
                        out.setReadable(true, false)
                        out.setWritable(true, true)
                        if (mode and 0b001_001_001 != 0) out.setExecutable(true, false)
                    }

                    else -> Unit // character/block devices and fifos: proot binds the real /dev
                }
                entries++
                if (entries % 200 == 0) {
                    onProgress((counting.count.toFloat() / totalCompressed).coerceIn(0f, 1f), entries)
                }
            }
        }
        onProgress(1f, entries)
    }

    private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        var count: Long = 0
            private set

        override fun read(): Int = wrapped.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            wrapped.read(b, off, len).also { if (it > 0) count += it }

        override fun close() = wrapped.close()
        override fun available(): Int = wrapped.available()
    }

    // -------------------------------------------------------------- configure

    private fun configureRootfs(distro: Distro, rootfs: File, onLog: (String) -> Unit) {
        fun write(path: String, content: String, executable: Boolean = false) {
            val f = File(rootfs, path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            if (executable) f.setExecutable(true, false)
        }

        listOf("tmp", "root", "proc", "sys", "dev", "dev/shm", "mnt/android", "var/tmp")
            .forEach { File(rootfs, it).mkdirs() }
        File(rootfs, "tmp").setWritable(true, false)

        write("etc/resolv.conf", dnsServers().joinToString("\n", postfix = "\n") { "nameserver $it" })
        write(
            "etc/hosts",
            """
            127.0.0.1   localhost berns-${distro.key}
            ::1         localhost ip6-localhost ip6-loopback
            """.trimIndent() + "\n"
        )
        write("etc/hostname", "berns-${distro.key}\n")

        val mirror = if (Abi.debianArch == "amd64") {
            "http://archive.ubuntu.com/ubuntu"
        } else {
            "http://ports.ubuntu.com/ubuntu-ports"
        }
        val suite = "noble"
        val components = "main restricted universe multiverse"

        val deb822 = File(rootfs, "etc/apt/sources.list.d/ubuntu.sources")
        if (deb822.isFile) {
            write(
                "etc/apt/sources.list.d/ubuntu.sources",
                """
                Types: deb
                URIs: $mirror
                Suites: $suite $suite-updates $suite-backports $suite-security
                Components: $components
                Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
                """.trimIndent() + "\n"
            )
            write("etc/apt/sources.list", "# see sources.list.d/ubuntu.sources\n")
        } else {
            write(
                "etc/apt/sources.list",
                listOf("$suite", "$suite-updates", "$suite-backports", "$suite-security")
                    .joinToString("\n") { "deb $mirror $it $components" } + "\n"
            )
        }

        // proot binds these over the real /proc entries, which Android does not expose.
        write("proc/.loadavg", "0.12 0.18 0.14 1/728 1024\n")
        write("proc/.stat", buildString {
            appendLine("cpu  1000 0 1000 100000 0 0 0 0 0 0")
            for (i in 0 until Runtime.getRuntime().availableProcessors()) {
                appendLine("cpu$i 100 0 100 10000 0 0 0 0 0 0")
            }
            appendLine("intr 0")
            appendLine("ctxt 0")
            appendLine("btime ${System.currentTimeMillis() / 1000}")
            appendLine("processes 1")
            appendLine("procs_running 1")
            appendLine("procs_blocked 0")
        })
        write("proc/.uptime", "5000.00 10000.00\n")
        write("proc/.version", "Linux version 6.2.1-berns (berns@android) (gcc, GNU ld) #1 SMP\n")
        write("proc/.vmstat", "nr_free_pages 100000\nnr_dirty 0\npgpgin 0\npgpgout 0\n")
        write("proc/.sysctl_entry_cap_last_cap", "40\n")
        write("proc/.sysctl_inotify_max_user_watches", "524288\n")

        // The setup scripts themselves.
        context.assets.open("setup/common.sh").use { input ->
            File(rootfs, "root/berns-common.sh").outputStream().use { input.copyTo(it) }
        }
        context.assets.open(distro.setupScript).use { input ->
            File(rootfs, "root/berns-setup.sh").outputStream().use { input.copyTo(it) }
        }
        File(rootfs, "root/berns-setup.sh").setExecutable(true, false)
        onLog("rootfs configured for $mirror ($suite, ${Abi.debianArch})")
    }

    /**
     * Uses the resolvers Android is actually using, so the container follows the phone
     * onto whatever network (or VPN) it is on, rather than hard-coding a public resolver.
     */
    private fun dnsServers(): List<String> {
        val fallback = listOf("1.1.1.1", "8.8.8.8")
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork ?: return fallback
            val props = cm.getLinkProperties(network) ?: return fallback
            props.dnsServers.mapNotNull { it.hostAddress }
                .filter { !it.contains('%') }
                .ifEmpty { fallback }
        }.getOrDefault(fallback)
    }

    // ------------------------------------------------------------------ setup

    private suspend fun runSetup(distro: Distro, vncPassword: String, onLog: (String) -> Unit) {
        val log = paths.logFile(distro)
        log.parentFile?.mkdirs()
        log.writeText("")

        val exit = ContainerRunner(context).runToLog(
            distro = distro,
            command = listOf("/bin/bash", "/root/berns-setup.sh"),
            extraEnv = mapOf(
                "BERNS_USER" to CONTAINER_USER,
                "BERNS_VNC_PASS" to vncPassword
            )
        ) { line ->
            log.appendText(line + "\n")
            onLog(line)
        }
        if (exit != 0) throw InstallException("The setup script exited with code $exit. See the log for details.")
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }

    private fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1048576.0)

    companion object {
        const val CONTAINER_USER = "berns"
    }
}
