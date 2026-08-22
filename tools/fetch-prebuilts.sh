#!/usr/bin/env bash
#
# Pulls the proot runtime into app/src/main/jniLibs so it ships inside the APK.
#
# Android refuses to execute a file the app can write to, so an executable has to travel
# as a "native library": Android extracts everything under jniLibs into nativeLibraryDir,
# which is read-only and exec-allowed. Filenames therefore have to end in .so even when the
# thing inside is a program or a library with a versioned soname.
#
# Sources: the Termux package repository, which builds proot for Android's bionic libc.
# Everything here is GPL/BSD-licensed; see THIRD_PARTY.md.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI="$ROOT/app/src/main/jniLibs"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"

# android abi : termux arch
ABIS=(
    "arm64-v8a:aarch64"
    "armeabi-v7a:arm"
    "x86_64:x86_64"
)

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v ar >/dev/null || die "'ar' is required (apt install binutils)"
command -v tar >/dev/null || die "'tar' is required"
command -v curl >/dev/null || die "'curl' is required"

# Finds the newest .deb for a package/arch in the pool listing.
# Debian pools group packages by first letter, except lib* which groups by "lib" + the
# following letter: pool/main/p/proot/, pool/main/libt/libtalloc/.
pool_dir() {
    printf '%s' "$1" | sed -E 's/^(lib.).*/\1/; t; s/^(.).*/\1/'
}

latest_deb() {
    local pkg="$1" arch="$2"
    curl -fsSL "${REPO}/pool/main/$(pool_dir "$pkg")/${pkg}/" \
        | grep -oE "${pkg}_[0-9A-Za-z.:~+-]*_${arch}\.deb" \
        | sort -V | tail -n 1
}

extract_deb() {
    local url="$1" dest="$2"
    mkdir -p "$dest"
    curl -fsSL -o "$WORK/pkg.deb" "$url"
    ( cd "$dest" && ar x "$WORK/pkg.deb" && \
      { tar xf data.tar.xz 2>/dev/null || tar xf data.tar.gz 2>/dev/null || tar xf data.tar.bz2; } )
}

for entry in "${ABIS[@]}"; do
    abi="${entry%%:*}"
    arch="${entry##*:}"
    log "fetching the proot runtime for $abi ($arch)"

    out="$JNI/$abi"
    mkdir -p "$out"
    stage="$WORK/$arch"
    rm -rf "$stage"

    for pkg in proot libtalloc libandroid-shmem; do
        file="$(latest_deb "$pkg" "$arch" || true)"
        [ -n "$file" ] || die "could not find $pkg for $arch in $REPO"
        base="$(pool_dir "$pkg")"
        log "  $file"
        extract_deb "${REPO}/pool/main/${base}/${pkg}/${file}" "$stage/$pkg"
    done

    prefix="data/data/com.termux/files/usr"
    cp "$stage/proot/$prefix/bin/proot"                    "$out/libproot.so"
    cp "$stage/proot/$prefix/libexec/proot/loader"          "$out/libproot_loader.so"
    if [ -f "$stage/proot/$prefix/libexec/proot/loader32" ]; then
        cp "$stage/proot/$prefix/libexec/proot/loader32"    "$out/libproot_loader32.so"
    else
        cp "$stage/proot/$prefix/libexec/proot/loader"      "$out/libproot_loader32.so"
    fi
    cp "$(readlink -f "$stage/libtalloc/$prefix/lib/libtalloc.so.2")" "$out/libtalloc.so"
    cp "$stage/libandroid-shmem/$prefix/lib/libandroid-shmem.so"      "$out/libandroid_shmem.so"

    chmod 0755 "$out"/*.so
    log "  -> $(ls "$out" | tr '\n' ' ')"
done

log "done - jniLibs is ready, now build the APK"
