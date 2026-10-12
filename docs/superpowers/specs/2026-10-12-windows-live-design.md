# Windows real-device testing extension

The user completed the portable simulation but wants a meaningful RX100 VII → Windows → TEJ4007CN physical test before the Orange Pi arrives. Windows x64 has Bluetooth; the printer is not paired yet. No local compilation, JDK, WSL or Xiaomi Home is required for the delivered workflow. Preserve Android and the offline simulation.

## Options and selected approach

1. **Direct Windows adapters (selected):** use Kotlin/JVM for the existing coordinator, sessions and image preparation; Windows network configuration discovery plus locally bound camera HTTP; JNA maps Windows paired-device enumeration and Winsock Bluetooth Classic RFCOMM. Windows resolves the SPP service UUID using SDP, without an invented channel or token. Keeps the portable Java package and avoids a second application runtime.
2. Windows Bluetooth virtual COM ports: simpler byte streams, but depends on driver-created outgoing COM ports and an extra serial library. Avoid requiring a COM port that may not exist on this printer/driver.
3. A separate WinRT/.NET broker: useful for richer Windows UI but adds another runtime and process boundary. Defer unless native Winsock proves incompatible on the physical PC.

## User flow

The new `WindowsLive.cmd` launches `PocketPhotoBridge.exe --windows-live`. Default EXE simulation remains unchanged. The live console offers receive-only, printer-status-only, receive-and-print, read-only native diagnostics and checked-printer acknowledgement. User manually joins the Sony AP and pairs the printer in Windows Settings. No unattended pairing or network switching.

Camera selection is a numbered local interface/gateway choice. Receive-only saves an unchanged original and prepared preview under the real-device state directory; it never connects a printer. The full operation uses the existing PrintCoordinator, saves a local preview during preparation, then requests the exact word PRINT before opening the printer transport. No retries or repeat confirmation jobs. A local preview can be opened in the Windows image viewer. Separate receive-only and full-flow tests require a fresh camera selection.

Printer selection shows paired names only in the local UI. A token is entered with a hidden Console.readPassword prompt, is never accepted as an argument or logged, and is not persisted. Device addresses, interface identities, token, photo paths and payloads are excluded from exported diagnostics. The proven PrinterSession implements encrypted status and one-copy printing.

## Adapters and boundaries

`WindowsCameraNetworking.candidates(): List<WindowsCameraCandidate>` and `connect(candidate): CameraLink` discover bounded local configuration with a fixed PowerShell script and bind HTTP sockets to the selected IPv4 address. Targets remain the selected private camera address, no DNS, proxy or redirect. The real Sony session still discovers DmsDescPush/SCPD and receives exactly PushRoot.

`WindowsBluetooth.pairedDevices(): List<WindowsPairedDevice>` and `connect(device, timeoutMillis=15000): PrinterTransport` enumerate authenticated/remembered devices without inquiry. Winsock uses AF_BTH, RFCOMM and the SPP UUID, bounded nonblocking connect/read/write, partial writes, cancellation and idempotent closure. Native ABI mapping follows the Microsoft SDK declarations (SOCKADDR_BTH is packed to 30 bytes). No BLE or cloud dependency.

Real state is separate from simulation. Full printing uses the existing forced/atomic journal, lifetime lock and durable content tombstones. Manual fresh selection IDs do not bypass content suppression. Unknown outcomes block later physical jobs until explicit checked-printer acknowledgement; acknowledgements retain attempted-photo suppression. Capture-only cancellation is reported as successful receive, with zero submitted prints. Cancelling before confirmation never opens a printer or reserves a job.

## Evidence and delivery

Unit tests cover unsafe camera routing/HTTP framing, native layouts and mocked socket failures, and real-session orchestration with injected UI/devices. Windows CI validates extracted runtime, JNA loading/no-radio behavior and refusal without a live console, while preserving all simulation checks. A hosted runner cannot establish physical Bluetooth or camera compatibility. Mark the live mode WINDOWS_HARDWARE_VALIDATION_PENDING until user tests it. Extend PR #7 and deliver a new portable build with exact pairing, receive/status/print and diagnostic steps. Linux radios remain outside this extension.
