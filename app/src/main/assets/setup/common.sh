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
    apt_get update || die "apt-get update failed - check the network and try again"
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
        xfce4-notifyd xfce4-screenshooter thunar-archive-plugin \
        epiphany-browser
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
