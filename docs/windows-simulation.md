# Windows portable simulation (0.4.2)

The Windows 10/11 **x64** package runs directly on Windows. It includes an EXE launcher and its Java 17 runtime; you do not need WSL, a JDK, Gradle or an IDE. Keep the entire `PocketPhotoBridge` folder together. Copying only the EXE will break it.

The EXE's default mode is an offline simulation. Synthetic camera and printer devices exercise the real Sony transfer and printer protocol sessions, image preparation and durable duplicate guard. Simulation opens no real Wi-Fi/Bluetooth connection, submits no physical print and needs no account, printer token or cloud service. Version 0.4.2 also includes a separate explicit [Windows live-device mode](windows-live.md), launched with WindowsLive.cmd. Linux radio adapters are a future milestone.

## Download and run

1. Open the repository's **Actions → Headless bridge** workflow and select a successful run for the intended revision.
2. Download the artifact named `pocket-photo-bridge-windows-x64-0.4.2`. GitHub's artifact download is an outer ZIP. Extract it to obtain `PocketPhotoBridge-0.4.2-windows-x64.zip` and its `.zip.sha256` file.
3. Optionally verify the package in PowerShell from that folder:

   ```powershell
   Get-FileHash .\PocketPhotoBridge-0.4.2-windows-x64.zip -Algorithm SHA256
   Get-Content .\PocketPhotoBridge-0.4.2-windows-x64.zip.sha256
   ```

   The two SHA-256 hashes must match, ignoring letter case. The checksum detects a damaged or changed download; obtain both files from the same trusted run.
4. Use **Extract All** on the inner package ZIP. Paths containing spaces are supported. Open its `PocketPhotoBridge` folder and double-click `PocketPhotoBridge.exe`.

The default run checks the complete deterministic scenario suite. With an interactive console it waits for Enter after reporting its result. A successful report contains `SIMULATION_ONLY`, `NO_PHYSICAL_PRINT` and `SIMULATION_SUITE_PASS checks=<number>`. Expected faults are part of the passing suite; they do not mean a physical device failed.

The folder includes `app/`, `runtime/`, documentation, inactive Linux templates, the project and mizink MIT notices under `licenses/`, and Java module notices under `runtime/legal/`. There is no installer or registry setup. This is an app image with a bundled runtime, not a standalone EXE.

## PowerShell commands

Open PowerShell inside the extracted `PocketPhotoBridge` folder. Explicit simulation commands finish without waiting for Enter.

```powershell
.\PocketPhotoBridge.exe --help
.\PocketPhotoBridge.exe --simulate-all
.\PocketPhotoBridge.exe --simulate --state-dir=".\normal simulation state"
$LASTEXITCODE
.\PocketPhotoBridge.exe --simulate --state-dir=".\normal simulation state"
.\PocketPhotoBridge.exe --simulate --crop --state-dir=".\crop simulation state"
.\PocketPhotoBridge.exe --simulate-failure=printer-busy --state-dir=".\busy simulation state"
$LASTEXITCODE
```

The first ordinary simulation on fresh state reports `SIMULATED_PRINTS count=1`. Repeating it on the same state suppresses the same event and reports `count=0`; this is deliberate. FIT is the default image placement; `--crop` selects centered CROP. The suite keeps its scenarios isolated, so repeated suite runs can check normal operation without replaying an ordinary saved job.

Without `--state-dir`, ordinary simulation state is stored in `%LOCALAPPDATA%\PocketPhotoBridge\simulation`. Fixed stage/outcome reports use `BRIDGE_*` lines. `diagnostics.txt` contains safe stages, counts and outcomes, excluding photos, tokens, fingerprints and personal paths. State snapshots are local duplicate-prevention data; do not treat them as diagnostics or edit them by hand.

