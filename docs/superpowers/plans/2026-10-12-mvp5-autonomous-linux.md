# MVP 5 implementation plan

Goal: automatic selected-photo printing on Linux, with a dedicated local setup
page and a fully testable Windows service simulation.

Architecture: durable capture/print controller + authenticated local setup API +
platform radio adapters; reuse protocol modules and image preparation.

Stack: Kotlin/JVM 17, JDK HttpServer, JNA / BlueZ, NetworkManager, systemd,
GitHub Actions, jlink/jpackage. Spec:
`docs/superpowers/specs/2026-10-12-mvp5-autonomous-linux-design.md`.

Global constraints: preserve Android and manual Windows behavior; no actual print
from simulation; no fabricated secrets/channels; no automatic uncertain retry;
no credentials in logs/URLs/arguments; private bounded persistent state; no local
user compilation; Linux hardware validation remains explicitly pending.

Review focus: use the complete Review focus section of the spec, especially crash
boundaries, HTTP credential handling, native errors and mode transitions.

## Task 1: durable controller (parallel domain)

- [ ] Write failing tests in `bridge/src/test/.../service/` for retained capture,
  restart recovery, busy/offline retry, interrupted receive, unknown job, durable
  acknowledgement, duplicates and corrupt/bounded state.
- [ ] Add `service/AutonomousBridge.kt` and private queue persistence. Expose
  synchronized capture(trigger), printNext(), snapshot(), acknowledgeCheckedPrinter()
  and close(). Receive via SonyTransferSession and print via PrinterSession.
- [ ] Commit capture inside validateJpeg before TransferEnd. Reuse FileBridgeJournal
  for reservation and tombstones. Report only fixed stage/category/counts.
- [ ] Run focused tests RED then GREEN. Expected: all behaviors above pass.

## Task 2: configuration and setup UI (parallel domain)

- [ ] Write failing tests for private atomic configuration/bootstrap, validation,
  sessions, CSRF, Host/Origin checks, redaction and setup lease.
- [ ] Add `setup/` config types/store/bootstrap and JDK HttpServer UI. Inject
  SetupBackend callbacks so this domain does not depend on radio/controller code.
  Fields: camera SSID/password, paired printer address/token, Wi-Fi interface,
  auto-print opt-in and fit/crop. Status never returns credentials.
- [ ] Test real loopback HTTP requests. Expected: authenticated safe operations
  succeed; unauthorized/cross-origin/oversize/injected inputs are rejected.

## Task 3: Linux radio adapters (parallel domain)

- [ ] Research primary Linux BlueZ/NM interfaces and license provenance. Write
  failing tests for fixed bounded commands, credentials, exclusive modes, private
  route discovery, native sockaddr/SDP, partial IO and timeouts.
- [ ] Add `linux/` adapters independently from protocol sessions. Discover the SPP
  channel using SDP; use saved native errno. Never assume channel 1. Profile
  passwords are protected files, not command arguments. Expose setup start/stop,
  camera presence/link, paired list/pair and printer connector to bootstrap.
- [ ] Add read-only native self-check. Expected: native mappings verified without
  a physical camera/printer; unavailable radios yield safe pending diagnostics.

## Task 4: application integration and service simulation (root)

- [ ] Write failing CLI/mode/simulation tests, then implement service bootstrap,
  exclusive setup-to-operation transition, boot window, shutdown and bounded
  reconnect loop. Extend CLI without changing existing manual commands/default.
- [ ] Add Windows loopback setup simulation using synthetic Sony/Xiaomi wire
  devices and real durable controller. Include unattended operation and restart
  scenarios. Expected: deterministic single print after printer availability,
  unknown jobs block, interrupted downloads never print.

## Task 5: packaging and deployment (parallel domain)

- [ ] Extend Windows package launchers and Linux ARM64 bundled-runtime packaging;
  include jdk.httpserver, private bootstrap, systemd installer, required OS package
  list and no-JDK smoke checks. Bump only bridge to 0.5.0.
- [ ] Add extracted-package service/HTTP smoke checks and GitHub Actions artifacts.
  Expected: Windows EXE and ARM64 shell launcher operate with system Java removed.
- [ ] Document Windows simulation, Windows-to-Pi SSH installation, first setup,
  normal power-order workflow, safe unknown-job acknowledgement and redacted
  diagnostics to return. Explain Linux radio validation limits.

## Task 6: verification, review and delivery (root)

- [ ] Run all JVM tests, Android tests/lint/debug build, package scripts and YAML
  validation. Read the results; fix meaningful regressions.
- [ ] Fresh-context whole-branch review; reproduce important findings RED/GREEN
  and run the complete suite after fixes.
- [ ] Publish feature branch and review PR based on the working unmerged MVP4
  branch (or main if merged), verify Actions artifacts and provide download links.

Primary test command (Cloud): `./gradlew -PheadlessOnly=true :sony:test
:printer:test :bridge:test --max-workers=2`; final Android verification uses
`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with the Cloud SDK.
