#!/bin/bash
# Berns Linux Ports - Ubuntu 24.04 LTS (Xfce)
set -u
. /root/berns-common.sh

prepare_apt
install_base
install_desktop
install_vnc
install_browser || warn "continuing without a web browser"
create_user

step "Applying Ubuntu branding"
# Yaru is Ubuntu's own theme and ships as architecture-independent packages in universe,
# so this is the genuine article straight from the Ubuntu archive.
install_optional yaru-theme-gtk yaru-theme-icon yaru-theme-sound ubuntu-wallpapers

WALLPAPER=""
for candidate in \
    /usr/share/backgrounds/warty-final-ubuntu.png \
    /usr/share/backgrounds/ubuntu-default-greyscale-wallpaper.png; do
    [ -e "$candidate" ] && WALLPAPER="$candidate" && break
done
if [ -z "$WALLPAPER" ]; then
    WALLPAPER="$(find /usr/share/backgrounds -maxdepth 2 -type f \( -name '*.png' -o -name '*.jpg' \) 2>/dev/null | head -n 1)"
fi

GTK_THEME="Yaru-dark"
[ -d /usr/share/themes/Yaru-dark ] || GTK_THEME="Adwaita-dark"
ICON_THEME="Yaru"
[ -d /usr/share/icons/Yaru ] || ICON_THEME="Adwaita"

apply_xfce_theme "$GTK_THEME" "$ICON_THEME" "$GTK_THEME" "$WALLPAPER"
configure_vnc
brand_release "ubuntu" "Ubuntu 24.04 LTS (Xfce)" "debian" "https://ubuntu.com"
finish_setup
