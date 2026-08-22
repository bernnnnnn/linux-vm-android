#!/bin/bash
# Berns Linux Ports - Linux Mint 22 (Xfce edition, Mint-Y)
set -u
. /root/berns-common.sh

MINT_REPO="http://packages.linuxmint.com"

prepare_apt
install_base
install_desktop
install_vnc
create_user

step "Installing the Mint artwork"
# Mint publishes its themes only for x86, but they are "Architecture: all" packages:
# the same files work everywhere. Grab them straight from the Mint pool.
MINT_CODENAME=""
for candidate in zara xia wilma; do
    if curl -fsI "$MINT_REPO/dists/$candidate/main/binary-amd64/Packages.gz" >/dev/null 2>&1; then
        MINT_CODENAME="$candidate"
        info "using the Mint '$candidate' pool"
        break
    fi
done

MINT_OK=0
if [ -n "$MINT_CODENAME" ]; then
    if install_all_deb "$MINT_REPO" "$MINT_CODENAME" "main" \
        mint-x-icons mint-y-icons mint-themes; then
        MINT_OK=1
    fi
    WALLPAPER_PKG="$(newest_all_package "$MINT_REPO" "$MINT_CODENAME" "main" "mint-backgrounds-")"
    if [ -n "$WALLPAPER_PKG" ]; then
        info "wallpapers: $WALLPAPER_PKG"
        install_all_deb "$MINT_REPO" "$MINT_CODENAME" "main" "$WALLPAPER_PKG" || \
            warn "the Mint wallpapers did not install; keeping the Xfce default"
    else
        warn "no Mint wallpaper package found; keeping the Xfce default"
    fi
else
    warn "the Mint pool is unreachable; using an equivalent green Xfce look"
fi

# Mint-Y needs the Murrine engine and Adwaita's cursors, both already installed above.
GTK_THEME="Mint-Y-Dark-Aqua"
for t in Mint-Y-Dark-Aqua Mint-Y-Dark Mint-Y Adwaita-dark; do
    [ -d "/usr/share/themes/$t" ] && GTK_THEME="$t" && break
done
ICON_THEME="Mint-Y-Aqua"
for i in Mint-Y-Aqua Mint-Y Mint-X Adwaita; do
    [ -d "/usr/share/icons/$i" ] && ICON_THEME="$i" && break
done
[ "$MINT_OK" = "1" ] || install_optional greybird-gtk-theme

WALLPAPER="$(find /usr/share/backgrounds/linuxmint* -maxdepth 2 -type f \( -name '*.jpg' -o -name '*.png' \) 2>/dev/null | head -n 1)"
[ -n "$WALLPAPER" ] || WALLPAPER="$(find /usr/share/backgrounds -maxdepth 2 -type f \( -name '*.png' -o -name '*.jpg' \) 2>/dev/null | head -n 1)"

apply_xfce_theme "$GTK_THEME" "$ICON_THEME" "$GTK_THEME" "$WALLPAPER"
configure_vnc
brand_release "linuxmint" "Linux Mint 22 (Xfce)" "ubuntu" "https://linuxmint.com"
finish_setup
