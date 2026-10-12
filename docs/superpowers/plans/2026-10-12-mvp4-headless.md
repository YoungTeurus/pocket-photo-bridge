# MVP 4 Headless Bridge Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for the coordinator inline. Independent image preparation and packaging tasks may use superpowers:dispatching-parallel-agents. Finish with one fresh whole-branch review.

**Goal:** Deliver a portable Windows EXE/runtime ZIP whose deterministic offline simulation exercises the real headless coordinator and proven protocols.

**Architecture:** Add `:bridge` with injected camera/printer adapters, portable Java ImageIO preparation and a durable file-backed print guard. Simulated wire devices feed the existing `:sony`/`:printer` sessions. Linux JVM packaging and hardware deployment templates keep platform-specific radio work explicit and pending.

**Tech Stack:** Kotlin 2.2.21, Gradle 8.13, Java 17, JUnit 4; Java ImageIO, file channels, jlink and jpackage; existing Gson protocol dependency.

**Spec:** `docs/superpowers/specs/2026-10-12-mvp4-headless-design.md`.

## Global constraints

- Base `d736caa`, merged PRs #2/#4. Preserve Android and existing Sony/printer protocol behavior.
- Source limit 64 MiB; subsampled decode approximately 2048 pixels/axis; RGB white 1040×1560 baseline JPEG90, Annex K Huffman, output at most 4 MiB.
- Simulation only on Windows; no real tokens, radio access or network/cloud/photo uploads.
- Durable reservation before print_job; consumed events and submitted-photo fingerprints survive restarts, resolution and manual acknowledgement. Never auto-retry an uncertain job.
- Windows 10/11 x64 ZIP includes the EXE and its full Java runtime. Linux JVM distribution requires matching Java 17; Orange Pi hardware validation remains pending.

## Review focus

- Crash after a finished reply but before coordinator return must not make the same photo eligible again.
- Corrupt journals, interrupted replacement and concurrent instances must stop before device actions.
- Repeated image bytes on a new selection generation must be suppressed without exporting their fingerprint.
- EXIF mirrors, malformed metadata and huge input dimensions must not corrupt orientation or allocate an unbounded bitmap.
- Extracted EXE must work from a path containing spaces with no system Java, including failure/nonzero exits and process restart.

---

### Task 1: Coordinator and durable guard

**Files:** create `bridge/build.gradle.kts`, `bridge/src/main/kotlin/dev/pocketphotobridge/bridge/{BridgePorts,FileBridgeJournal,PrintCoordinator}.kt` and matching journal/coordinator tests; modify `settings.gradle.kts` for `:bridge` and explicit headless-only builds.

**Interfaces:** `SelectionTrigger(id: String)`; `CameraWifiConnector.connect(trigger): CameraLink`; `CameraLink(endpoint, transport, close)`; `PrinterConnector.connect(): PrinterTransport`; `PrintImagePreparer.validateSource(source: File)` / `prepare(source: File, crop: Boolean): ByteArray`; `FileBridgeJournal(directory: Path): PrintJobGuard, AutoCloseable`; `PrintCoordinator.process(trigger): BridgeResult`.

- [x] Write tests for reservation/reopen, stale resolution, corruption, concurrent owners, resolution/crash-window suppression and manual acknowledgement retaining tombstones. Observe RED, implement checksummed atomic snapshots and lifetime locking, observe GREEN.
- [x] Write coordinator tests for receive/prepare/print, duplicate generation/content, error privacy and pre-submission versus uncertain failure. Observe RED, implement real-session orchestration and lifecycle cleanup, observe GREEN.
- [x] Verify `:sony:test :printer:test :bridge:test` and commit the independently testable core.

### Task 2: Portable image preparation

**Files:** create `bridge/src/main/kotlin/dev/pocketphotobridge/bridge/images/JvmPrintImagePreparer.kt`, focused EXIF helper if needed, and `bridge/src/test/kotlin/dev/pocketphotobridge/bridge/images/JvmPrintImagePreparerTest.kt` with synthetic resources.

**Interfaces:** implements Task 1's `PrintImagePreparer`; `JvmPrintImagePreparer()` requires no Android classes or native platform setup.

- [x] Write tests for exact output dimensions/baseline/DHT, FIT/CROP pixels, original preservation, all eight EXIF cases, invalid/truncated metadata, oversized dimensions/bytes and cancellation. Observe RED; implement bounded ImageIO/subsampling/orientation plus existing layout/Huffman normalization; observe GREEN.
- [x] Run image tests and bridge suite; record evidence and integrate without modifying Android.

### Task 3: Simulated wire devices and CLI

**Files:** create `bridge/src/main/kotlin/dev/pocketphotobridge/bridge/simulation/{SimulatedSony,SimulatedPrinter,SimulationRunner}.kt`, `BridgeCli.kt`, synthetic selected-JPEG resource and matching integration/CLI tests.

**Interfaces:** Task 1 ports; `SimulationRunner.run(scenario, stateDir, crop, report): SimulationResult`; CLI main class `dev.pocketphotobridge.bridge.BridgeCliKt`, version `0.4.0`; fixed scenario names and outcomes shared with packaging smoke script.

