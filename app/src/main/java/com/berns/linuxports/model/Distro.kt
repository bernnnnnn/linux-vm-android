package com.berns.linuxports.model

/**
 * The three ports Berns ships.
 *
 * All three run from an Ubuntu base rootfs of the matching CPU architecture. Mint and
 * Zorin do not publish ARM rootfs images of their own, so their ports are built the way
 * both distributions are actually built: an Ubuntu base plus the distribution's own
 * desktop, theme and branding packages, which are architecture independent.
 */
enum class DistroId { MINT, UBUNTU, ZORIN }

data class Distro(
    val id: DistroId,
    val key: String,
    val name: String,
    val edition: String,
    val tagline: String,
    val accent: Long,
    val onAccent: Long,
    val setupScript: String,
    val desktop: String,
    /** Rough on-disk size once the desktop is installed, for the "do you have room" check. */
    val installedSizeMb: Int,
    val base: UbuntuBase
) {
    val displayName: String get() = "$name $edition"
}

data class UbuntuBase(val release: String, val point: String) {
    /** ubuntu-base tarballs are published per architecture under the same release path. */
    fun url(arch: String): String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$release/release/ubuntu-base-$point-base-$arch.tar.gz"

    fun sumsUrl(): String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$release/release/SHA256SUMS"

    fun fileName(arch: String): String = "ubuntu-base-$point-base-$arch.tar.gz"
}

object Distros {
    private val noble = UbuntuBase(release = "24.04", point = "24.04.4")

    val MINT = Distro(
        id = DistroId.MINT,
        key = "mint",
        name = "Linux Mint",
        edition = "22 · Xfce",
        tagline = "Mint-Y, Mint themes and the Mint tooling on top of a Noble base.",
        accent = 0xFF6FBD44,
        onAccent = 0xFF0B1A06,
        setupScript = "setup/mint.sh",
        desktop = "Xfce + Mint-Y",
        installedSizeMb = 2600,
        base = noble
    )

    val UBUNTU = Distro(
        id = DistroId.UBUNTU,
        key = "ubuntu",
        name = "Ubuntu",
        edition = "24.04 LTS · Xfce",
        tagline = "The stock Noble base with the Xubuntu desktop and Yaru theming.",
        accent = 0xFFE95420,
        onAccent = 0xFF2C001E,
        setupScript = "setup/ubuntu.sh",
        desktop = "Xfce + Yaru",
        installedSizeMb = 2400,
        base = noble
    )

    val ZORIN = Distro(
        id = DistroId.ZORIN,
        key = "zorin",
        name = "Zorin OS",
        edition = "17 · Lite",
        tagline = "Zorin's desktop themes and icons over Noble, in the Lite (Xfce) layout.",
        accent = 0xFF0E9BD9,
        onAccent = 0xFF06131F,
        setupScript = "setup/zorin.sh",
        desktop = "Xfce + Zorin themes",
        installedSizeMb = 2700,
        base = noble
    )

    val all = listOf(MINT, UBUNTU, ZORIN)

    fun byKey(key: String): Distro = all.first { it.key == key }
}
