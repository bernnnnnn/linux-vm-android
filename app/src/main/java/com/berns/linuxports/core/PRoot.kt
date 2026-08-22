package com.berns.linuxports.core

import android.content.Context
import com.berns.linuxports.model.Distro
import java.io.File
import java.nio.file.Files

/**
 * Builds proot invocations.
 *
 * proot gives an unprivileged Android process a chroot-like view of a Linux rootfs by
 * intercepting syscalls with ptrace: path translation plus a fake uid 0. No root, no
 * kernel modules, and no KVM required.
 *
 * The binary itself ships in jniLibs. That is deliberate: since Android 10 an app may not
 * exec a file it can write to, and nativeLibraryDir is the one directory that is both
 * app-private and executable. proot links against libtalloc.so.2 and libandroid-shmem.so,
 * whose sonames are not legal library file names inside an APK, so they travel as
 * libtalloc.so / libandroid_shmem.so and get symlinks with their real sonames pointing back
 * at the read-only originals.
 */
class PRoot(context: Context) {
    private val paths = Paths(context)

    val isAvailable: Boolean get() = paths.prootBin.isFile

    fun ensureShims() {
        paths.ensureDirs()
        link("libtalloc.so.2", "libtalloc.so")
        link("libandroid-shmem.so", "libandroid_shmem.so")
    }

    private fun link(soname: String, packagedAs: String) {
        val target = File(paths.nativeDir, packagedAs)
        if (!target.isFile) return
        val link = File(paths.shimDir, soname)
        if (link.exists() || Files.isSymbolicLink(link.toPath())) {
            if (Files.isSymbolicLink(link.toPath()) &&
                Files.readSymbolicLink(link.toPath()).toString() == target.absolutePath
            ) return
            link.delete()
        }
        runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }
    }

    /** Environment for the proot process itself (the Android side of the fence). */
    fun hostEnv(): Map<String, String> = mapOf(
        "PROOT_LOADER" to paths.prootLoader.absolutePath,
        "PROOT_LOADER_32" to paths.prootLoader32.absolutePath,
        "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
        "LD_LIBRARY_PATH" to "${paths.shimDir.absolutePath}:${paths.nativeDir.absolutePath}",
        "PATH" to "/system/bin:/system/xbin",
        "HOME" to paths.files.absolutePath,
        "TMPDIR" to paths.tmpDir.absolutePath,
        "ANDROID_DATA" to "/data",
        "ANDROID_ROOT" to "/system"
    )

    /**
     * @param command what to run inside the container, already split into argv.
     * @param user the container user to become; null means stay root.
     */
    fun command(
        distro: Distro,
        command: List<String>,
        user: String? = null,
        workdir: String? = null,
        term: String = "xterm-256color",
        extraEnv: Map<String, String> = emptyMap()
    ): List<String> {
        val rootfs = paths.rootfs(distro).absolutePath
        val home = if (user == null || user == "root") "/root" else "/home/$user"
        val args = mutableListOf(
            paths.prootBin.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--ashmem-memfd",
            "--kernel-release=6.2.1",
            "-r", rootfs,
            "-0"
        )

        // Android's own filesystems, then the small lies that make /proc look Linux-ish.
        args += listOf("-b", "/dev", "-b", "/proc", "-b", "/sys")
        args += listOf("-b", "/dev/urandom:/dev/random")
        args += listOf("-b", "/proc/self/fd:/dev/fd")
        args += listOf("-b", "/proc/self/fd/0:/dev/stdin")
        args += listOf("-b", "/proc/self/fd/1:/dev/stdout")
        args += listOf("-b", "/proc/self/fd/2:/dev/stderr")
        args += listOf("-b", "$rootfs/tmp:/dev/shm")
        for ((fake, real) in FAKE_PROC) {
            if (File("$rootfs$fake").isFile) args += listOf("-b", "$rootfs$fake:$real")
        }
        args += listOf("-b", "${paths.sharedDir.absolutePath}:/mnt/android")

        args += listOf("-w", workdir ?: home)

        // env -i so the container never inherits Android's environment by accident.
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=$home",
            "USER=${user ?: "root"}",
            "LOGNAME=${user ?: "root"}",
            "TERM=$term",
            "LANG=C.UTF-8",
            "SHELL=/bin/bash",
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "DEBIAN_FRONTEND=noninteractive",
            "BERNS_DISTRO=${distro.key}"
        )
        extraEnv.forEach { (k, v) -> args += "$k=$v" }

        if (user != null && user != "root") {
            // su - keeps the login environment; proot's fake root makes it password-free.
            args += listOf("/bin/su", "-l", user, "-c", command.joinToString(" ") { shellQuote(it) })
        } else {
            args += command
        }
        return args
    }

    /** Runs one short command and returns its combined output. */
    fun capture(distro: Distro, command: List<String>): String {
        val pb = ProcessBuilder(this.command(distro, command))
        pb.environment().putAll(hostEnv())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    companion object {
        val FAKE_PROC = listOf(
            "/proc/.loadavg" to "/proc/loadavg",
            "/proc/.stat" to "/proc/stat",
            "/proc/.uptime" to "/proc/uptime",
            "/proc/.version" to "/proc/version",
            "/proc/.vmstat" to "/proc/vmstat",
            "/proc/.sysctl_entry_cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
            "/proc/.sysctl_inotify_max_user_watches" to "/proc/sys/fs/inotify/max_user_watches"
        )

        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
