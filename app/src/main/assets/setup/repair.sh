#!/bin/bash
# Berns Linux Ports - maintenance pass over a container that already exists.
#
# Setup scripts run once, at install time. This re-applies the current version's fixes to
# an installed container so a bug fix does not cost the user another full reinstall.
set -u
. /root/berns-common.sh

step "Updating an existing container"

# A package left half-configured by an earlier run blocks every install below it.
dpkg --configure -a >/dev/null 2>&1 || true

prepare_apt
install_browser || warn "continuing without a web browser"

# Re-apply the desktop launcher and VNC configuration: these are cheap and idempotent,
# and they pick up whatever the current version of the app fixed.
install_vnc
configure_vnc

finish_setup
