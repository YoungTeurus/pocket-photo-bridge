# Linux radio and protocol adapters

The JVM service uses the existing Sony `SonyTransferSession` and Xiaomi
`PrinterSession`. The Linux layer supplies Wi-Fi attachment, source-bound HTTP
and a classic RFCOMM byte stream. It does not contain a second camera or printer
protocol implementation. Linux AP support, pairing, SDP discovery, reconnect and
physical printing remain **pending Orange Pi validation**.

## NetworkManager and private credentials

`LinuxRadioManager` uses absolute `/usr/bin/nmcli` commands through a bounded
process runner. Commands have fixed operations and deadlines, use no shell and
have a small deterministic environment. Output is limited to 64 KiB and never
copied to diagnostics. SSIDs are validated as 1–32 UTF-8 bytes; interface names,
WPA2 passwords and Bluetooth addresses have strict format checks. No password or
printer token is passed in process arguments or environment variables.

NetworkManager's keyfile loader only accepts its configured connection directories,
so temporary profiles are written to `/run/NetworkManager/system-connections`.
Their adapter-owned filenames use a digest of the state location, with opaque
UUIDs; the directory and target must not be symlinks or writable by other users.
Atomic writes create the keyfile with 0600 permissions before writing any secret.
The pilot service runs as root and removes its profiles during cleanup. SSIDs use
keyfile byte-array syntax, and WPA2 passwords use keyfile escaping. Keyfiles remain
secret even if a command fails. NetworkManager itself has administrative access
to these files and credentials, as required for association.

Setup uses WPA2 AP mode and the explicit `192.168.50.1/24` address. Camera mode uses
DHCP, `autoconnect=false`, `never-default=true`, ignores automatic DNS, and disables
IPv6. Setup is stopped before camera activation; camera links close before setup
activation. Before either activation, an explicit device disconnect clears any
stale connection left by an earlier process crash. Presence checks request a
fresh scan on the selected interface.

The camera endpoint comes from `IP4.GATEWAY` or the DHCP `routers` option of that
specific device. Only a single private gateway and source address in the same IPv4
subnet are eligible. No global default route, guessed camera address, DNS or proxy
is used. `ip -j -4 route get <gateway> from <source>` must confirm the actual
direct route through that interface. The check does not force an output interface;
forcing one could hide a conflicting route that the source-bound socket would use. The adapter rechecks configuration and route
before each HTTP request. The existing pure JVM `BoundCameraHttpTransport` binds
the socket to the selected source address, bounds HTTP framing/deadlines and
restricts all camera URLs to the selected private IPv4 endpoint. Its current
package name includes `windows`; using it here does not invoke PowerShell or
Windows networking discovery.

## BlueZ pairing, SDP and native RFCOMM

Pairing uses independently installed `/usr/bin/bluetoothctl` in noninteractive
mode. Already paired devices are trusted without another pairing attempt. New
devices first receive a bounded 10-second classic Bluetooth scan to create the
BlueZ device proxy, followed by a fixed `NoInputNoOutput` agent and a 30-second
pairing tool timeout. The pairing process
permits the asynchronous pairing callback and then verifies the resulting state; a piped `pair` immediately followed by `quit`
would exit before that asynchronous callback. A separate `info` command must
report `Paired: yes`, and a separate bounded command then trusts the device. The
address and device name may be displayed only in the authenticated local UI;
diagnostics contain fixed stage/categories and numeric native errors.

Each printer connection discovers the channel again using exactly:

```text
/usr/bin/sdptool search --bdaddr <validated-address> SP
```

This targets the selected remote address and the Serial Port service UUID 0x1101.
The bounded parser requires exactly one matching record and one RFCOMM UUID
0x0003 descriptor with a channel from 1 through 30. Missing, ambiguous, malformed
or out-of-range channel data fails closed. There is no assumed channel 1. Raw SDP
output and remote service names never become diagnostic messages. A changed SDP
channel is rediscovered on reconnect.

The JVM dynamically calls libc through the existing JNA dependency. It does not
load or link `libbluetooth`. Linux x64 and ARM64 use the LP64 ABI: socket handles
are signed 32-bit descriptors; `socklen_t` is 32 bits; `size_t` and `ssize_t` are
native longs. `sockaddr_rc` is 10 bytes: a native 16-bit address family at offset 0,
a six-byte Bluetooth address at offset 2 in least-significant-byte-first order,
the discovered channel at offset 8 and one trailing padding byte. `pollfd` is
8 bytes, with the descriptor at offset 0 and 16-bit events/revents at offsets 4/6.

