# Berns Linux Ports for Android

Three Linux desktops — **Linux Mint**, **Ubuntu** and **Zorin OS** — running on an Android
phone, in one APK. Install a port, wait while it builds itself, then open a full Xfce
desktop or drop straight into a shell.

<p align="center"><em>No root. No KVM. No emulation. Native speed on your own CPU.</em></p>

---

## What this actually is

Each "port" is a real Ubuntu 24.04 LTS root filesystem with a real desktop installed into
it, running under [proot](https://proot-me.github.io/). proot uses `ptrace` to rewrite
filesystem paths and fake a uid of 0, which gives an ordinary unprivileged Android app a
chroot-like container — the same technique Termux's `proot-distro` and UserLAnd use.

The app then talks to that container two ways:

| | |
|---|---|
| **Desktop** | The container runs `Xvnc` plus an Xfce session on loopback. The app has its own RFB (VNC) client built in, so the desktop is drawn directly in the app — nothing else to install. |
| **Terminal** | A real pseudo-terminal (`forkpty` via JNI) attached to `bash -l`, with a VT100/xterm emulator and a scrolling key bar for the keys a touchscreen doesn't have. |

**It is a container, not a virtual machine.** Nothing is emulated, so there is no separate
kernel, no `systemd`, and hardware-level things (kernel modules, `mount`, Docker, VirtualBox)
do not work. In exchange, everything runs at full native CPU speed instead of the ~20x
slow-down of `qemu-system` on a phone. For running a Linux desktop and Linux software,
that is the trade worth making.

## The three ports

All three are built from the **Ubuntu base rootfs for your phone's own architecture**
(`arm64`, `armhf` or `amd64`), verified against Canonical's published `SHA256SUMS`, and then
finished by a per-distribution setup script.

| Port | What you get | Where the artwork comes from |
|---|---|---|
| **Linux Mint 22 · Xfce** | Xfce with Mint-Y, Mint-X icons and the Mint wallpapers | Fetched from `packages.linuxmint.com` |
| **Ubuntu 24.04 LTS · Xfce** | Xfce with the Yaru theme and Ubuntu wallpapers | The Ubuntu archive itself |
| **Zorin OS 17 · Lite style** | Xfce in Zorin's Lite layout — single bottom taskbar, Zorin blue | Zorin's own GPL theme sources, with a fallback |

### Why Mint and Zorin sit on an Ubuntu base

Neither Linux Mint nor Zorin OS publishes an ARM root filesystem — both are x86-only
distributions, and phones are ARM. But both **are** Ubuntu underneath, and the parts that
make them look and feel like themselves (themes, icon sets, wallpapers, panel layout) are
`Architecture: all` packages that work anywhere.

So the ports are assembled the way the distributions themselves are: Ubuntu base plus that
distribution's desktop layer. `install_all_deb` in
[`common.sh`](app/src/main/assets/setup/common.sh) resolves the genuine package through the
vendor's own `Packages` index and installs the real `.deb`.

Two honest caveats:

- **Mint** ships Cinnamon on x86; the port uses Xfce, which is what the Mint Xfce edition
  uses too, with the genuine Mint-Y theme on top.
- **Zorin's** apt repository currently publishes no package index at all — not for ARM and
  not for x86. The Zorin script first tries Zorin's own GPL theme sources, and if those
  cannot be reached it builds the same look out of packages from the Ubuntu archive. The
  build log says which of the two happened. This one is *Zorin-styled* rather than
  bit-for-bit Zorin, and the app doesn't pretend otherwise.

## Building it

Requirements: JDK 17+, the Android SDK (platform 35, build-tools 35, NDK 27), and `curl`,
`ar` and `tar` on the build machine.

```bash
# 1. Pull the proot runtime into jniLibs (not committed - it is third-party binary)
./tools/fetch-prebuilts.sh

# 2. Build
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # unsigned release build
```

`fetch-prebuilts.sh` downloads `proot`, `libtalloc` and `libandroid-shmem` from the Termux
package repository for all three ABIs and stages them as `lib*.so`. That renaming is not
cosmetic: since Android 10 an app may not `exec()` a file it can write to, and
`nativeLibraryDir` — where Android unpacks `jniLibs` — is the only app-private directory
that is both read-only and executable. `PRoot.ensureShims()` then symlinks the real sonames
(`libtalloc.so.2`, `libandroid-shmem.so`) back at those files so the dynamic linker is happy.

CI builds a debug APK on every push; grab it from the workflow artifacts.

### Updating without losing your container

Installing a port takes a long download and a real `apt` install, so updates must never
cost you that. The rootfs lives in the app's private data directory, which Android keeps
across an in-place update — but only when the new APK carries the *same signature*. A debug
build normally uses a per-machine throwaway key, so a rebuild elsewhere would be treated as
a different app and force an uninstall.

`keystore/berns-debug.keystore` is therefore committed and pinned in `app/build.gradle.kts`,
so every build anywhere signs identically and installs straight over the last one. It is the
stock Android debug keystore with its published password — not a secret, and not to be used
for a release build.

## Using it

1. Pick a port and press **Install**. It downloads ~30 MB of base image, then runs a real
   `apt` install of the desktop — budget several hundred MB and 10–30 minutes on the first
   run, on Wi-Fi. The build log streams live and survives leaving the screen.
2. Press **Open desktop**. The first launch is the slow one while Xfce builds its caches.
3. Drive the desktop with whichever pointer mode suits you — the button in the toolbar
   cycles between them, and the one next to it cycles pointer speed:

   | Mode | How it works |
   |---|---|
   | **Trackpad** (default) | Drag anywhere to move the cursor relatively, like a laptop touchpad. Tap to click. Best on a phone, because your finger never covers the thing you are aiming at. |
   | **Touch** | The cursor jumps to wherever you touch, and touching presses the left button. Direct, but your fingertip hides the target. |
   | **Stick** | An on-screen thumbstick in the corner steers the cursor continuously. Slowest, but the most precise for small targets. |

   Two fingers scroll when the screen is fit to the view, and pan once you pinch-zoom in.
   The bar along the bottom has the mouse buttons, a latching **DRAG** toggle for moving
   windows, scroll, and Ctrl/Alt/Esc/Tab/arrows.
4. **Open terminal** gives you `bash` as the `berns` user, with passwordless `sudo`.
5. **Update container** on the port's page re-applies the current version's setup to a
   container you already installed — it is how a fix reaches an existing install without
   costing you the whole download again.

Files in `/mnt/android` inside the container are shared with the app's own storage, so it's
the place to move things in and out.

Screen size is configurable per port (1024x600 up to 1920x1080) — pick something close to
your phone's aspect ratio and let the pinch-zoom do the rest.

## How it fits together

```
MainActivity ─ Compose UI ─┬─ HomeScreen / DistroScreen      install, progress, settings
                           ├─ TerminalScreen                 VT100 emulator on a real pty
                           └─ DesktopScreen                  RFB client, touch → pointer
                                    │
core/ ─┬─ Installer      download → verify → untar → configure → run setup script
       ├─ PRoot          builds the proot argv, /proc fakes, soname shims
       ├─ Sessions       live terminal + desktop sessions, outliving the Activity
       └─ Paths / Abi    filesystem layout, ABI → Debian architecture
                                    │
term/  ─┬─ Pty.kt + cpp/pty.c       forkpty(), the one thing that needs native code
       └─ Terminal.kt               screen buffer, escape-sequence parser, xterm palette
vnc/   ─── RfbClient.kt             RFB 3.8, VncAuth, Raw/CopyRect/RRE/Hextile
service/ ─ VmService                foreground service so sessions survive backgrounding
assets/setup/ ─ common.sh + one script per port
```

## What has been verified, and what hasn't

Being straight about this, because "it builds" and "it works" are different claims.

**Verified:**

- The APK builds clean for `arm64-v8a`, `armeabi-v7a` and `x86_64`, native layer included,
  and ships the proot runtime unstripped and byte-identical to what `fetch-prebuilts.sh`
  staged.
- The whole install path was run for real against an Ubuntu 24.04 root filesystem: apt
  configuration, the desktop and VNC package sets, user creation, and the Mint artwork
  fetched live from `packages.linuxmint.com` — genuine Mint-Y-Dark-Aqua, Mint-Y-Aqua icons
  and a real Mint wallpaper, all resolved through the vendor index.
- `berns-desktop` starts Xvnc and a working Xfce session (`xfce4-session`, `xfconfd`,
  `xfce4-panel` and its plugins all come up).
- The RFB conversation was replayed byte for byte against that running Xvnc: version
  handshake, VncAuth with the bit-reversed DES key, the exact pixel format and encoding
  list this client sends, 18 Hextile rectangles decoded without desync, and pointer and
  key events accepted.

That run found two real bugs, both fixed: Ubuntu 24.04 moved `vncpasswd` into
`tigervnc-tools` (so the password file was silently never written), and Mint's wallpaper
package is named after an older release than the suite serving it.

**Not verified:** none of this has run on an actual Android device. proot's ptrace sandbox,
the `jniLibs` exec path, the JNI pty and the Compose UI were all built against the
documented behaviour but never executed on a phone. Expect the first run on real hardware
to need a fix or two — that is where the remaining risk lives.

## The web browser

Ubuntu ships Firefox only as a snap, and snapd needs systemd and mount namespaces a
container cannot provide. Worse, Ubuntu's `firefox` package is a stub that pulls in snapd
and systemd, and systemd's post-install aborts under proot and leaves `dpkg` half
configured — which then blocks every later install. The setup pins that stub to
`Pin-Priority: -1` so apt can never reach for it.

The browser therefore comes from **Mozilla's own apt repository**, which publishes genuine
`.deb` builds for arm64 and amd64, verified through their signing key. If that signature
chain cannot be established, the setup falls back to fetching the package straight from
Mozilla's index over HTTPS and checking it against the SHA-256 recorded there, rather than
silently installing nothing.

Chromium is snap-only on Ubuntu too. Anything built on WebKitGTK — Epiphany, Midori —
launches its renderer through bubblewrap, which needs user namespaces proot cannot grant;
those fail with `Input/output error` however they are installed. Firefox is the one that
works.

## Why the container has no init

There is no systemd inside a port, and packages are told so explicitly. `policy-rc.d`
refuses every service start, and the handful of tools systemd's maintainer scripts reach
for (`systemctl`, `systemd-sysusers`, `systemd-tmpfiles`, `udevadm`) are diverted to
`/bin/true`.

That is not tidiness. Those scripts abort under proot's ptrace sandbox, and a failed
`postinst` leaves `dpkg` half configured — which silently blocks every install after it.
The symptom is a package that appears to install and then does not work, which is exactly
how the missing browser presented.

## Known limits

- Anything needing a real kernel — `mount`, kernel modules, Docker, nested virtualisation —
  will not work. This is a container.
- No audio. VNC carries pixels, not sound.
- No hardware GPU acceleration; the desktop is software-rendered, so pick a modest
  resolution on older phones.
- Android may kill a background session under memory pressure despite the foreground
  service. Big builds are happier with the screen on.
- 32-bit ARM devices work but are tight — the desktop needs roughly 2.5 GB of free storage.

## Third-party components

See [THIRD_PARTY.md](THIRD_PARTY.md). Short version: proot (GPL-2.0), talloc (LGPL-3.0),
libandroid-shmem (MIT), and the distributions' own packages, downloaded at install time
from each vendor's servers. Linux Mint, Ubuntu and Zorin OS are trademarks of their
respective owners; this project is not affiliated with or endorsed by any of them.
