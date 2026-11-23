# Design notes and test plan

## Sony receive and Gallery export

- `sony/`: platform-independent Kotlin `SonyTransferSession`, bounded SOAP/XML and selected-list parsing, fixed diagnostics and an injectable `CameraTransport`. XPushList/PushRoot support follows [the cited evidence](sony-protocol.md); unsupported responses stop without a PTP/IP or full-card fallback.
- `app/.../camera/network/`: Android Settings pairing, existing Wi-Fi route attachment and network-loss callback, HTTP bound to that specific Network with timeouts and no redirects/system proxy. Android owns saved Wi-Fi credentials; the app requests no QR, location, camera, media-read or storage permission.
- `app/.../photos/`: private cache, bounded/sample JPEG preview and unchanged-byte MediaStore export via a pending row. Abandoned cache is cleared at process startup; activity cleanup is isolated from other sessions.
- `MainActivity`: native Kotlin UI and foreground worker. Leaving the foreground cancels transport work and releases Wi-Fi callbacks. Camera network loss alone preserves a pending validated photo and permits a local Gallery save. **Print received JPEG** stages a separate private copy before opening the printer screen.

The user's version 0.1.1 RX100 VII / Android API 36 hardware test received one camera-selected JPEG (**13,805,942 bytes**), displayed it and saved it to Gallery. The camera acknowledged TransferEnd; Wi-Fi disconnected after the save. This validates that tested combination. An SD-card original hash comparison, repeatability and negative device tests remain outstanding; follow [the Sony tablet guide](tablet-testing.md).

## MVP 2 direct printer implementation

The reported printer SKU is **TEJ4007CN**, with Micro-USB and a sliding paper compartment. The implemented original Mi Portable `basil` profile produced its first physical print with 0.2.2 on the user's Android API 36 tablet on 2026-10-12. The trace reached an explicit matching job-finished response, and the user confirmed the sheet. Its reference reports XMKDDYJ01HM / XMKDDYJHT02; the user's exact firmware code and broader retail SKU/firmware equivalence remain unknown. See [protocol evidence and limits](xiaomi-protocol.md).

