# Linux portable bridge and pilot service (0.5.0)

Version 0.5.0 provides native Linux x64 and ARM64 portable bundles, offline simulations, an authenticated setup server, and autonomous Linux pilot adapters. The Orange Pi Zero 2W target needs a **64-bit Linux OS**; an x64 Windows runtime cannot be used there. The proposed 4 GB board still needs actual hardware validation.

The Sony/printer protocols, bounded JPEG preparation and durable duplicate guard remain shared with the proven Android and separate [Windows live workflow](windows-live.md). Those Windows adapters refuse Linux. The old unsupported `--hardware` command still exits `64`; the Linux pilot runs explicitly with `--service`. Windows/Android physical success is evidence for those workflows and does not establish Linux radio success.

## Run the offline simulation

Download `pocket-photo-bridge-linux-arm64-0.5.0` (ARM64/AArch64 board) or
`pocket-photo-bridge-linux-x64-0.5.0` (x64 Linux) from a successful **Headless
bridge** Actions run. Extract GitHub's outer artifact ZIP to obtain the native
portable `.tar.gz` and its `.tar.gz.sha256` file. These packages include Java 17
and need no system Java or JDK. The separate `pocket-photo-bridge-jvm-0.5.0`
artifact remains available for developers with their own matching Java runtime.

On Debian/Ubuntu, install the native font dependencies used by headless Java
desktop imaging. For the ARM64 bundle:

```bash
sudo apt-get update
sudo apt-get install --no-install-recommends libfreetype6 fontconfig
sha256sum -c PocketPhotoBridge-0.5.0-linux-arm64.tar.gz.sha256
tar -xzf PocketPhotoBridge-0.5.0-linux-arm64.tar.gz
cd PocketPhotoBridge
./runtime/bin/java -version
./runtime/bin/java --list-modules | grep -E '^(java.desktop|jdk.httpserver)@'
./bin/bridge --help
./bin/bridge --simulate-all
./bin/bridge --simulate --state-dir="./normal simulation state"
./bin/bridge --simulate --state-dir="./normal simulation state"
./bin/bridge --simulate-failure=printer-busy --state-dir="./busy simulation state"
echo "$?"
```

Use the equivalent `linux-x64` archive on an x64 machine. The portable shell
launcher uses only `runtime/bin/java` and supplies the headless flags and 512 MiB
heap cap itself, including when `JAVA_HOME` and system Java are unavailable.

The suite returns `0` and prints `SIMULATION_SUITE_PASS checks=<number>` when all expected outcomes pass. Reports include `SIMULATION_ONLY`, `NO_PHYSICAL_PRINT` and fixed `BRIDGE_*` lines. A fresh ordinary simulation reports one simulated job; the same event on the same state is suppressed on subsequent runs. FIT is the default; add `--crop` for centered CROP. The simulation works offline without real photos, tokens, radios or a cloud service.

The initial 512 MiB heap cap leaves room for the OS on a 4 GB board; resource use and thermals must still be measured on the board. Image preparation bounds received bytes and decoded dimensions, and retains `java.desktop` even in headless mode. Native JPEG decoder allocations are outside the Java heap: an 8192×8192 progressive JPEG used approximately 292 MiB process RSS in a cloud check despite a 64 MiB heap cap. Measure progressive JPEG RSS on the board before setting its production memory budget. Errors about `libfontmanager`/FreeType or missing `java.desktop` indicate an incomplete extracted runtime or native dependency set. Extract the complete portable bundle for the installed OS architecture.

