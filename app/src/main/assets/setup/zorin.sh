#!/bin/bash
# Berns Linux Ports - Zorin OS 17 Lite style (Xfce)
set -u
. /root/berns-common.sh

prepare_apt
install_base
install_desktop
install_vnc
create_user

step "Installing the Zorin desktop look"
# Zorin's apt repository publishes no package index for ARM (and none for x86 either at
# the time of writing), so the themes come from Zorin's own GPL sources when they can be
# reached. If they cannot, the port falls back to a Zorin-styled Xfce built entirely from
# the Ubuntu archive - same layout and palette, stock components.
ZORIN_OK=0
ZORIN_TARBALLS="
https://codeload.github.com/ZorinOS/zorin-desktop-themes/tar.gz/refs/heads/master|themes
https://codeload.github.com/ZorinOS/zorin-icon-themes/tar.gz/refs/heads/master|icons
"

for entry in $ZORIN_TARBALLS; do
    url="${entry%%|*}"
    kind="${entry##*|}"
    tmp="/tmp/zorin-$kind.tar.gz"
    if curl -fsSL --max-time 120 -o "$tmp" "$url" 2>/dev/null; then
        mkdir -p "/tmp/zorin-$kind"
        if tar xzf "$tmp" -C "/tmp/zorin-$kind" --strip-components=1 2>/dev/null; then
            src="/tmp/zorin-$kind"
            [ -d "$src/$kind" ] && src="$src/$kind"
            [ -d "$src/usr/share/$kind" ] && src="$src/usr/share/$kind"
            if cp -a "$src"/Zorin* "/usr/share/$kind/" 2>/dev/null; then
                info "installed the official Zorin $kind"
                ZORIN_OK=1
            fi
        fi
        rm -rf "$tmp" "/tmp/zorin-$kind"
    fi
done

if [ "$ZORIN_OK" = "0" ]; then
    warn "Zorin's theme sources are unreachable; building the Zorin look from Ubuntu packages"
    install_optional arc-theme papirus-icon-theme numix-gtk-theme
fi

GTK_THEME=""
for t in ZorinBlue-Dark ZorinBlue-Light Zorin-Dark Arc-Dark Adwaita-dark; do
    [ -d "/usr/share/themes/$t" ] && GTK_THEME="$t" && break
done
[ -n "$GTK_THEME" ] || GTK_THEME="Adwaita-dark"

ICON_THEME=""
for i in ZorinBlue-Dark ZorinBlue Zorin Papirus-Dark Papirus Adwaita; do
    [ -d "/usr/share/icons/$i" ] && ICON_THEME="$i" && break
done
[ -n "$ICON_THEME" ] || ICON_THEME="Adwaita"

step "Applying the Zorin panel layout"
# Zorin OS Lite puts a single taskbar along the bottom with the menu on the left.
XFCONF="/home/$BERNS_USER/.config/xfce4/xfconf/xfce-perchannel-xml"
mkdir -p "$XFCONF"
cat > "$XFCONF/xfce4-panel.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
    <property name="panel-1" type="empty">
      <property name="position" type="string" value="p=8;x=0;y=0"/>
      <property name="length" type="uint" value="100"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="40"/>
      <property name="plugin-ids" type="array">
        <value type="int" value="1"/>
        <value type="int" value="2"/>
        <value type="int" value="3"/>
        <value type="int" value="4"/>
        <value type="int" value="5"/>
      </property>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-1" type="string" value="applicationsmenu">
      <property name="button-title" type="string" value="Start"/>
      <property name="show-button-title" type="bool" value="true"/>
    </property>
    <property name="plugin-2" type="string" value="tasklist"/>
    <property name="plugin-3" type="string" value="separator">
      <property name="expand" type="bool" value="true"/>
      <property name="style" type="uint" value="0"/>
    </property>
    <property name="plugin-4" type="string" value="systray"/>
    <property name="plugin-5" type="string" value="clock">
      <property name="digital-format" type="string" value="%H:%M"/>
    </property>
  </property>
</channel>
EOF
chown -R "$BERNS_USER:$BERNS_USER" "/home/$BERNS_USER/.config"

WALLPAPER="$(find /usr/share/backgrounds -maxdepth 2 -type f \( -name '*.png' -o -name '*.jpg' \) 2>/dev/null | head -n 1)"
apply_xfce_theme "$GTK_THEME" "$ICON_THEME" "$GTK_THEME" "$WALLPAPER"
configure_vnc
brand_release "zorin" "Zorin OS 17 Lite style (Xfce)" "ubuntu" "https://zorin.com/os/"
finish_setup