Sockets use AF_BLUETOOTH (31), SOCK_STREAM, SOCK_NONBLOCK, SOCK_CLOEXEC and
BTPROTO_RFCOMM (3). EINPROGRESS/EALREADY/EAGAIN connections wait for poll readiness
and then check SO_ERROR; readiness alone does not establish connection success.
Errno is saved immediately after each failed native call, before diagnostic
callbacks or cleanup. Partial sends, would-block retries and reads share one
operation deadline. `MSG_NOSIGNAL` suppresses process signals from a disconnected
peer. Polling uses at most 100-ms slices for cancellation. Shutdown wakes native
operations, and descriptor leasing prevents close/reuse while a call is active.
A timed-out partial write closes the stream, so another frame cannot resume it.
An ordinary read timeout permits a later read; EOF and native errors close it.
The printer token is supplied only to the shared printer session.

`LinuxBluetooth.nativeSelfTest()` checks struct bytes, native widths, and libc
error mapping using **invalid descriptor -1**. It does not create a Bluetooth
socket, scan, pair, connect or submit a print job. It can run without radios.

## Primary sources and licensing

These interfaces were researched from primary upstream documentation/source;
fixtures and Kotlin implementation are independently written. No BlueZ or
NetworkManager implementation source is copied into the repository.

- [NetworkManager 1.46.2 nmcli manual](https://github.com/NetworkManager/NetworkManager/blob/1.46.2/man/nmcli.xml):
  bounded `--wait`, terse escaping, `connection load`, `up`, `down`, device Wi-Fi
  scan and device-specific property selection. The `nm-settings-keyfile(5)` manual
  installed with NetworkManager documents byte-array SSIDs and protected keyfiles.
- [NetworkManager keyfile plugin](https://github.com/NetworkManager/NetworkManager/blob/1.46.2/src/core/settings/plugins/keyfile/nms-keyfile-plugin.c):
  `_path_detect_storage_type` restricts loaded profile paths to configured
  `/run`, `/etc` and library system-connection directories.
- [BlueZ 5.79 sdptool](https://github.com/bluez/bluez/blob/5.79/tools/sdptool.c):
  `search_options`, `search_help`, `cmd_search`, service mapping for `SP`, record
  handle/class/protocol display, and the RFCOMM `Channel:` field establish the
  selected-address command and parser grammar.
- [BlueZ RFCOMM ABI header](https://github.com/bluez/bluez/blob/5.79/lib/rfcomm.h)
  and [Bluetooth ABI header](https://github.com/bluez/bluez/blob/5.79/lib/bluetooth.h):
  address layout and Linux Bluetooth constants. These are cited for interface
  facts, not copied as code or bundled headers.
- [glibc socket header](https://sourceware.org/git/?p=glibc.git;a=blob;f=sysdeps/unix/sysv/linux/bits/socket.h)
  and [poll header](https://sourceware.org/git/?p=glibc.git;a=blob;f=sysdeps/unix/sysv/linux/bits/poll.h):
  POSIX/Linux socket/poll ABI; installed `socket(2)`, `connect(2)`, `poll(2)`,
  `getsockopt(2)`, `send(2)` and `recv(2)` manuals establish errno semantics.

BlueZ 5.64, 5.66 and 5.79 `lib/sdp.c` and Bluetooth headers carry
`GPL-2.0-or-later` SPDX identifiers, despite BlueZ also retaining `COPYING.LIB`.
Consequently the bridge avoids direct SDP library linkage. `bluetoothctl` and
`sdptool` are ordinary independently installed OS executables communicating over
process boundaries. The bridge distribution does not bundle BlueZ binaries or
source. Their licenses and any `libbluetooth3` dependency are supplied by the OS
package manager. libc and JNA retain their own licenses; existing JNA notices are
already distributed with the bridge.

Required host tools are NetworkManager (`nmcli`), BlueZ (`bluetoothctl`, `sdptool`)
and iproute2 (`/usr/sbin/ip`). Some distributions omit deprecated `sdptool`; that
is an unsupported/incomplete host installation, not a reason to guess a channel.

## Verification limits

Automated tests exercise private profile permissions and escaping, command
bounds/redaction, mode ordering, route rejection, SDP ambiguity, native errno
preservation, structure bytes, pending connection checks, partial writes,
timeouts, cancellation, descriptor lifecycle and reconnect. They use synthetic
tool output and injected native backends. The native self-test verifies libc
mapping in the cloud host without radio access.

On the Orange Pi, verify AP driver support and credentials; the setup listener at
192.168.50.1; hotspot teardown before camera join; DHCP route discovery with
`never-default`; Sony transfer using the selected source; actual pairing and SDP
SPP channel; printer-off/on reconnect; interrupted receive; printer completion;
and power-order/reboot retention. A successful cloud self-test or a prior Windows
or Android physical print does not establish Linux radio or physical-print
success.