- [x] Write integration tests proving actual descriptor/SCPD/PushRoot transfer, version-1/type-6 encrypted fragmented replies, complete JPEG reassembly and one-copy submission. Observe RED; implement fake devices; observe GREEN.
- [x] Add normal/failure/duplicate/restart/manual-recovery scenarios and privacy assertions. Use virtual printer clock; test never-finished job timeout without real sleep.
- [x] Write CLI tests for help, defaults, suite success, fault nonzero/unknown exits, unsafe argument/exception redaction and hardware refusal. Implement safe reports and interactive default behavior.
- [x] Add a process-level power-cut experiment (exit 71 after reservation/acceptance) and verify a fresh process on the same state refuses new jobs. Verify full JVM suite and CLI commands, commit.

### Task 4: Windows/Linux distributions and user instructions

**Files:** create `.github/workflows/bridge.yml`, `bridge/packaging/{package-windows,smoke-windows}.ps1`, distribution/runtime license inclusion, `docs/windows-simulation.md`, `docs/linux-bridge.md`, `deploy/linux/pocket-photo-bridge.service.example` and configuration example; update README and architecture.

**Interfaces:** Gradle `:bridge:installDist`, `:bridge:distZip`/`distTar`; Task 3 CLI options; Windows application name `PocketPhotoBridge`, app version `0.4.0`.

- [x] Implement pinned Linux/Windows/ARM64 JVM test jobs. Leave existing Android workflow unchanged.
- [x] Build Windows app-image with bundled Java desktop runtime; ZIP the whole directory and checksum it. Execute extracted EXE with no system Java from a path containing spaces, check all scenarios, nonzero faults and cross-process crash/restart suppression.
- [x] Execute Linux JVM distribution on ARM64 hosted runner where available and upload an architecture-independent distribution plus reports.
- [x] Write download/extract/Explorer/PowerShell instructions, expected safe report contents and troubleshooting. Document known-SSID NetworkManager, Classic SPP/BlueZ, owner-only future secrets, conservative trigger semantics and an inactive systemd template; clearly state radios remain unimplemented/unvalidated.

### Task 5: Whole-branch verification and PR

- [x] Run Sony/printer/bridge/app unit tests, Android lint/debug build, CLI suite and distribution smoke checks. Verify no Android/protocol source change and no secret/private fixture is included.
- [x] Dispatch one independent whole-branch review; reproduce/fix important findings with RED/GREEN regressions and rerun required checks.
- [x] Publish feature branch, open PR targeting main, verify Windows and ARM64 Actions results and downloadable artifacts, and provide exact Windows ZIP instructions. Do not merge or claim physical Linux success.

## Progress and decisions

- Read Issue #6, merged PRs #2/#4, existing protocol sessions/transport and hardware evidence. Worktree `.worktrees/mvp4`, branch `feat/mvp4-headless-windows-simulation`, baseline `d736caa`.
- User explicitly requests documenting architecture/plan first and then implementing. Proceed through these artifacts without an additional approval pause.
- Conservative content suppression includes known rejected submissions; an explicit future reprint flow is outside this milestone. The current manual acknowledgement unlocks fresh different content and never replays the uncertain image.
- The user confirmed Windows x64 with WSL available. WSL remains optional; portable Windows testing uses the EXE and its bundled runtime directly. The final hardware platform remains Linux ARM64.
- Journal tests reproduced a POSIX locking hazard: closing a rejected second in-process file descriptor released the first owner's OS lock. A canonical in-process ownership registry now rejects that open before creating a descriptor; a fresh subprocess verifies the lock remains held (behavioral RED/GREEN).
- Image task: 14 tests, including 8 expected behavioral failures before implementation; all eight EXIF cases, exact independent DHT/DQT, FIT/CROP/source integrity and large sampled decode under a 64 MiB heap pass. Source JPEG headers are capped at 1 MiB, axes at 32768, pixels at 268,435,456 and decoded axes at 2048.
- Device task: 24 real-session/negative-contract tests pass. Additional RED/GREEN checks verified accepted-but-unconfirmed reconnect status and malformed JPEG decode classification. No real radios or credentials are used.
- Cloud verification: 176 tests (24 Sony, 43 printer, 72 bridge, 37 app), zero failures/errors/skips; Android lint/debug build passed. Extracted Linux distribution smoke passed all 24 process checks, including power-cut exit 71, restart exit 3, acknowledgement and suppression. Initial declaration-only APIs/missing new classes were recorded separately from behavioral RED/GREEN regressions.
- Independent whole-branch review found no Critical or Important defects. Corrected the one Minor configuration comment: `--hardware --config` accepts a path but refuses hardware operation without reading it. Native Windows and ARM64 Actions verification was the delivery gate.
- Resource review: an 8192×8192 progressive JPEG decoded under a 64 MiB Java heap but used approximately 292 MiB process RSS because native decoder allocations are outside the heap. Measure progressive JPEG process RSS on the board before fixing its production memory budget; the heap test does not establish an RSS cap.
- Published [PR #7](https://github.com/YoungTeurus/pocket-photo-bridge/pull/7), left open for review. [Headless bridge run #2](https://github.com/YoungTeurus/pocket-photo-bridge/actions/runs/38236814491) passed on source revision `56d47c9`: Windows extracted/bundled-runtime smoke passed 25 checks; Linux x64 and ARM64 each passed 24 checks. The portable Windows ZIP/checksum, JVM archives and all three verification reports were uploaded successfully. [Android run #23](https://github.com/YoungTeurus/pocket-photo-bridge/actions/runs/38236814246) also passed. This records software verification only; physical Orange Pi integration remains pending.
