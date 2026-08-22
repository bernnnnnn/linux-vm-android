package com.berns.linuxports.core

import android.content.Context
import com.berns.linuxports.model.Distro
import java.io.File

/**
 * Everything lives under the app's internal storage. That is the only place where an
 * unprivileged Android app may create the symlinks, device nodes stand-ins and 700-mode
 * directories a Linux rootfs expects.
 */
class Paths(context: Context) {
    private val app: Context = context.applicationContext

    val files: File = app.filesDir
    val nativeDir: File = File(app.applicationInfo.nativeLibraryDir)
    val distrosDir = File(files, "distros")
    val sharedDir = File(files, "shared")
    val shimDir = File(files, "shim")
    val tmpDir = File(files, "tmp")
    val downloads = File(app.cacheDir, "downloads")

    val prootBin = File(nativeDir, "libproot.so")
    val prootLoader = File(nativeDir, "libproot_loader.so")
    val prootLoader32 = File(nativeDir, "libproot_loader32.so")

    fun distroDir(distro: Distro) = File(distrosDir, distro.key)
    fun rootfs(distro: Distro) = File(distroDir(distro), "rootfs")
    fun stampFile(distro: Distro) = File(distroDir(distro), "installed")
    fun logFile(distro: Distro) = File(distroDir(distro), "setup.log")

    fun isInstalled(distro: Distro) = stampFile(distro).isFile

    fun ensureDirs() {
        listOf(distrosDir, sharedDir, shimDir, tmpDir, downloads).forEach { it.mkdirs() }
    }

    /** Free space on the volume that holds the rootfs, in MiB. */
    fun freeSpaceMb(): Long = files.usableSpace / (1024L * 1024L)
}
