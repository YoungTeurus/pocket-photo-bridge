#!/usr/bin/env bash
# Install a downloaded native Linux portable bundle; no compilation or JDK.
set -euo pipefail
umask 077
version=0.5.0
application=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
base=/opt/pocket-photo-bridge
state=/var/lib/pocket-photo-bridge
unit=pocket-photo-bridge.service
stage=
release=
old_target=
was_active=false
was_enabled=false
switched=false
unit_installed=false
previous_unit=
completed=false

[[ $EUID -eq 0 ]] || { printf '%s\n' 'Run this installer with sudo on the dedicated bridge board.' >&2; exit 1; }
[[ -t 1 ]] || {
    printf '%s\n' 'Use an interactive terminal (Windows SSH: ssh -t). Bootstrap credentials must stay local.' >&2; exit 1;
}
[[ -f "$application/lib/bridge-$version.jar" && -x "$application/runtime/bin/java" ]] || {
    printf '%s\n' 'Run the installer from the extracted Linux portable bundle.' >&2; exit 1;
}
case "$(uname -m)" in x86_64|aarch64|arm64) ;; *) printf '%s\n' 'The board must run a supported 64-bit Linux OS.' >&2; exit 1 ;; esac
[[ -d /run/systemd/system ]] || { printf '%s\n' 'An active systemd installation is required.' >&2; exit 1; }
for directory in "$base" "$base/releases" "$state"; do
    if [[ -L $directory || ( -e $directory && ! -d $directory ) ]]; then
        printf '%s\n' 'An installation or state path is unsafe; installation stopped.' >&2; exit 1
    fi
    if [[ -d $directory && $(stat -c %u "$directory") != 0 ]]; then
        printf '%s\n' 'Existing installation and state directories must be root owned.' >&2; exit 1
    fi
    if [[ -d $directory ]]; then
        permissions=$(stat -c %a "$directory")
        if (( (8#$permissions & 8#022) != 0 )); then
            printf '%s\n' 'Existing installation and state directories must not be writable by other users.' >&2; exit 1
        fi
    fi
done
if [[ -e "$base/current" && ! -L "$base/current" ]]; then
    printf '%s\n' 'The current release path must be a managed symlink.' >&2; exit 1
fi
if [[ -L "$base/current" ]]; then
    old_target=$(readlink -f -- "$base/current")
    [[ $old_target == "$base/releases/"* && -d $old_target ]] || {
        printf '%s\n' 'The current release points outside the managed releases directory.' >&2; exit 1;
    }
fi
if [[ -L "/etc/systemd/system/$unit" || ( -e "/etc/systemd/system/$unit" && ! -f "/etc/systemd/system/$unit" ) ]]; then
    printf '%s\n' 'The existing systemd unit path is unsafe; installation stopped.' >&2; exit 1
fi
# Validate native architecture before making changes. Uses the bundled runtime.
"$application/runtime/bin/java" -version
command -v apt-get >/dev/null || { printf '%s\n' 'This pilot installer requires Debian/Ubuntu apt-get.' >&2; exit 1; }
apt-get update
apt-get install --no-install-recommends -y network-manager wpasupplicant dnsmasq-base nftables bluez libbluetooth3 iproute2 libfreetype6 fontconfig
for executable in nmcli wpa_cli readlink bluetoothctl sdptool ip dnsmasq nft; do
    command -v "$executable" >/dev/null || { printf '%s\n' 'A required NetworkManager/BlueZ executable is missing.' >&2; exit 1; }
done
install -d -o root -g root -m 0755 "$base" "$base/releases"
install -d -o root -g root -m 0700 "$state"
install -d -o root -g root -m 0700 /etc/NetworkManager/system-connections
stage=$(mktemp -d "$base/releases/.staging.XXXXXXXX")

cleanup() {
    local result=$?
    trap - EXIT
    if [[ $completed != true ]]; then
        if [[ $switched == true ]]; then
            systemctl stop "$unit" || true
            if [[ -n $old_target ]]; then
                rm -f -- "$base/.rollback-current"
                ln -s -- "$old_target" "$base/.rollback-current"
                mv -Tf -- "$base/.rollback-current" "$base/current"
            else
                rm -f -- "$base/current"
            fi
        fi
        if [[ $unit_installed == true ]]; then
            if [[ $was_enabled == false ]]; then systemctl disable "$unit" || true; fi
            if [[ -n $previous_unit ]]; then
                install -o root -g root -m 0644 "$previous_unit" "/etc/systemd/system/$unit"
            else
                rm -f -- "/etc/systemd/system/$unit"
            fi
            systemctl daemon-reload || true
        fi
        if [[ $was_active == true ]]; then systemctl start "$unit" || true; fi
        printf '%s\n' 'Installation stopped; previous service/release restored. Persistent state was retained.' >&2
    fi
    rm -f -- "$base/.next-current"
    [[ -z $previous_unit ]] || rm -f -- "$previous_unit"
    [[ -z $stage || ! -d $stage ]] || rm -rf -- "$stage"
    exit "$result"
}
trap cleanup EXIT
cp -a -- "$application/." "$stage/"
chown -hR root:root "$stage"
chmod -R go-w "$stage"
chmod 0755 "$stage/bin/bridge"
release="$base/releases/$version-${stage##*.}"
mv -- "$stage" "$release"
stage=
# Stop before opening state; durable queue and uncertainty records stay intact.
if [[ -f "/etc/systemd/system/$unit" ]]; then
    previous_unit=$(mktemp "$base/.previous-unit.XXXXXXXX")
    cp -- "/etc/systemd/system/$unit" "$previous_unit"
fi
if systemctl is-enabled --quiet "$unit"; then was_enabled=true; fi
if systemctl is-active --quiet "$unit"; then
    was_active=true
    systemctl stop "$unit"
fi
"$release/bin/bridge" --make-bootstrap "--state-dir=$state"
unit_installed=true
install -o root -g root -m 0644 "$release/deploy/linux/pocket-photo-bridge.service" "/etc/systemd/system/$unit"
rm -f -- "$base/.next-current"
ln -s -- "$release" "$base/.next-current"
switched=true
mv -Tf -- "$base/.next-current" "$base/current"
systemctl daemon-reload
systemctl enable NetworkManager.service bluetooth.service
systemctl start NetworkManager.service bluetooth.service
systemctl enable "$unit"
systemctl start "$unit"
systemctl is-active --quiet "$unit"
completed=true
printf '%s\n' 'Pocket Photo Bridge 0.5.0 installed. Keep the displayed credentials private.'
printf '%s\n' 'Unconfigured boots stay in setup. Configured boots offer setup for 60 seconds.'
printf '%s\n' 'Connect only to the dedicated PocketPhotoBridge hotspot and open http://192.168.50.1:8080/.'
