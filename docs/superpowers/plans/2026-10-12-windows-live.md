# Windows real-device testing implementation plan

> **For agentic workers:** Use superpowers:executing-plans for integration and superpowers:dispatching-parallel-agents for the independent camera and Bluetooth adapters. Finish with one fresh whole-branch review.

**Goal:** Deliver a portable Windows build that can attempt the selected Sony JPEG → real Xiaomi printer flow, with explicit print confirmation.

**Architecture:** Implement Windows adapters behind existing ports; keep proven sessions, image preparation, coordinator and durable guard. Separate interactive live UI, secrets and photo retention from safe diagnostic reporting and offline simulation.

**Tech Stack:** Kotlin/JVM Java17, pinned JNA5.17.0, Windows native Bluetooth APIs/Winsock, Windows PowerShell network discovery, jpackage/jlink.

**Spec:** `docs/superpowers/specs/2026-10-12-windows-live-design.md`.

## Global constraints

- Preserve app/sony/printer source and the complete offline simulation.
- Windows x64, no local compiler, no WSL/JDK prerequisite for user.
- Physical printing requires a hidden user-owned token and exact PRINT confirmation; no token arguments, persistence or invented secrets.
- Separate real state and simulation; reserve before submission; never retry possible acceptance; acknowledgement retains tombstones.
- No Linux radio implementation or claimed physical success before user validation.

## Review focus

- Unsafe/private-but-wrong route, proxy and redirected URLs cannot send camera traffic through a different interface.
- Native SOCKET width, packed BTH address, GUID byte order, timeouts and partial writes are correct on Windows x64.
- Closing or cancelling during connect/write never leaks a socket or makes an accepted job retryable.
- Token, malicious device name, personal path and native exception text never reach exported reports.
- Explorer console and JNA work with only bundled runtime; no live console causes refusal before any device action.

### Task 1: Camera adapter

**Files:** create `bridge/src/main/kotlin/dev/pocketphotobridge/bridge/windows/WindowsCameraNetworking.kt`, `BoundCameraHttpTransport.kt`, matching tests.

**Interfaces:** `WindowsCameraCandidate(interfaceIndex: Int, displayName: String, sourceAddress: String, gateway: String)`; `WindowsCameraNetworking.candidates(): List<WindowsCameraCandidate>`; `connect(candidate): CameraLink`.

- [x] Write/run RED tests for configuration parsing, private/canonical endpoints, locally bound GET/POST, length/chunked framing, redirects, truncation and cancellation.
- [x] Implement fixed bounded PowerShell discovery and source-bound transport; use real SonyTransferSession without modifying :sony.
- [x] Run tests GREEN and commit only this task's files.

### Task 2: Bluetooth adapter

**Files:** create `bridge/src/main/kotlin/dev/pocketphotobridge/bridge/windows/WindowsBluetooth.kt`, focused supporting files and matching tests. Parent adds JNA dependency/notices.

**Interfaces:** `WindowsPairedDevice(address: Long, name: String)`; `WindowsBluetooth.pairedDevices(): List<WindowsPairedDevice>`; `connect(device, timeoutMillis: Int = 15000): PrinterTransport`; `nativeSelfTest(): Unit` verifies Winsock without requiring a physical radio.

- [x] Write/run RED tests for ABI layouts/GUID, partial writes, timeout, disconnect and idempotent close; verify SDK declarations.
- [x] Implement lazy Windows x64 native mappings, paired selection enumeration and nonblocking RFCOMM/SPP with service UUID/SDP.
- [x] Run tests GREEN; distinguish no-radio limitations from actual native load success; commit only task files.

### Task 3: Live workflow and portable delivery

**Files:** create `windows/WindowsLive.kt`, safe live report/UI/retained preview helper and tests; modify CLI dispatch/options/help, bridge build/notices, package/smoke/workflow versions and docs; add `WindowsLive.cmd`.

**Interfaces:** `WindowsLive.run(crop: Boolean, stateDir: Path?): Int` uses injectable local UI/device boundaries for tests. Safe report is separate from interactive prompts and saves `diagnostics.txt` under real state.

- [x] Write/run RED tests for receive-only no printer, status-only no job, confirmation cancellation, full existing-coordinator print, duplicate suppression, uncertain outcomes/acknowledgement and privacy.
- [x] Implement explicit live mode, console/OS gate, hidden token, local device/interface menu, photo retention/preview and all guard policies.
- [x] Pin JNA and include notices; increment desktop to 0.4.1 while leaving Android0.2.2. Extend native smoke and preserve simulation checks.
- [x] Document Windows pairing, camera sharing/manual join, status probe, full one-print flow, safe diagnostics and physical validation pending.
- [ ] Run complete JVM/Android tests, lint/APK, local distribution smoke; review, update PR #7 and verify native Windows/Linux ARM64 artifacts before delivery.

## Progress

- User accepted the proposed real Windows receive/status/explicit-print extension after successful 0.4.0 simulation. Bluetooth is available and Windows pairing is pending. Existing worktree/PR #7 is isolated and open.
- Baseline `ea10a19`: all 176 unit tests, Windows25 smoke checks, Linux x64/ARM64 24 each and Android build/lint passed.

- Camera adapter:17 tests passed, including socket source binding under NAT, cancellation and independent blocked-write deadline. Bluetooth adapter:32 tests passed, including JNA ABI/deadlines/partial writes/concurrent closure and bounded lock wait.
- Live workflow:8 tests plus3 CLI tests passed. Linkage failure after an accepted job first returned2 (RED); now rechecks the durable physical journal and reports unknown outcome/exit3 (GREEN). No raw native messages are exported.
