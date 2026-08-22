package com.berns.linuxports.core

import android.os.Build

/** Maps the phone's ABI onto the Debian/Ubuntu architecture name used for rootfs images. */
object Abi {
    val androidAbi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    val debianArch: String = when {
        androidAbi.startsWith("arm64") -> "arm64"
        androidAbi.startsWith("armeabi") -> "armhf"
        androidAbi == "x86_64" -> "amd64"
        else -> "arm64"
    }

    val isSupported: Boolean get() = debianArch in setOf("arm64", "armhf", "amd64")

    /** 64-bit devices get the full desktop; 32-bit ARM is tight but works headless. */
    val is64Bit: Boolean get() = debianArch == "arm64" || debianArch == "amd64"
}
