# Pocket Photo Bridge 0.5.0: service simulation and Linux pilot

The portable downloads contain the application and Java 17 runtime. You do not
need a JDK, Gradle, Android SDK, or a compiler on Windows or the bridge board.
Keep the complete extracted folder together, including `runtime`, `lib`/`app`,
`licenses`, and `deploy`. Android and the existing `WindowsLive.cmd` manual
hardware workflow remain available.

Linux radio support is a pilot. Earlier 0.5.0 builds printed two single photos
on the Orange Pi Zero 2W; [the Linux field protocol](linux-field-protocol.md)
records what is and is not validated there. The Windows simulation proves controller and
setup behavior using synthetic devices; it cannot access radios or print paper. Use a board-supported 64-bit Debian/Ubuntu image.
An Armbian Community image is not certified for this bridge; an image's name or
successful boot does not establish working Wi-Fi AP/Bluetooth support.

## Download and run the Windows simulation

1. In this repository's **Actions → Headless bridge**, open a successful 0.5.0
   run and download `pocket-photo-bridge-windows-x64-0.5.0`.
2. Extract GitHub's outer artifact ZIP. It contains
   `PocketPhotoBridge-0.5.0-windows-x64.zip` and its `.zip.sha256` file. In
   PowerShell, compare the two SHA256 values before extracting the inner ZIP:

   ```powershell
   Get-FileHash .\PocketPhotoBridge-0.5.0-windows-x64.zip -Algorithm SHA256
   Get-Content .\PocketPhotoBridge-0.5.0-windows-x64.zip.sha256
   Expand-Archive .\PocketPhotoBridge-0.5.0-windows-x64.zip .\bridge-0.5.0
   ```

3. Open the extracted `PocketPhotoBridge` folder and double-click
   `WindowsServiceSimulation.cmd`. Keep its console open. It displays locally
   generated setup credentials and starts the service at
   `http://127.0.0.1:8080/`. The administrator password is unique to this private
   simulation state. Open that URL and sign in with the displayed password.
4. Keep the prefilled synthetic camera/printer configuration; do not enter real
   credentials in the simulation. Click **Start operation**. Apply **printer off**,
   then **select photo**, and refresh until `queued` is 1. Apply **printer on** and
   refresh until `completed` is 1. Apply **duplicate photo** and verify `prints`
   stays 1. You can similarly queue the second photo with **printer busy**, then
   apply **printer ready** to complete it.
5. Stop with **Ctrl+C** and restart the same launcher to inspect retained state.
   State lives under `%LOCALAPPDATA%\PocketPhotoBridge\service-simulation`.
   Keep this directory private; it contains credentials and retained photos.

The simulation alternates two fixed synthetic JPEGs; already-printed bytes remain
suppressed across restarts. For an independent interruption/uncertainty experiment,
use a new private `--state-dir` with the console commands below. Choose
**interrupted download** to exercise a failed transfer, or **uncertain print**
followed by **select photo** to observe `NEEDS_ATTENTION` and no automatic retry.

For a chosen private state directory, the equivalent console commands are:

```powershell
.\PocketPhotoBridge.exe --make-bootstrap "--state-dir=$env:LOCALAPPDATA\PocketPhotoBridge\service-simulation"
.\PocketPhotoBridge.exe --simulate-service "--state-dir=$env:LOCALAPPDATA\PocketPhotoBridge\service-simulation"
```

The original double-click `PocketPhotoBridge.exe` simulation suite, manual
`--simulate` / `--simulate-all` commands, and `WindowsLive.cmd` remain usable.
See `windows-live.md` for the separate physical Windows workflow. Do not use an
old simulator's acknowledgement command to modify the new service's queue;
use the authenticated setup page described below.

## Flash and install from Windows over SSH

1. Flash a board-supported **64-bit** Debian/Ubuntu image using the board vendor's
   instructions. Enable SSH, create your account, and connect the board to your
   home network or Ethernet for this one-time installation. Update the OS.
   A supported Wi-Fi AP interface and Bluetooth Classic adapter are required.