- `printer/`: JVM Kotlin framing/checksums, frame-local RC4, bounded JSON correlation, read-only diagnosis, one-copy job submission, JPEG upload, explicit matching job completion and an injectable read/write/close transport. `JpegHuffman` converts baseline JPEG entropy coding to standard Annex K Huffman tables without requantizing coefficients. Protocol evidence is MIT licensed and pinned at upstream [85335b639f3d818c3fe9a892e328864fc3d37250](https://github.com/fliptheweb/mi-portable-photo-printer/tree/85335b639f3d818c3fe9a892e328864fc3d37250); the APK contains its MIT notice.
- `app/.../printing/AndroidPrinterTransport`: secure public Android Bluetooth Classic RFCOMM SPP, bounded connection/read/write deadlines and socket cancellation. Android Settings owns pairing; the app selects from bonded devices and requests Nearby devices permission on Android 12+. Device names are shown locally; identifiers are not shared. No BLE printing or reflective channel fallback is used.
- `PrinterImages`: bounded local JPEG/PNG/WebP import and sampled decode, EXIF rotation/mirror handling, FIT or centered CROP on white at **1040×1560**, quality-90 baseline JPEG with standard Annex K Huffman tables, and preview of the prepared file. A generated orientation/color chart supports testing without the Sony camera.
- `PrinterActivity`: foreground-only selection, diagnose, image preview and explicit one-copy confirmation. The screen is secure against screenshots; the token field excludes saved state/autofill. Image files and placement survive rotation and Settings return, while tokens, device selection and active workers are not retained across recreation. Final screen cleanup deletes its private files; process startup clears abandoned printer cache/staging.
- `StoredPrintJobGuard`: synchronously persists a random non-secret unresolved reservation before job submission. Matching explicit completion or an established rejection resolves it. Disconnect, cancellation, timeout, malformed/lost replies and process death can retain it. A stale worker cannot resolve a newer reservation. New submissions remain blocked until completion or an explicit checked-printer acknowledgement.

Pairing and encryption are separate. The profile requires a device-specific **12-byte token (24 hexadecimal characters)** that the user already legitimately owns. It is accepted locally for one operation, cleared when consumed and on `onStop`, and never stored, logged, backed up or sent to a server. **Connect / Diagnose** without a token performs SPP connection diagnostics, then reports `KEY_REQUIRED` before encrypted commands. No verified tablet-only offline token bootstrap has been established. The application makes no Xiaomi Home/cloud request and invents no handshake, token or authentication bypass.

Printing performs preflight diagnosis, reserves one job, uploads the prepared JPEG, confirms that job and polls for its explicit finished response. Idle is insufficient evidence of completion. Known busy/error states stop without automatic retry; exact TEJ4007CN paper/cover/battery fault mappings await hardware testing. Operation cancellation closes the socket rather than sending an undocumented printer cancel command. `onStop` cancels foreground transport and clears the token; an accepted printer job may still produce a sheet. The full job deadline is bounded to four minutes.

## Verification and tablet acceptance

`.github/workflows/android.yml` pins Java/Gradle/SDK/actions, runs Sony and printer JVM tests, Android regressions, lint and a debug build, then publishes the APK/checksum and reports. Robolectric and synthetic transports verify software behavior; they are not printer hardware validation.

The first physical printer test passed separately from software verification. The user's follow-up on 2026-10-12 also confirmed downloading a photo from the Sony and printing it directly from the app, establishing the **Sony → Android → Xiaomi** flow on the tested setup. Gallery saving passed the earlier 0.1.1 test; saving after printing has not been separately reported. Detailed orientation/color/cropping checks, repeatability, hardware faults and cancellation/unknown-job recovery remain outstanding. Follow [the printer tablet guide](printer-tablet-testing.md) for those checks. For another printer, first pair/select and diagnose SPP, then attempt encrypted diagnosis and one manually confirmed print only if an existing owned token is available. Record safe diagnostics together with physical observations.

## MVP 4 headless coordinator and simulation

The new `:bridge` JVM application depends on the existing `:sony` and `:printer` modules. `CameraWifiConnector`, `CameraLink`, `PrinterConnector` and `PrintImagePreparer` isolate platform work. `PrintCoordinator` receives and validates the selected JPEG, prepares it and submits one guarded print for a supplied selection generation. Default adapters simulate the devices; version 0.4.1 adds explicit Windows live adapters. At MVP 4, Linux radio/event-source adapters were not implemented; the MVP 5 section below describes their addition. Android does not depend on this module or its Java desktop classes.

`FileBridgeJournal` holds a lifetime cross-process lock and writes checksummed, forced, atomically replaced snapshots. It consumes event digests before reception and persists the photo digest plus a reservation before `print_job`. Both digests survive resolution and manual acknowledgement, covering a crash between the finished reply and coordinator return. Unknown outcomes block new device actions; damaged/missing prior state fails closed. This guarantees at most one automatic submission for consumed events/submitted byte-identical JPEGs, with no exactly-once physical-output claim. In the Windows and simulation flows, conservative suppression includes known rejected submissions; a future intentional-reprint flow needs separate review. The autonomous Linux service's guard instead withdraws a busy (`-8006`) refusal: one durable update clears the reservation and the photo digest, so the queued photo is retried at the service loop's back-off (2 s doubling to 30 s after each failed attempt). Any other negative code is withdrawn the same way, and then the service drops the photo and its files from the queue (reported as `BRIDGE_QUEUE_RECONCILED count=1`), so the camera can send it again; the snapshot's `dropped` counts such drops since the last successful print, and the refusal counts as a failed attempt for the back-off. `reconcile()` reports every queue removal as `BRIDGE_QUEUE_RECONCILED count=N`.

`JvmPrintImagePreparer` uses sampled Java ImageIO decoding, all EXIF rotation/mirror cases, FIT/CROP placement and standard Huffman normalization. From 0.4.2, it applies EXIF first, then turns a remaining landscape image 90° clockwise to use portrait paper; portrait results are not turned again. Sources are JPEG-only, at most 64 MiB; headers, dimensions/pixels and decoded raster size are bounded. Output remains RGB white 1040×1560 baseline JPEG90 with Annex K tables and at most 4 MiB. Received originals are not rewritten. This desktop policy does not change Android image preparation.

`SimulatedSony` supplies descriptor/SCPD/PushRoot/DIDL and JPEG responses through the real receiver. `SimulatedPrinter` parses encrypted commands/chunks, validates the reassembled JPEG independently and returns fragmented version-1/type-6 replies with method notifications. A virtual clock exercises timeouts immediately. `SimulationRunner` verifies outcomes and submission counts for 15 scenarios; extracted-distribution smoke additionally halts one JVM after submission, then verifies another JVM blocks it. All fixture photos/keys are synthetic, and the simulation opens no real network/radio or cloud service.

The CLI runs directly through a Windows EXE with a bundled runtime, or through the Linux JVM distribution on Java 17. GitHub Actions tests Linux x64, Windows x64 and ARM64, alongside the existing Android checks. See [Windows instructions](windows-simulation.md), [future Linux deployment and hardware checklist](linux-bridge.md), [the design](superpowers/specs/2026-10-12-mvp4-headless-design.md) and [the plan](superpowers/plans/2026-10-12-mvp4-headless.md). Physical Orange Pi routing, pairing, event detection, coexistence and printer output remain pending hardware arrival.

`WindowsLive` is an explicit interactive mode with separate physical-job state. Its Windows adapters discover a selected camera link and bind bounded HTTP sockets to that link's IPv4 source; paired printers use JNA-mapped Classic RFCOMM/SPP with Windows SDP lookup and provider-select readiness. Per-connection fixed stages and native error numbers are exported without identifiers or raw exception text. The same coordinator and printer session run the full flow, while a prepared preview is retained locally and the user must type PRINT before opening the printer connection. Receive-only and status-only operations consume no paper. Token input is hidden and ephemeral. Local selection/path UI is separate from exported diagnostics. See [the Windows hardware procedure and result](windows-live.md): the user confirmed Sony receive/save with 0.4.1 and the complete Sony → Windows → Xiaomi print with 0.4.3 on the tested setup. Physical Linux/Orange Pi integration remains untested.

## MVP 5 autonomous service

`AutonomousBridge` separates capture from queued printing. A validated original
and prepared image are committed to a private, bounded, checksummed queue inside
Sony's validation callback, before TransferEnd. Camera acknowledgement failure
does not discard a captured photo. Printer-off/busy errors before reservation
retain the queue; after possible job acceptance the existing `FileBridgeJournal`
blocks automatic replay until a physical check. Completed/tombstoned photos are
removed from the queue without deleting duplicate suppression history.

The native Linux service applies the saved `keepQueueAcrossBoots` choice once
per kernel boot, before setup opens. It defaults to false, including older
configurations: a new boot discards only unsubmitted waiting photos. The private,
checksummed boot marker is committed after queue cleanup, so reopening the bridge
or restarting its service within the same boot retains new captures. Opting in
keeps waiting photos across boots. Submitted-photo tombstones and unresolved
reservations are always preserved; a discarded, unsubmitted JPEG may be received
again. Simulation and the separate Windows/Android workflows retain their prior
restart behavior.

The first service upgrade adopts a queue with no boot marker without deleting
it; subsequent known boot changes apply the chosen policy. Boot identity comes
from Linux's [stable per-boot boot_id](https://docs.kernel.org/admin-guide/sysctl/kernel.html#random),
not a per-process random value.

`ServiceRuntime` composes this controller with `ServiceLoop`, `SetupServer` and
platform device adapters. First boot waits in setup. Configured boots offer a
60-second setup window; authenticated interaction holds it open. A one-time
auto-print opt-in enables operation. Linux closes the HTTP listener and hotspot
before joining the camera; reboot makes setup reachable again. There is no
per-photo prompt in normal operation. A single worker receives before printing,
so a long print can delay the next camera transfer. An uncertain job requires
checking the printer via setup; it is never automatically resubmitted.

Several photos can be sent in one power-on. Each loop pass scans for the camera
network once; the result is present, absent or unknown (the scan failed). An
unknown scan neither checks the camera nor starts a new selection generation;
the print step still runs and the next successful scan decides. A presence that
follows an observed absence starts a new generation and is checked at once.
After a successful transfer the camera usually stays visible, offering its last
selection; the loop is then in re-check mode. It checks the camera again on the
next pass after a successful print, because nothing is scanned during a print
(up to four minutes), and otherwise after a pause counted from the start of the
pass that ran the previous check: `FIRST_RECHECK_MS` (60 s), doubling after
every check that finds only a known photo or fails before a photo is
fingerprinted, up to `MAX_RECHECK_MS` (300 s). An observed absence or a newly
queued photo resets the pause. Before the first successful transfer of a
presence, and while the queue is full or a job needs attention (no radio
connection is made), the check is retried every 5 seconds. Only a newly queued
photo resets the print back-off. The SHA-256 of the received bytes, checked
against the queue and the print history before any decoding, stays the only
duplicate guard, so a repeated selection is downloaded again in full (about
11-14 MB and a Wi-Fi join per check) but never printed twice. A photo dropped
after a refusal is also treated as known for the rest of its generation, in
memory only, so a re-check does not submit it again; the owner sends it again
after the camera network has gone away. Presence is logged only when it changes:
`SERVICE_CAMERA_PRESENT`, `SERVICE_CAMERA_ABSENT`, or `SERVICE_CAMERA_WAIT`
with the scan's failure category. The simulated devices use 2 s and 10 s pauses
so the packaged smoke test can send a series within its waits.

**Deliberate deviation from the MVP 4 design.** The [MVP 4 design](superpowers/specs/2026-10-12-mvp4-headless-design.md)
required waiting for the camera network to disappear before rearming. In the
code that meant a new photo was accepted only after a scan without the camera;
no scan ran during a transfer or a print (up to four minutes) and a failed scan
reused the earlier result, so a photo chosen quickly or during a print was not
fetched until an absence was seen; this is reasoning from the code, not a board
observation. The owner replaced that rule with the re-check above (issue #20).
The 60 s and 300 s values are starting points for the first field session,
which measures scan duration, how long the camera keeps its network after a
completed transfer and what a second connection does on the camera screen.

The local page uses generated credentials, sessions, CSRF and exact Host/Origin
checks. Credentials are private persisted configuration, never diagnostic
content or command arguments. Linux NetworkManager profiles are root-private
temporary keyfiles. BlueZ pairing and bounded `sdptool` discovery precede native
RFCOMM through libc/JNA; no channel is assumed and no GPL libbluetooth is linked.
The pilot systemd service has administrative radio access and is intended for
the dedicated bridge appliance. See [adapter provenance and limits](linux-protocol-adapters.md).

`--simulate-service` uses the same controller/protocol sessions and a loopback
setup page with synthetic camera/printer controls. Its page remains reachable
during simulated operation because it does not switch a real radio. Android and
the manual Windows workflow are unchanged. Windows EXE and Linux ARM64 packages
bundle the runtime; see [deployment and testing](mvp5-deployment.md) and the
[approved design](superpowers/specs/2026-10-12-mvp5-autonomous-linux-design.md).

## Data handling and future portability

Private camera originals and prepared printer copies have separate ownership; printing does not alter Sony Gallery bytes. Shared diagnostics contain fixed stages, bounded progress and error categories, not raw replies, Bluetooth identities, credentials or photo contents. Sharing is a manual user action. The durable guard contains only a non-secret reservation and is deliberately retained across restart.

The `sony` and `printer` JVM modules remain separate from Android routing, Bluetooth, image decoding and UI. MVP 4 reuses them in the headless coordinator and simulation; actual Linux radio adapters, a battery or enclosure remain outside this Windows-testable milestone.
