# Third-party components

## Bundled in the APK

Fetched by `tools/fetch-prebuilts.sh` from the [Termux package
repository](https://packages.termux.dev/) and packaged under `jniLibs`:

| Component | Upstream | Licence | Packaged as |
|---|---|---|---|
| proot | https://github.com/termux/proot | GPL-2.0-or-later | `libproot.so`, `libproot_loader.so`, `libproot_loader32.so` |
| talloc | https://talloc.samba.org/ | LGPL-3.0-or-later | `libtalloc.so` |
| libandroid-shmem | https://github.com/termux/libandroid-shmem | MIT | `libandroid_shmem.so` |

These are unmodified binaries, renamed only so that Android will extract them into the
executable `nativeLibraryDir`. Source for each is available from the upstream links above,
as required by their licences.

## Downloaded at install time, onto the user's device

Nothing below is redistributed by this project; the app fetches it from the vendor's own
servers when the user installs a port.

| Source | What | Verified |
|---|---|---|
| `cdimage.ubuntu.com` | `ubuntu-base` root filesystem | SHA-256 against Canonical's published `SHA256SUMS` |
| `archive.ubuntu.com` / `ports.ubuntu.com` | every package `apt` installs | apt's own GPG signature chain |
| `packages.linuxmint.com` | Mint themes, icons and wallpapers (`Architecture: all`) | resolved through Mint's `Packages` index |
| Zorin's published theme sources | Zorin desktop and icon themes | falls back to Ubuntu-archive packages when unreachable |

## Trademarks

Linux Mint, Ubuntu and Zorin OS are trademarks of Clement Lefebvre, Canonical Ltd. and
Zorin Technology Group Ltd. respectively. This project is an independent hobby build and is
not affiliated with, endorsed by, or supported by any of them.