2. Open this repository's
   [Releases](https://github.com/YoungTeurus/pocket-photo-bridge/releases),
   choose the release (tag `v0.5.0` for this version) and download
   `PocketPhotoBridge-0.5.0-linux-arm64.tar.gz` and its `.tar.gz.sha256` file.
   Releases do not expire; a build from an untagged branch is still available
   as the `pocket-photo-bridge-linux-arm64-0.5.0` artifact of a **Headless
   bridge** run for 30 days (extract GitHub's outer ZIP). The checksum detects
   transfer corruption only; the bundle's `BUILD` file (`0.5.0+<commit>`) names
   the source commit. The Linux x64 package is for an x64 machine; do not
   install it on ARM64.
3. In Windows PowerShell, replace `YOUR_USER@BOARD_LAN_IP` with your board's SSH
   account and address, then copy the two files:

   ```powershell
   scp .\PocketPhotoBridge-0.5.0-linux-arm64.tar.gz .\PocketPhotoBridge-0.5.0-linux-arm64.tar.gz.sha256 YOUR_USER@BOARD_LAN_IP:~/
   ssh -t YOUR_USER@BOARD_LAN_IP
   ```

4. In that interactive SSH session, verify and install:

   ```bash
   sha256sum -c PocketPhotoBridge-0.5.0-linux-arm64.tar.gz.sha256
   tar -xzf PocketPhotoBridge-0.5.0-linux-arm64.tar.gz
   cd PocketPhotoBridge
   sudo ./deploy/linux/install.sh
   ```

   The installer requires a terminal to display credentials privately. Do not
   pipe its output to a log or share a screenshot of its credential lines. It
   installs `network-manager`, `wpasupplicant`, `dnsmasq-base`, `nftables`, `bluez` (including `sdptool`), `libbluetooth3`,
   `iproute2`, `libfreetype6`, and `fontconfig` with apt, uses the bundled runtime, and enables
   `pocket-photo-bridge.service`. No board-side compilation is involved. When
   `dpkg-query` reports all of these packages as installed (an upgrade), it
   skips apt, so an upgrade should need no internet uplink (not yet verified on
   the board; see the upgrade steps below). It prints the installed
   build (`0.5.0+<commit>`) and names its release directory after it.

   The dedicated setup AP explicitly disables optional PMF (`pmf=1`) because
   the tested Orange Pi UWE5622 driver cannot initialize that mode. It remains
   password protected; the camera's station profile is unchanged. DHCP and shared
   IPv4 require dnsmasq and a firewall backend even on minimal Armbian images.

   On UWE5622 (`unisoc_wifi`/`sprdwl_ng`) the setup adapter also removes
   NetworkManager's unsupported AP `WPA-PSK-SHA256` advertisement before declaring
   setup ready. It verifies its active connection UUID and current AP network,
   keeps ordinary WPA2-PSK/CCMP, and checks every supplicant change. Other drivers
   and camera station connections do not receive this adjustment. The installer
   explicitly includes `wpasupplicant`. The firmware limitation is independently
   documented in the [OpenAstro hardware setup](https://github.com/open-astro/openastro-imate/blob/main/openastro/openastro-setup.sh).

   The service retains `PrivateTmp=true`. Its supplicant client uses `wpa_cli -s`
   with a private `radio` directory in the state root, so the host supplicant can
   reach the client's reply socket. The default client socket in `/tmp` is hidden
   across that service namespace and times out. Symlinked or non-private client
   directories fail before any supplicant command.

5. Save the displayed unique `PocketPhoto-...` SSID, Wi-Fi password, and
   administrator password privately. Disconnecting Wi-Fi for setup can end the
   SSH session; Ethernet is useful during installation. The hotspot uses
   `10.212.77.0/24`; keep any wired network on a different subnet. Connect your phone or
   Windows computer to that dedicated hotspot, then open
   `http://10.212.77.1:8080/` and sign in. This HTTP address is available only on
   the setup AP. Ignore the client's warning that this hotspot has no Internet.

   Setup responses use `Referrer-Policy: same-origin`: browser form submissions
   retain the exact `Origin` required by the server, while cross-origin referrers
   are suppressed. `Origin: null`, foreign origins, and missing POST origins
   remain rejected together with the existing login/session CSRF checks.

The enabled service runs as root on a dedicated pilot board to administer
NetworkManager and BlueZ. Its systemd sandbox protects the installed application
and permits private state, NetworkManager profile writes, local DBus, and radio
sockets. State is root-owned, mode `0700`, under `/var/lib/pocket-photo-bridge`;
bootstrap/configuration files inside `setup` are private. The application lives
under `/opt/pocket-photo-bridge/releases`, selected by an atomic `current`
symlink. The setup listener binds only `10.212.77.1:8080`; Windows binds only
loopback. Do not deploy this pilot on an unrelated multiuser server.

## Configure once, then print automatically

On the first unconfigured boot the board stays in setup. In the authenticated
page, choose the Wi-Fi interface, enter the Sony camera SSID/password, pair or
choose the printer, enter its real token, and select fit or crop. Explicitly
enable automatic printing and save. Choose **Run** to leave setup.

**Keep waiting photos across power cycles** is off by default, including for
older configurations. With it off, a new Linux boot removes only photos not yet
submitted to the printer; send a discarded JPEG again from the camera if needed.
Enable it to keep waiting photos after power restarts. Changes apply at the next
device boot, not when saving setup or restarting the service within the same
boot. The first upgrade adopts an existing queue without a boot marker; it does
not discard that queue during installation. Submitted-photo history and unknown
print reservations are always retained.

Pairing runs in the background because BlueZ discovery and pairing can take
about 40 seconds. Refresh the page while status shows `PAIRING`; choose Run
after it finishes. A failed pairing reports a fixed category in diagnostics.
If the paired-printer list cannot be read (for example Bluetooth is hung), the
page still opens, says the list is unavailable, and accepts the printer address
typed by hand; saving, acknowledging and Run keep working.

When the bridge accepts or prints no photos, the status section says why in one
sentence, and the status reports `blocked` with a fixed `blockedReason`:
`UNRESOLVED_PRINT` (check the printer and its output, then acknowledge; see
below), `QUEUE_FULL` (the waiting queue is full; start operation with the printer
on so they print, then send new photos) or `STATE_ERROR` (the saved state
cannot be read; download the diagnostics, then restart the bridge without
deleting its state files). The page also shows how many waiting photos were
discarded at this boot (`discardedAtBoot`) and always notes that with the
retention setting off a reboot discards waiting photos. The count is known
only to the service run that began the boot: after a service restart within
the same boot the page shows no count, and the earlier run's diagnostics keep
the `BRIDGE_BOOT_QUEUE_DISCARDED` line.

A state error during operation, for example a failed write to a worn SD card,
does not end printing for the rest of the boot. The service closes the bridge
and opens it again, which checks the saved state from disk as a start does;
duplicate prevention and an unknown print's block are kept because they live in
that state. Each state error gets one such reopen, and a service run gets at
most three. Each is logged as `SERVICE_STATE_REOPENED reason=OK count=<n>` or,
when the state still cannot be read, `SERVICE_STATE_REOPENED reason=FAILED
category=<category> count=<n>`. After a failed reopen or the third one, the
bridge stays blocked with `STATE_ERROR` and the service keeps running without
exiting, so systemd does not restart it into a loop through setup. Because the
page is unreachable during operation, a bridge that stops printing needs a
restart: `sudo systemctl restart pocket-photo-bridge.service` keeps waiting
photos, while a reboot with the retention setting off discards them.

After configuration, every reboot opens setup for 60 seconds. Signing in and
interacting with the page holds setup open; otherwise the hotspot and HTTP
server stop before the board joins the camera network. Reboot to enter setup
again. With **Keep waiting photos across power cycles** off, that reboot
discards photos still waiting for the printer before setup opens. A single radio alternates between these modes; the setup page is
unreachable while the bridge is connected to the camera.

If the hotspot cannot start, for example because the Wi-Fi driver is still
loading after power-on or the radio is blocked, the service tries again 5
seconds after each failed attempt. It starts no new attempt two minutes after
the first one (an attempt already running then may finish) and exits with
`SERVICE_FAILED`; systemd starts it again 30 seconds later, as often as needed,
so the hotspot appears once the radio works, without a power cycle. The setup
window counts from the moment the hotspot is up, however long that took. Until
then nothing prints: operation begins only after setup. A failed attempt is
logged as `SERVICE_SETUP_FAILED category=<category> count=<n>`, where `n` is the
number of failed attempts so far in that run; it is written after 1, 2, 4, 8 ...
failures, whenever the category changes, and once more when the hotspot comes up
or the run gives up. `sudo systemctl stop` stops the service without a restart.
A stop, including the one every upgrade performs, logs `SERVICE_STOPPED` and
then takes down the setup hotspot or the camera connection the bridge owns,
also while the hotspot is still being retried. The service waits at most 30
seconds for that cleanup, within the unit's 45-second stop timeout; a cleanup
that fails logs `SERVICE_RADIO_CLEANUP_FAILED`. A stop during a print leaves
that print unknown, to be checked as described below.
Setup refuses to save an interface name that is not a Wi-Fi interface on the
board, including a wired, loopback or USB network name
(`WIFI_INTERFACE_NOT_FOUND`), and keeps the configuration saved before, because
the hotspot itself starts on the saved interface.

For normal use, power on the bridge and printer in either order, then use the
RX100 VII's selected-photo transfer for one JPEG. The bridge durably stores the
validated original before acknowledging the camera, prepares the print, and
prints automatically. Printer off/busy keeps the captured image queued: within
the same boot, including a service restart, it prints once the printer is
available. After a reboot or power cut with the retention setting off, photos
not yet submitted are discarded; send them again from the camera. The same JPEG
bytes are never printed twice automatically, also across reboots.
There is no per-photo approval in normal operation. Leave queued state intact
when restarting, upgrading, or diagnosing the bridge.

## Resolve an unknown print safely

If the printer may have accepted a job but completion was lost, the bridge
shows an unknown print and blocks automatic submissions. Check the physical
printer and its paper output. Reboot the bridge, join setup, sign in, and use
the page's **I checked the printer** acknowledgement only after that check.

Rebooting into setup discards photos still waiting for the printer when
**Keep waiting photos across power cycles** is off; the unknown print's record
itself is kept. The setup page shows how many were discarded at this boot;
send those again from the camera after the acknowledgement. The discard happens when the service starts, before setup is
reachable, so switching the setting on in that setup session protects only
later boots: check it in setup and save; it applies from the next device boot.
With shell access over Ethernet or a serial console,
`sudo systemctl restart pocket-photo-bridge.service` opens the setup window
within the same boot and keeps waiting photos.
The acknowledgement retains duplicate-prevention records; it does not reprint
the ambiguous photo. Keep the photo/queue state rather than deleting files to
clear the warning. Deliberate reprints are outside this milestone.

## Diagnostics, credentials, and upgrades

From authenticated setup, download **Diagnostics** and return that redacted
text (its `build` field names version and commit), the package architecture, your board/OS model, and a
brief description of camera/printer power order and the physical outcome.
Diagnostics contain fixed stage/category/count information.

Each run of the service or of a probe (`--linux-status-only`,
`--linux-receive-only`) starts with a header line such as
`Pocket Photo Bridge 0.5.0+1a2b3c4 | headless service run=3 uptime_s=42`: the version and build commit (`dev` for a build made without one),
a run counter, and the kernel uptime in whole seconds when the process started
(omitted where the kernel does not report it). Each event line starts with the
whole seconds since that start; there is no wall-clock time, because the board
has no backed-up clock. An unchanged state or camera-scan failure is written
once. A print attempt that fails the same way as the one before, and a
re-check of a still-visible camera that ends as the one before (for example
`DUPLICATE_SUPPRESSED`), leave none of their steps (radio steps included);
instead a line ending in `count=N` is written after 1, 2, 4, 8 ... such
repeats, so hours of idling, of a camera left on its send screen or of a
switched-off printer do not push earlier events out. The system journal still
shows every step as it happens. While a photo waits, a failed print attempt
(printer off, busy, or refusing the job) is retried after 2, 4, 8 ... seconds, at
most every 30 seconds; a new photo or a successful print starts again at 2 seconds.
A job the printer refuses is logged as `PRINTER_SUBMIT_REFUSED code=N`; a busy
refusal (`code=8006`) keeps the photo queued; any other code drops the photo.
To print it, leave the camera's send screen until the bridge logs
`SERVICE_CAMERA_ABSENT`, then send it again; while the camera stays visible the
bridge treats it as already received (`DUPLICATE_SUPPRESSED`). Photos removed from the queue
because they were already submitted or were refused this way are logged as `BRIDGE_QUEUE_RECONCILED count=N`. The run that was in place before the
upgrade to run headers is kept as a previous run without a run counter.

A failed camera or printer step names its own category, for example
`CAMERA_ROUTE_UNSAFE`, `COMMAND_FAILED` or `COMMAND_TIMEOUT` for the camera
Wi-Fi and `SPP_UNAVAILABLE` when the printer is off or out of range, instead of
`IO_ERROR`. Joining the camera Wi-Fi logs `LINUX_CAMERA_ACTIVATED` before the
address and route are checked and `LINUX_CAMERA_CONNECTED` after; a line
without the first means the join itself failed. A failed join logs
`LINUX_CAMERA_ACTIVATION_FAILED nm_exit=<n> nm_state=<n> nm_reason=<n>`:
nmcli's exit status (for example 3 timeout, 4 activation failed, 10 no such
network) and NetworkManager's numeric device state and reason read right after
it. Association and address assignment happen in the same nmcli step, so only
these numbers tell them apart. Whether NetworkManager still holds the reason at
that moment on the board's image is not yet confirmed; record it in the field
protocol. An activation that failed, including one nmcli gave up on after its
wait (exit 3) while NetworkManager may still be bringing it up, is taken down
with a checked `nmcli connection down`. `LINUX_SETUP_STOPPED` appears only when
a running hotspot was stopped or a hotspot activation failed.
The camera transfer logs `SONY_SELECTION count=<items>` and
`SONY_JPEG_BYTES count=<bytes>`.

At start the previous run moves to `diagnostics.1.txt` and older runs shift up
to `diagnostics.5.txt`; anything older is dropped. A probe, reboot, crash restart
or service restart therefore no longer erases the service's history. Runs that
only failed to start the hotspot replace each other in `diagnostics.1.txt`, so a
long outage keeps the latest of them and the runs before the outage. The
download keeps the current run in `events` and adds `previousRuns`, oldest
first, each with its `header` and `events`. Every line is checked again when it
is served; a line that is not a known event is left out. Do not send the
private state directory, images, configuration/bootstrap files, printer token,
hotspot password, administrator password, or the installer's credential output.

The optional read-only Linux check uses the installed runtime and does not pair,
change Wi-Fi profiles, or print:

```bash
sudo /opt/pocket-photo-bridge/current/bin/bridge --linux-native-check
sudo systemctl status pocket-photo-bridge.service --no-pager
```

### Physical Linux probes without printing

Current builds provide two explicit commands using the saved
configuration. Stop the service first: both probes acquire its persistent queue
and print-journal locks and fail if another owner is active. Preserve all state.

```bash
sudo systemctl stop pocket-photo-bridge.service
sudo /opt/pocket-photo-bridge/current/bin/bridge --linux-status-only
sudo /opt/pocket-photo-bridge/current/bin/bridge --linux-receive-only
```

`--linux-status-only` discovers the actual SPP channel with SDP, opens RFCOMM,
and invokes the existing encrypted read-only diagnosis. It never opens camera
Wi-Fi, resets printer data, or submits a job. `--linux-receive-only` joins the
saved camera Wi-Fi and invokes the existing capture/validation path, retaining
the original and prepared JPEG in the service queue. It never opens a printer
transport. Both print `NO_PHYSICAL_PRINT`; an unresolved prior print blocks
transport actions. The existing native ABI check remains a separate test.

The receive probe disconnects the current Wi-Fi, including an SSH connection on
that radio. Arrange independent access or a tested timed network recovery before
starting it. Do not start the normal service with queued photos until automatic
printing is intended. A queued probe photo is printed by the next service start
in the same boot; a reboot with the retention setting off discards it. Existing
duplicate/uncertainty records are always retained.

To redisplay your existing bootstrap credentials locally, first stop the service
so it no longer owns the state, run the command in a private terminal, then
restart:

```bash
sudo systemctl stop pocket-photo-bridge.service
sudo /opt/pocket-photo-bridge/current/bin/bridge --make-bootstrap --state-dir=/var/lib/pocket-photo-bridge
sudo systemctl start pocket-photo-bridge.service
```

For an upgrade, verify and extract the new matching portable archive and run its
installer in the same way. Ethernet or a serial console is the recommended
path. With the packages already installed the installer skips apt, so an
upgrade over the setup hotspot without internet should also work, but this has
not yet been verified on the board. Over the hotspot:

1. Sign in to the setup page and keep interacting with it, or choose
   **Keep setup open**; otherwise the hotspot stops 60 seconds after boot and
   takes the SSH session with it.
2. Copy the archive and run the installer over SSH as above. Expect the SSH
   session to drop when the installer stops the service, because stopping it
   takes the hotspot down; the installer's last lines may never appear.
3. Rejoin the hotspot, which the new service opens again for 60 seconds, sign
   in, and check the `build` field in the setup status or Diagnostics. An
   unchanged build means the installer stopped and restored the previous
   release. Over Ethernet or a serial console,
   `readlink /opt/pocket-photo-bridge/current` names the installed release.

The installer stops the service before switching releases,
reuses existing bootstrap/configuration, and retains all queue and print-history
state. A stopped process may leave an uncertain print; resolve it by checking
the printer as described above. Do not run two service instances with the same
state or copy old state over newer state.