Without `--state-dir`, ordinary state is stored under `$HOME/.local/state/pocket-photo-bridge/simulation` (Java's `user.home`). POSIX state directories/files are owner-only. Keep state on persistent local storage and preserve it across deployments; journal locks exclude another running instance. Safe `diagnostics.txt` reports exclude photos, tokens, fingerprints, arbitrary exception text and personal paths. Do not publish the state snapshot as a diagnostic artifact.

Exit codes are `0` for completion/passing suite/suppression/acknowledgement, `1` for a failed simulation check, `2` for known failures, `3` for uncertain outcomes, `64` for invalid usage or unsupported hardware mode, and `71` for the deliberate power-cut fault. [Windows simulation instructions](windows-simulation.md) list all fault names and show manual recovery using the same CLI on Linux. Explicit commands never pause; no arguments run the suite and wait for Enter only when an interactive console exists.

For a separate-process crash experiment:

```bash
./bin/bridge --simulate-failure=power-cut --state-dir="./power cut experiment" # exits 71
./bin/bridge --simulate --state-dir="./power cut experiment" # exits 3; no new job
./bin/bridge --acknowledge-checked-printer --confirm --state-dir="./power cut experiment"
./bin/bridge --simulate --state-dir="./power cut experiment" # suppressed, exits 0
```

Acknowledgement keeps event/photo tombstones and clears only the uncertainty gate. It neither retries the uncertain job nor cancels work on the printer. Damaged state fails closed. Forced files/atomic replacements and POSIX directory synchronization provide software persistence; actual storage power-loss behavior remains unvalidated. The guarantee is at most one automatic submission, with no claim of exactly-once physical output.

## Linux autonomous pilot and hardware validation

Use [0.5.0 deployment instructions](mvp5-deployment.md) for Windows SSH transfer,
the bundled-runtime installer, private bootstrap credentials, authenticated
setup, and normal power-order operation. The installer requires NetworkManager,
BlueZ (including `sdptool`), `libbluetooth3`, `iproute2`, FreeType and fontconfig.
It enables the actual `deploy/linux/pocket-photo-bridge.service` under systemd.
The older `*.example` files are archival design templates and are not used by
this installer. Configure through authenticated setup; no handwritten proposed
TOML schema or token on the service command line is needed.

The first unconfigured boot stays in a dedicated setup AP; configured reboots
offer a 60-second setup window. An authenticated interaction holds setup open.
The unique SSID starts with `PocketPhotoBridge-`, and only the setup interface
serves `http://192.168.50.1:8080/`. The hotspot and HTTP listener stop before
joining the camera network. Reboot to enter setup again. The state root is
`/var/lib/pocket-photo-bridge`, root-owned and private. Upgrades retain the queue,
configuration, bootstrap secrets, and print-history records.

Software adapters use protected NetworkManager profiles and discover the
printer's Classic RFCOMM SPP channel with SDP rather than assuming channel 1.
The actual selected camera link supplies camera routing; a universal camera
gateway is not assumed. Passwords/tokens remain in private state/profile files,
not process arguments or diagnostics. Durable capture and print reservations
retain photos while the printer is offline or busy and block automatic
submission after uncertain acceptance. Resolve unknown jobs only after checking
the physical printer, through authenticated setup; keep duplicate records intact.

Actual Wi-Fi AP support, hotspot-to-camera transitions, Bluetooth coexistence,
SPP discovery, headless boot, pairing, permissions, photo selection/rearming,
JPEG rendering, printer firmware responses, reconnect, power cuts and battery
behavior must still be exercised on the Orange Pi and printer. Use a
board-supported 64-bit Debian/Ubuntu image; Armbian Community images are not
certified by this project. Linux ARM64 CI executes the extracted native bundle's
real JVM simulations and authenticated HTTP server on an ARM64 hosted runner;
it provides no evidence about Orange Pi radios or physical printer output.

## Build and CI

The Windows x64, Linux x64 and Linux ARM64 jobs use Java 17.0.12 and pinned Actions. Headless builds do not configure Android or require its SDK:

```bash
./gradlew --no-daemon -PheadlessOnly=true :sony:test :printer:test :bridge:test :bridge:installDist :bridge:distZip :bridge:distTar
bash bridge/packaging/package-linux.sh
bash bridge/packaging/smoke-linux.sh # native architecture selected automatically
```

The Linux smoke script checks the portable checksum, extracts into a path with spaces, and checks normal, crop, duplicate, deterministic faults, suite and power-cut recovery in bounded child processes with system Java excluded. It also checks the read-only Linux native bindings and authenticates against the real extracted service HTTP server, verifying redacted status/diagnostics without saving bootstrap output. HTTP controls prove capture retention while the printer is off or busy, completion when available, and duplicate suppression. Safe reports upload as `bridge-verification-linux-x64` and `bridge-verification-linux-arm64`. The existing Android workflow remains separate. Portable bundles include deployment files, documentation, project/mizink MIT notices, JNA licenses, and the bundled Java runtime/module legal notices.
