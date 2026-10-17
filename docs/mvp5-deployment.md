# Pocket Photo Bridge 0.5.0: service simulation and Linux pilot

The portable downloads contain the application and Java 17 runtime. You do not
need a JDK, Gradle, Android SDK, or a compiler on Windows or the bridge board.
Keep the complete extracted folder together, including `runtime`, `lib`/`app`,
`licenses`, and `deploy`. Android and the existing `WindowsLive.cmd` manual
hardware workflow remain available.

Linux radio support is a pilot awaiting Orange Pi hardware validation. The
Windows simulation proves controller and setup behavior using synthetic devices;
it cannot access radios or print paper. Wi-Fi AP support, board drivers, BlueZ
pairing, discovered SPP channels, reconnect, and physical printing still need
testing on the target board. Use a board-supported 64-bit Debian/Ubuntu image.
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
2. Download `pocket-photo-bridge-linux-arm64-0.5.0` from the same successful
   **Headless bridge** run. Extract the outer ZIP to obtain
   `PocketPhotoBridge-0.5.0-linux-arm64.tar.gz` and its `.tar.gz.sha256` file.
   The Linux x64 artifact is for an x64 machine; do not install it on ARM64.
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
   installs `network-manager`, `dnsmasq-base`, `nftables`, `bluez` (including `sdptool`), `libbluetooth3`,
   `iproute2`, `libfreetype6`, and `fontconfig` with apt, uses the bundled runtime, and enables
   `pocket-photo-bridge.service`. No board-side compilation is involved.

   The dedicated setup AP explicitly disables optional PMF (`pmf=1`) because
   the tested Orange Pi UWE5622 driver cannot initialize that mode. It remains
   password protected; the camera's station profile is unchanged. DHCP and shared
   IPv4 require dnsmasq and a firewall backend even on minimal Armbian images.

5. Save the displayed unique `PocketPhotoBridge-...` SSID, Wi-Fi password, and
   administrator password privately. Disconnecting Wi-Fi for setup can end the
   SSH session; Ethernet is useful during installation. Connect your phone or
   Windows computer to that dedicated hotspot, then open
   `http://192.168.50.1:8080/` and sign in. This HTTP address is available only on
   the setup AP. Ignore the client's warning that this hotspot has no Internet.

The enabled service runs as root on a dedicated pilot board to administer
NetworkManager and BlueZ. Its systemd sandbox protects the installed application
and permits private state, NetworkManager profile writes, local DBus, and radio
sockets. State is root-owned, mode `0700`, under `/var/lib/pocket-photo-bridge`;
bootstrap/configuration files inside `setup` are private. The application lives
under `/opt/pocket-photo-bridge/releases`, selected by an atomic `current`
symlink. The setup listener binds only `192.168.50.1:8080`; Windows binds only
loopback. Do not deploy this pilot on an unrelated multiuser server.

## Configure once, then print automatically

On the first unconfigured boot the board stays in setup. In the authenticated
page, choose the Wi-Fi interface, enter the Sony camera SSID/password, pair or
choose the printer, enter its real token, and select fit or crop. Explicitly
enable automatic printing and save. Choose **Run** to leave setup.

Pairing runs in the background because BlueZ discovery and pairing can take
about 40 seconds. Refresh the page while status shows `PAIRING`; choose Run
after it finishes. A failed pairing reports a fixed category in diagnostics.

After configuration, every reboot opens setup for 30 seconds. Signing in and
interacting with the page holds setup open; otherwise the hotspot and HTTP
server stop before the board joins the camera network. Reboot to enter setup
again. A single radio alternates between these modes; the setup page is
unreachable while the bridge is connected to the camera.

For normal use, power on the bridge and printer in either order, then use the
RX100 VII's selected-photo transfer for one JPEG. The bridge durably stores the
validated original before acknowledging the camera, prepares the print, and
prints automatically. Printer off/busy keeps the captured image queued. The
same JPEG bytes are automatically printed once, including after restart.
There is no per-photo approval in normal operation. Leave queued state intact
when restarting, upgrading, or diagnosing the bridge.

## Resolve an unknown print safely

If the printer may have accepted a job but completion was lost, the bridge
shows an unknown print and blocks automatic submissions. Check the physical
printer and its paper output. Reboot the bridge, join setup, sign in, and use
the page's **I checked the printer** acknowledgement only after that check.
The acknowledgement retains duplicate-prevention records; it does not reprint
the ambiguous photo. Keep the photo/queue state rather than deleting files to
clear the warning. Deliberate reprints are outside this milestone.

## Diagnostics, credentials, and upgrades

From authenticated setup, download **Diagnostics** and return that redacted
text, the downloaded package version/architecture, your board/OS model, and a
brief description of camera/printer power order and the physical outcome.
Diagnostics contain fixed stage/category/count information. Do not send the
private state directory, images, configuration/bootstrap files, printer token,
hotspot password, administrator password, or the installer's credential output.

The optional read-only Linux check uses the installed runtime and does not pair,
change Wi-Fi profiles, or print:

```bash
sudo /opt/pocket-photo-bridge/current/bin/bridge --linux-native-check
sudo systemctl status pocket-photo-bridge.service --no-pager
```

### Physical Linux probes without printing

Hardware-validation builds also provide two explicit commands using the saved
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
printing is intended. Queued photos remain available to the normal service after
restart, and existing duplicate/uncertainty records are retained.

To redisplay your existing bootstrap credentials locally, first stop the service
so it no longer owns the state, run the command in a private terminal, then
restart:

```bash
sudo systemctl stop pocket-photo-bridge.service
sudo /opt/pocket-photo-bridge/current/bin/bridge --make-bootstrap --state-dir=/var/lib/pocket-photo-bridge
sudo systemctl start pocket-photo-bridge.service
```

For an upgrade, verify and extract the new matching portable archive and run its
installer in the same way. It stops the service before switching releases,
reuses existing bootstrap/configuration, and retains all queue and print-history
state. A stopped process may leave an uncertain print; resolve it by checking
the printer as described above. Do not run two service instances with the same
state or copy old state over newer state.
