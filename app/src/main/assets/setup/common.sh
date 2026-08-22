#!/bin/bash
# Shared helpers for the Berns Linux Ports first-boot setup.
# Runs inside the container as (fake) root, under proot.

set -u

BERNS_USER="${BERNS_USER:-berns}"
BERNS_VNC_PASS="${BERNS_VNC_PASS:-bernsvnc}"
BERNS_DISPLAY="${BERNS_DISPLAY:-1}"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m!!  %s\033[0m\n' "$*" >&2; exit 1; }

apt_get() {
    apt-get -o Acquire::Retries=3 -o Dpkg::Use-Pty=false "$@"
}

install_pkgs() {
    apt_get install -y --no-install-recommends "$@" || die "could not install: $*"
}

# Best effort: a missing optional package should not fail the whole install.
install_optional() {
    for p in "$@"; do
        if ! apt_get install -y --no-install-recommends "$p" >/dev/null 2>&1; then
            warn "optional package unavailable: $p"
        else
            info "installed $p"
        fi
    done
}

prepare_apt() {
    step "Preparing apt"
    # apt drops privileges to the _apt user for downloads, which cannot work when the
    # whole container is a ptrace sandbox with a faked uid 0.
    cat > /etc/apt/apt.conf.d/99berns <<'EOF'
APT::Sandbox::User "root";
Dpkg::Use-Pty "false";
Acquire::Languages "none";
APT::Install-Recommends "false";
EOF
    # The stock Ubuntu base image ships no man pages or docs anyway; keep it that way.
    cat > /etc/dpkg/dpkg.cfg.d/99berns <<'EOF'
path-exclude=/usr/share/doc/*
path-exclude=/usr/share/man/*
path-exclude=/usr/share/groff/*
path-exclude=/usr/share/info/*
EOF
    neutralise_init
    apt_get update || die "apt-get update failed - check the network and try again"
}

# Nothing in the container runs services - there is no init - and systemd's maintainer
# scripts abort under proot's ptrace sandbox. A failed postinst leaves dpkg half
# configured, which then blocks every later install, so the pieces those scripts reach for
# are stubbed out and packages are told not to start anything. Idempotent: safe to re-run
# over a container that already exists.
neutralise_init() {
    printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
    chmod +x /usr/sbin/policy-rc.d

    if [ ! -s /etc/machine-id ]; then
        (cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s%N) | tr -d '-\n' > /etc/machine-id
    fi

    local tool
    for tool in systemctl systemd-sysusers systemd-tmpfiles systemd-machine-id-setup \
                systemd-detect-virt systemd-hwdb udevadm; do
        if [ ! -L "/usr/bin/$tool" ]; then
            dpkg-divert --local --rename --add "/usr/bin/$tool" >/dev/null 2>&1
            ln -sf /bin/true "/usr/bin/$tool"
        fi
    done
}

install_base() {
    step "Installing base system"
    install_pkgs ca-certificates curl wget gnupg locales sudo nano less \
        tzdata xz-utils bzip2 unzip procps psmisc file git python3
    locale-gen en_US.UTF-8 >/dev/null 2>&1 || true
    update-locale LANG=en_US.UTF-8 >/dev/null 2>&1 || true
}

install_desktop() {
    step "Installing the Xfce desktop"
    install_pkgs xfce4-session xfce4-panel xfce4-settings xfdesktop4 xfwm4 \
        thunar xfce4-terminal xfce4-appfinder xfconf \
        dbus-x11 x11-xserver-utils xdg-utils \
        fonts-dejavu-core fonts-liberation2 \
        librsvg2-common gtk2-engines-murrine
    install_optional mousepad ristretto xarchiver galculator \
        xfce4-notifyd xfce4-screenshooter thunar-archive-plugin
}

# Ubuntu ships Firefox only as a snap, and snapd needs systemd and mount namespaces that a
# container has no way to provide. Mozilla publishes genuine .deb builds for arm64 and
# amd64, so the browser comes from there.
#
# Chromium is snap-only too, and everything built on WebKitGTK (Epiphany, Midori) launches
# its renderer through bubblewrap, which needs user namespaces proot cannot grant - those
# fail with "Input/output error" no matter how they are installed.
MOZILLA_REPO="https://packages.mozilla.org/apt"

install_browser() {
    step "Installing the web browser"
    local update_log="/tmp/berns-apt-mozilla.log"

    # Ubuntu's "firefox" package is a stub that installs the snap, and it depends on snapd
    # and systemd. systemd's post-install aborts inside a container and leaves dpkg
    # half-configured, which then blocks every later install. Make that stub uninstallable
    # before apt is given any chance to reach for it.
    cat > /etc/apt/preferences.d/berns-firefox <<'EOF'
Package: firefox firefox-* snapd
Pin: release o=Ubuntu
Pin-Priority: -1

Package: *
Pin: origin packages.mozilla.org
Pin-Priority: 1000
EOF

    install -d -m 0755 /etc/apt/keyrings
    if curl -fsSL "$MOZILLA_REPO/repo-signing-key.gpg" \
            | gpg --dearmor > /etc/apt/keyrings/packages.mozilla.org.gpg 2>/dev/null \
            && [ -s /etc/apt/keyrings/packages.mozilla.org.gpg ]; then
        echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.gpg] $MOZILLA_REPO mozilla main" \
            > /etc/apt/sources.list.d/mozilla.list
    else
        warn "could not fetch Mozilla's signing key"
    fi

    apt_get update > "$update_log" 2>&1 || true
    if grep -qE 'NO_PUBKEY|is not signed' "$update_log"; then
        warn "Mozilla's repository did not verify; fetching the package directly instead"
        rm -f /etc/apt/sources.list.d/mozilla.list
        apt_get update >/dev/null 2>&1 || true
    else
        apt_get install -y --no-install-recommends firefox >/dev/null 2>&1 || true
    fi

    browser_works || install_firefox_deb || true

    if ! browser_works; then
        warn "no working web browser could be installed"
        return 1
    fi
    info "installed $(firefox --version 2>/dev/null | head -1)"
    harden_browser
    set_default_browser
}

# A binary on PATH is not proof of a working browser: a package that unpacked but never
# configured leaves the executable in place with its libraries missing.
browser_works() {
    command -v firefox >/dev/null 2>&1 && firefox --version >/dev/null 2>&1
}

# Fallback when the apt signature chain is unavailable: resolve the package through
# Mozilla's own index over HTTPS and check the .deb against the SHA-256 recorded there.
install_firefox_deb() {
    local arch index path sha deb
    arch="$(dpkg --print-architecture)"
    index="/tmp/berns-mozilla-$arch.txt"
    curl -fsSL "$MOZILLA_REPO/dists/mozilla/main/binary-$arch/Packages" -o "$index" || return 1

    path="$(awk '/^Package: firefox$/ {f=1} f && /^Filename: / {print $2; exit}' "$index")"
    sha="$(awk '/^Package: firefox$/ {f=1} f && /^SHA256: / {print $2; exit}' "$index")"
    [ -n "$path" ] || { warn "firefox is not listed for $arch"; return 1; }

    deb="/tmp/firefox_${arch}.deb"
    info "fetching firefox for $arch"
    curl -fsSL "$MOZILLA_REPO/$path" -o "$deb" || return 1
    if [ -n "$sha" ] && ! echo "$sha  $deb" | sha256sum -c - >/dev/null 2>&1; then
        warn "the downloaded firefox package failed its checksum"
        rm -f "$deb"
        return 1
    fi

    # Installed through apt rather than dpkg so the GTK stack the browser links against
    # comes with it. "dpkg -i" on its own leaves the package unpacked and unusable.
    apt_get install -y --no-install-recommends "$deb" >/dev/null 2>&1 || \
        apt_get -f install -y >/dev/null 2>&1 || true
    rm -f "$deb" "$index"
    browser_works
}

# Firefox renders pages in content processes that install a seccomp-bpf filter. proot
# intercepts syscalls with ptrace, and the two collide: the content process is killed and
# the browser reports "Gah. Your tab just crashed." on every page it opens.
#
# There are no user namespaces in here to sandbox with in the first place - that is the
# same limitation that rules WebKitGTK out entirely - so the content sandbox is switched
# off. The container is the boundary; the browser is not being asked to be one too.
harden_browser() {
    local real installdir prefdir desktop candidate

    # command -v is the reliable answer here; readlink and test -x can both misreport
    # inside the sandbox, and an early return would leave the browser unconfigured.
    real="$(command -v firefox 2>/dev/null || true)"
    case "$real" in /usr/local/bin/*) real="" ;; esac

    installdir=""
    for candidate in /usr/lib/firefox /opt/firefox /usr/lib64/firefox; do
        if [ -e "$candidate/firefox" ]; then
            installdir="$candidate"
            break
        fi
    done
    [ -n "$installdir" ] && real="$installdir/firefox"
    if [ -z "$real" ]; then
        warn "cannot locate the firefox binary to configure"
        return 1
    fi
    [ -n "$installdir" ] || installdir="$(dirname "$real")"

    # A system pref applies however Firefox is started, including from the applications
    # menu, which goes through no wrapper of ours.
    prefdir="$installdir/browser/defaults/preferences"
    mkdir -p "$prefdir"
    cat > "$prefdir/berns.js" <<'EOF'
// Berns Linux Ports: proot gives no user namespaces, so the content sandbox cannot work
// and its failure takes every tab with it. The container is the security boundary here.
pref("security.sandbox.content.level", 0);

// A phone has far less memory than these defaults assume, and every content process is
// another copy of the engine. One is enough here.
pref("dom.ipc.processCount", 1);
pref("browser.tabs.remote.autostart", true);

// The X server behind this desktop is a software framebuffer with no GL at all. Asking
// for acceleration gets a GPU process that fails, and it takes tabs down with it.
pref("gfx.webrender.software", true);
pref("gfx.canvas.accelerated", false);
pref("layers.acceleration.disabled", true);
pref("media.hardware-video-decoding.enabled", false);
pref("gfx.x11-egl.force-disabled", true);
EOF

    cat > /usr/local/bin/firefox <<EOF
#!/bin/sh
# Berns Linux Ports launcher - see $prefdir/berns.js
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_ENABLE_WAYLAND=0
export LIBGL_ALWAYS_SOFTWARE=1
export MOZ_ACCELERATED=0
exec $real "\$@"
EOF
    chmod +x /usr/local/bin/firefox

    # Menu entries use "Exec=firefox", which finds the wrapper through PATH, but some use
    # an absolute path. Rewrite those so every launch route agrees.
    for desktop in /usr/share/applications/firefox.desktop \
                   /usr/local/share/applications/firefox.desktop; do
        [ -f "$desktop" ] || continue
        sed -i -E 's|^Exec=(/usr/bin/firefox\|/usr/lib/firefox/firefox\|/opt/firefox/firefox)|Exec=/usr/local/bin/firefox|' "$desktop"
    done
    update-desktop-database /usr/share/applications >/dev/null 2>&1 || true

    install_browser_check
    info "configured firefox for a software-rendered container"
}

# A one-command answer to "why did my tab crash", runnable from the app's terminal.
install_browser_check() {
    cat > /usr/local/bin/berns-browser-check <<'EOF'
#!/bin/sh
# Renders a local page in a real content process and reports what happened.
echo "berns browser check"
echo "-------------------"
command -v firefox >/dev/null 2>&1 || { echo "firefox: NOT INSTALLED"; exit 1; }
echo "version: $(firefox --version 2>&1 | head -1)"
cat > /tmp/berns-probe.html <<'HTML'
<html><body><h1>probe</h1></body></html>
HTML
rm -f /tmp/berns-probe.png
timeout 120 firefox --headless --screenshot /tmp/berns-probe.png     file:///tmp/berns-probe.html > /tmp/berns-probe.log 2>&1
status=$?
if [ -s /tmp/berns-probe.png ]; then
    echo "render: OK ($(wc -c < /tmp/berns-probe.png) bytes) - content processes work"
else
    echo "render: FAILED (exit $status) - this is the tab crash"
    echo "--- last 20 lines ---"
    tail -20 /tmp/berns-probe.log
fi
echo "memory: $(free -m 2>/dev/null | awk '/^Mem:/{print $2" MB total, "$7" MB available"}')"
EOF
    chmod +x /usr/local/bin/berns-browser-check
}

# Xfce asks exo which browser to launch, and exo answers from a helper definition rather
# than from the freedesktop defaults. Without one it reports "Failed to execute default
# Web Browser", which is what an unconfigured container does.
set_default_browser() {
    local bin path home
    bin=firefox
    path="$(command -v "$bin")"
    home="/home/$BERNS_USER"

    update-alternatives --install /usr/bin/x-www-browser x-www-browser "$path" 200 >/dev/null 2>&1
    update-alternatives --set x-www-browser "$path" >/dev/null 2>&1
    update-alternatives --install /usr/bin/gnome-www-browser gnome-www-browser "$path" 200 >/dev/null 2>&1

    mkdir -p /usr/share/xfce4/helpers
    cat > /usr/share/xfce4/helpers/berns-browser.desktop <<EOF
[Desktop Entry]
Version=1.0
Encoding=UTF-8
Type=X-XFCE-Helper
X-XFCE-Category=WebBrowser
X-XFCE-CommandsWithParameter=$path "%s"
X-XFCE-Commands=$path
Icon=$bin
Name=Firefox
StartupNotify=false
EOF

    mkdir -p "$home/.config/xfce4"
    if [ -f "$home/.config/xfce4/helpers.rc" ] && grep -q '^WebBrowser=' "$home/.config/xfce4/helpers.rc"; then
        sed -i 's|^WebBrowser=.*|WebBrowser=berns-browser|' "$home/.config/xfce4/helpers.rc"
    else
        echo "WebBrowser=berns-browser" >> "$home/.config/xfce4/helpers.rc"
    fi

    cat > "$home/.config/mimeapps.list" <<'EOF'
[Default Applications]
text/html=firefox.desktop
x-scheme-handler/http=firefox.desktop
x-scheme-handler/https=firefox.desktop
x-scheme-handler/about=firefox.desktop
EOF
    chown -R "$BERNS_USER:$BERNS_USER" "$home/.config"
    info "set Firefox as the default browser"
}

install_vnc() {
    step "Installing the VNC server"
    # Ubuntu 24.04 splits the password tool out into tigervnc-tools; without it there is
    # no vncpasswd and Xvnc has nothing to authenticate against.
    install_pkgs tigervnc-standalone-server tigervnc-common tigervnc-tools
}

create_user() {
    step "Creating the '$BERNS_USER' account"
    if ! id -u "$BERNS_USER" >/dev/null 2>&1; then
        useradd -m -s /bin/bash "$BERNS_USER"
    fi
    # proot already grants fake root, so sudo never needs to ask for anything.
    usermod -aG sudo,audio,video "$BERNS_USER" 2>/dev/null || true
    passwd -d "$BERNS_USER" >/dev/null 2>&1 || true
    echo "$BERNS_USER ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/berns
    chmod 0440 /etc/sudoers.d/berns
}

configure_vnc() {
    step "Configuring the VNC session"
    local home="/home/$BERNS_USER"
    mkdir -p "$home/.vnc"

    local vncpasswd_bin=""
    for candidate in vncpasswd tigervncpasswd; do
        if command -v "$candidate" >/dev/null 2>&1; then
            vncpasswd_bin="$candidate"
            break
        fi
    done
    [ -n "$vncpasswd_bin" ] || die "no vncpasswd tool found - tigervnc-tools did not install"
    printf '%s\n' "$BERNS_VNC_PASS" | "$vncpasswd_bin" -f > "$home/.vnc/passwd"
    [ -s "$home/.vnc/passwd" ] || die "could not write the VNC password file"
    chmod 600 "$home/.vnc/passwd"

    cat > "$home/.vnc/xstartup" <<'EOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export XDG_RUNTIME_DIR="/tmp/runtime-$(id -un)"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
export XDG_CURRENT_DESKTOP=XFCE
export XDG_SESSION_TYPE=x11
[ -r "$HOME/.Xresources" ] && xrdb "$HOME/.Xresources"
exec dbus-launch --exit-with-session xfce4-session
EOF
    chmod +x "$home/.vnc/xstartup"

    cat > "$home/.Xresources" <<'EOF'
Xft.dpi: 120
Xft.antialias: true
Xft.hinting: true
Xft.hintstyle: hintslight
Xft.rgba: rgb
EOF

    chown -R "$BERNS_USER:$BERNS_USER" "$home/.vnc" "$home/.Xresources"

    command -v Xvnc >/dev/null 2>&1 || die "Xvnc is missing - the VNC server did not install"

    # The launcher the app calls. Xvnc is started directly rather than through the
    # vncserver wrapper, which expects a session manager the container does not have.
    cat > /usr/local/bin/berns-desktop <<'EOF'
#!/bin/bash
# usage: berns-desktop [display] [geometry] [dpi]
set -u
DISPLAY_NUM="${1:-1}"
GEOMETRY="${2:-1280x720}"
DPI="${3:-120}"
PORT=$((5900 + DISPLAY_NUM))
USER_NAME="$(id -un)"
HOME_DIR="$HOME"

rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null
mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix
export XDG_RUNTIME_DIR="/tmp/runtime-$USER_NAME"
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"

echo "berns: starting Xvnc on :$DISPLAY_NUM ($GEOMETRY) port $PORT"
Xvnc ":$DISPLAY_NUM" \
    -geometry "$GEOMETRY" \
    -depth 24 \
    -dpi "$DPI" \
    -rfbport "$PORT" \
    -rfbauth "$HOME_DIR/.vnc/passwd" \
    -localhost \
    -SecurityTypes VncAuth \
    -AlwaysShared \
    -desktop "Berns Linux Ports" &
XVNC_PID=$!

# Give the X server a moment to create its socket before the session connects.
for _ in $(seq 1 40); do
    [ -e "/tmp/.X11-unix/X${DISPLAY_NUM}" ] && break
    sleep 0.25
done

export DISPLAY=":$DISPLAY_NUM"
echo "berns: starting the desktop session"
"$HOME_DIR/.vnc/xstartup" > "$HOME_DIR/.vnc/session.log" 2>&1 &
SESSION_PID=$!

trap 'kill $SESSION_PID $XVNC_PID 2>/dev/null' TERM INT
wait $XVNC_PID
EOF
    chmod +x /usr/local/bin/berns-desktop
}

# Downloads an "Architecture: all" package straight from a vendor pool and installs it.
#
# Mint and Zorin build their themes as architecture-independent packages, but their apt
# repositories only publish an index for x86. Resolving the file name through the amd64
# index and installing the .deb directly gets the genuine artwork onto an ARM device
# without pointing apt at a repository that has nothing else it can use.
#
# usage: install_all_deb <base-url> <dist> <component> <package> [package...]
install_all_deb() {
    local base="$1" dist="$2" comp="$3"
    shift 3
    local index="/tmp/berns-index-${dist}-${comp}.txt"

    if [ ! -s "$index" ]; then
        if ! curl -fsSL "$base/dists/$dist/$comp/binary-amd64/Packages.gz" | gunzip > "$index" 2>/dev/null; then
            warn "cannot read the $dist/$comp package index at $base"
            return 1
        fi
    fi

    local pkg path url out rc=0
    for pkg in "$@"; do
        path="$(awk -v want="$pkg" '
            /^Package: /   { p = $2 }
            /^Architecture: / { a = $2 }
            /^Filename: /  { if (p == want && a == "all") { print $2; exit } }
        ' "$index")"
        if [ -z "$path" ]; then
            warn "$pkg not found in $dist/$comp"
            rc=1
            continue
        fi
        url="$base/$path"
        out="/tmp/$(basename "$path")"
        info "fetching $pkg"
        if curl -fsSL -o "$out" "$url"; then
            dpkg -i "$out" >/dev/null 2>&1 || apt_get -f install -y >/dev/null 2>&1 || warn "$pkg did not install cleanly"
            rm -f "$out"
        else
            warn "download failed: $url"
            rc=1
        fi
    done
    return $rc
}

# Prints the newest "Architecture: all" package whose name starts with a prefix.
# Mint's wallpaper packages are named after the release they debuted in, not the suite
# they are served from, so the name has to be discovered rather than guessed.
#
# usage: newest_all_package <base-url> <dist> <component> <prefix>
newest_all_package() {
    local base="$1" dist="$2" comp="$3" prefix="$4"
    local index="/tmp/berns-index-${dist}-${comp}.txt"
    if [ ! -s "$index" ]; then
        curl -fsSL "$base/dists/$dist/$comp/binary-amd64/Packages.gz" | gunzip > "$index" 2>/dev/null || return 1
    fi
    awk -v pfx="$prefix" '
        /^Package: /      { p = $2 }
        /^Architecture: all/ { if (index(p, pfx) == 1) print p }
    ' "$index" | sort -u | tail -n 1
}

# Writes the Xfce defaults (theme, icons, wallpaper, font) for the container user.
# usage: apply_xfce_theme <gtk-theme> <icon-theme> <wm-theme> <wallpaper-path-or-empty>
apply_xfce_theme() {
    local gtk="$1" icons="$2" wm="$3" wallpaper="${4:-}"
    local home="/home/$BERNS_USER"
    local dir="$home/.config/xfce4/xfconf/xfce-perchannel-xml"
    mkdir -p "$dir"

    cat > "$dir/xsettings.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="$gtk"/>
    <property name="IconThemeName" type="string" value="$icons"/>
    <property name="EnableEventSounds" type="bool" value="false"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="FontName" type="string" value="Sans 10"/>
    <property name="MonospaceFontName" type="string" value="Monospace 10"/>
    <property name="CursorThemeName" type="string" value="Adwaita"/>
  </property>
  <property name="Xft" type="empty">
    <property name="DPI" type="int" value="120"/>
    <property name="Antialias" type="int" value="1"/>
    <property name="HintStyle" type="string" value="hintslight"/>
    <property name="RGBA" type="string" value="rgb"/>
  </property>
</channel>
EOF

    cat > "$dir/xfwm4.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="$wm"/>
    <property name="title_font" type="string" value="Sans Bold 10"/>
    <property name="use_compositing" type="bool" value="false"/>
    <property name="workspace_count" type="int" value="2"/>
  </property>
</channel>
EOF

    if [ -n "$wallpaper" ] && [ -e "$wallpaper" ]; then
        cat > "$dir/xfce4-desktop.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-desktop" version="1.0">
  <property name="backdrop" type="empty">
    <property name="screen0" type="empty">
      <property name="monitorVNC-0" type="empty">
        <property name="workspace0" type="empty">
          <property name="last-image" type="string" value="$wallpaper"/>
          <property name="image-style" type="int" value="5"/>
        </property>
      </property>
      <property name="monitor0" type="empty">
        <property name="workspace0" type="empty">
          <property name="last-image" type="string" value="$wallpaper"/>
          <property name="image-style" type="int" value="5"/>
        </property>
      </property>
    </property>
  </property>
</channel>
EOF
    fi

    chown -R "$BERNS_USER:$BERNS_USER" "$home/.config"
}

# A tiny /etc/os-release-style banner so the container announces which port it is.
brand_release() {
    local name="$1" pretty="$2" base_family="$3" home_url="$4"
    cat > /etc/berns-release <<EOF
BERNS_PORT_NAME="$name"
BERNS_PORT_PRETTY="$pretty"
BERNS_BASE="Ubuntu 24.04 LTS (noble), $base_family family"
BERNS_HOME_URL="$home_url"
EOF
    cat > /etc/motd <<EOF

  $pretty
  Berns Linux Ports for Android - running on an Ubuntu 24.04 base via proot

  Desktop:  berns-desktop            Shared files:  /mnt/android
  Docs:     cat /etc/berns-release   Distro home:   $home_url

EOF
}

finish_setup() {
    step "Cleaning up"
    apt_get autoremove -y >/dev/null 2>&1 || true
    apt_get clean >/dev/null 2>&1 || true
    rm -rf /var/lib/apt/lists/* /tmp/berns-index-*.txt
    step "Setup complete"
}