| Exit code | Meaning |
| --- | --- |
| `0` | Simulated completion, passing suite, acknowledgement or safely suppressed duplicate |
| `1` | A simulation check did not match its expected outcome or job count |
| `2` | Known simulated failure |
| `3` | Uncertain submission/completion; new automatic jobs are blocked |
| `64` | Invalid command or unsupported `--hardware` mode |
| `71` | Deliberate simulated power cut after durable reservation and printer acceptance |

Fault names are `missing-ssid`, `camera-offline`, `camera-timeout`, `no-selected-jpeg`, `interrupted-transfer`, `printer-offline`, `printer-busy`, `dropped-after-submit`, `uncertain-completion`, `duplicate-trigger`, `duplicate-photo`, `restart`, `manual-recovery` and `power-cut`. The duplicate/restart/recovery scenarios assert their expected policy and return `0`. Simulated timeouts advance virtual time.

The suite checks 14 scenarios. `power-cut` is a separate process-level experiment because it deliberately halts its JVM. Use a fresh synthetic state directory for each individual fault experiment; repeating a consumed fault generation is suppressed. The global uncertainty gate and event/photo tombstones also retain a submitted photo after a known rejection. A same-photo intentional reprint is not implemented.

## Inspect crash recovery

Use a new synthetic state directory for this experiment:

```powershell
.\PocketPhotoBridge.exe --simulate-failure=power-cut --state-dir=".\power cut experiment"
$LASTEXITCODE # 71
.\PocketPhotoBridge.exe --simulate --state-dir=".\power cut experiment"
$LASTEXITCODE # 3; no new job
.\PocketPhotoBridge.exe --acknowledge-checked-printer --confirm --state-dir=".\power cut experiment"
$LASTEXITCODE # 0
.\PocketPhotoBridge.exe --simulate --state-dir=".\power cut experiment"
$LASTEXITCODE # 0; same event/photo remains suppressed, no new job
```

Acknowledgement clears the global uncertainty gate only. It retains consumed events and attempted photo fingerprints, never retries the old job and never cancels printer-side work. In a future hardware deployment, checking the actual printer before acknowledgement will be essential. At most one automatic submission is the guarantee being tested; exactly-once physical output is not claimed.

## What to send back

After the run, send the contents of `diagnostics.txt` from `%LOCALAPPDATA%\PocketPhotoBridge\simulation`, or from the directory explicitly passed with `--state-dir`. For PowerShell, also give `$LASTEXITCODE` and say whether the EXE opened and stayed visible. A clean suite should end with `SIMULATION_SUITE_PASS checks=14`. If launching fails before a report is created, provide the fixed error shown by the launcher or Windows and the application version; no screenshot is needed. Do not send the journal snapshot, personal photos, tokens or configuration.

## Troubleshooting and verification

- If the launcher cannot find its runtime, extract the complete ZIP again and launch from its `PocketPhotoBridge` folder. A system Java installation does not repair missing bundled files.
- If Explorer's console closes before you can read an error, run `--simulate-all` from PowerShell and inspect `$LASTEXITCODE`.
- If a repeated ordinary run prints `count=0`, it used saved state. Use the suite or a separate fresh synthetic experiment directory to see normal completion again.
- An unresolved, corrupt or unsupported journal fails closed. Keep its state and inspect the safe report; do not clear it to bypass the guard. Acknowledgement resolves uncertainty, not damaged state.
- This build is unsigned. Windows may display an unknown-publisher warning; check that the download came from the intended repository workflow before running it.

The Windows CI job runs Sony, printer and bridge tests before packaging. It checks the ZIP checksum, extracts into a path with spaces, removes `JAVA_HOME` and `PATH` from each EXE child's environment, and verifies normal, fault, duplicate, suite and separate-process crash/recovery behavior with a 60-second process limit. Safe stdout/stderr and exit-code reports are available in `bridge-verification-windows-x64`. A successful Linux/ARM64 workflow checks software execution on that architecture; it does not validate a board's radios or physical printing.
