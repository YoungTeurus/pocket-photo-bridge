# Linux JVM bridge distribution (0.4.0)

MVP 4 provides an architecture-independent Kotlin/JVM distribution and offline simulations. Java 17 must be installed for the machine's architecture. The future Orange Pi Zero 2W target is a **64-bit Linux OS with an ARM64/AArch64 Java runtime**; an x64 Windows runtime cannot be used there. The proposed 4 GB board still needs actual hardware validation.

The coordinator, Sony/printer protocols, bounded JPEG preparation and durable guard are reusable. NetworkManager Wi-Fi detection, Linux camera HTTP routing and BlueZ printer transport adapters are **not implemented**. `--hardware` exits `64` without opening transports. Nothing in this distribution enables autonomous physical printing.

## Run the offline simulation

Download `pocket-photo-bridge-jvm-0.4.0` from a successful **Headless bridge** Actions run. Extract GitHub's outer artifact ZIP to obtain `bridge-0.4.0.tar`, `bridge-0.4.0.zip` and `bridge-0.4.0.sha256`. The archives contain the same application and require a local matching Java runtime.

On Debian/Ubuntu, including their ARM64 variants, install Java and the native font dependencies used by headless Java desktop imaging:

```bash
sudo apt-get update
sudo apt-get install --no-install-recommends openjdk-17-jre-headless libfreetype6 fontconfig
java -version
java --list-modules | grep '^java.desktop@'
sha256sum -c bridge-0.4.0.sha256
tar -xf bridge-0.4.0.tar
cd bridge-0.4.0
export JAVA_OPTS='-Djava.awt.headless=true -Xmx512m'
./bin/bridge --help
./bin/bridge --simulate-all
./bin/bridge --simulate --state-dir="./normal simulation state"
./bin/bridge --simulate --state-dir="./normal simulation state"
./bin/bridge --simulate-failure=printer-busy --state-dir="./busy simulation state"
echo "$?"
```

The suite returns `0` and prints `SIMULATION_SUITE_PASS checks=<number>` when all expected outcomes pass. Reports include `SIMULATION_ONLY`, `NO_PHYSICAL_PRINT` and fixed `BRIDGE_*` lines. A fresh ordinary simulation reports one simulated job; the same event on the same state is suppressed on subsequent runs. FIT is the default; add `--crop` for centered CROP. The simulation works offline without real photos, tokens, radios or a cloud service.

The initial 512 MiB heap cap leaves room for the OS on a 4 GB board; resource use and thermals must still be measured on the board. Image preparation bounds received bytes and decoded dimensions, and retains `java.desktop` even in headless mode. Errors about `libfontmanager`/FreeType or missing `java.desktop` indicate an incomplete local runtime or native dependency set. Use Java for the installed OS architecture.

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

## Future Orange Pi integration requirements

The included `deploy/linux/pocket-photo-bridge.service.example` and `pocket-photo-bridge.toml.example` are inactive design templates. The configuration schema is proposed and is not parsed by the current CLI. A reserved `--config` path is accepted only alongside `--hardware`; hardware mode refuses to run without reading that file. Do not enable the service as an unattended printer.

The future Linux implementation must meet these requirements before enabling a service:

- **Camera Wi-Fi:** save an owner/root-only NetworkManager profile for the known Sony SSID, configure the explicit Wi-Fi interface, and activate that saved profile. Enter its password through an appropriate secret agent or protected profile file, never command arguments or logs. Set `ipv4.never-default` and `ipv6.never-default`, avoid adopting camera DNS/internet routes, and derive the camera endpoint from the selected link's actual local gateway/address information. Validate the route on the target board; do not invent a universal camera gateway. Camera HTTP must use the selected route, no redirects and no system proxy.
- **Selection detection:** track a stable sharing generation, debounce network flaps, and wait for the camera AP to disappear before rearming. Consume a trigger before receiving its photo. Persist event and content suppression across camera/bridge restarts. A reconnect alone cannot authorize replaying an uncertain submission.
- **Printer Bluetooth:** pair a known printer using BlueZ **Classic RFCOMM SPP**. Discover its service using SDP and use the verified channel; do not assume channel `1` or substitute BLE. Implement bounded raw-byte reads/writes/close, pairing lifecycle and device permissions through a reviewed adapter or broker. Keep the existing encrypted protocol framing.
- **Secrets:** store the user-owned printer token separately, in an owner-only file or systemd credential. The proposed configuration refers to a credential name, never a token value. Avoid passwords/tokens in process arguments, environment dumps, diagnostics or repository files. Restrict configuration and secrets to the service owner/root (`0600` files, `0700` secret directories). No real secret is needed for this milestone.
- **Submission safety:** retry camera/printer availability only before a durable submission reservation. After possible acceptance, block new jobs until an operator checks the printer and acknowledges; preserve submitted-photo suppression afterward. Intentional reprinting needs a separate future design.

Actual Wi-Fi/Bluetooth coexistence, SPP channel discovery, headless boot, pairing, user permissions, photo selection/rearming, JPEG rendering, printer firmware responses, restarts/power cuts and battery behavior must be exercised on the Orange Pi and printer. Linux ARM64 CI executes the extracted distribution's real JVM simulations on an ARM64 hosted runner; it provides no evidence about Orange Pi radios, printer output or unattended service readiness.

## Build and CI

The Windows x64, Linux x64 and Linux ARM64 jobs use Java 17.0.12 and pinned Actions. Headless builds do not configure Android or require its SDK:

```bash
./gradlew --no-daemon -PheadlessOnly=true :sony:test :printer:test :bridge:test :bridge:installDist :bridge:distZip :bridge:distTar
bash bridge/packaging/smoke-linux.sh bridge/build/distributions/bridge-0.4.0.tar
```

The Linux smoke script extracts into a path with spaces and checks normal, crop, duplicate, all deterministic faults, suite and power-cut recovery in bounded child processes. Safe reports upload as `bridge-verification-linux-x64` and `bridge-verification-linux-arm64`. The existing Android workflow remains separate. The JVM archives include documentation, inactive deployment examples and project/mizink MIT notices; licenses for the separately installed Java runtime come from its distributor.
