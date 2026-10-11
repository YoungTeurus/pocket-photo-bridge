# Design notes and test plan

## MVP 1 implementation

- `sony/`: platform-independent Kotlin `SonyTransferSession`, bounded SOAP/XML and selected-list parsing, fixed diagnostics and an injectable `CameraTransport`. Experimental XPushList/PushRoot support follows [the cited evidence](sony-protocol.md); no PTP/IP or PhotoRoot fallback.
- `app/.../camera/network/`: Android Settings pairing, existing Wi-Fi route attachment and network-loss callback, HTTP bound to that specific Network with explicit timeouts and no redirects/system proxy. Android owns saved credentials; the app requests no location, QR, camera, media-read or storage permissions.
- `app/.../photos/`: private per-activity cache, bounded/sample JPEG preview, and unchanged-byte MediaStore export via a pending row. Abandoned cache is cleared at process startup; activity cleanup is isolated from other sessions.
- `MainActivity`: native Kotlin UI and one foreground worker, with HTTP close/drain on a separate cleanup executor. User actions expose Connect, Diagnose, Receive, Cancel/Disconnect, preview, Save and manual redacted-log sharing. Exiting the foreground cancels work and releases Wi-Fi callbacks/HTTP; activity recreation clears the preview. Network loss alone closes HTTP without invalidating a pending validated photo or interrupting a local Gallery save.
- `.github/workflows/android.yml`: pinned Java/Gradle/SDK/actions, unit tests, lint, downloadable sideloadable debug APK/checksum and verification reports.

The manual connection path is intentional for the initial tablet experiment. No app-managed credential store or guessed QR parser is needed. If a later release adds programmatic pairing, credentials must use Android-secure storage and the QR format must first be observed on hardware.

Software build/tests alone do not establish camera compatibility. The user's 0.1.1 RX100 VII / Android API 36 test confirmed the basic selected-JPEG receive, preview and Gallery export flow. [Remaining tablet acceptance steps](tablet-testing.md) cover an older selected JPEG, full-original comparison, repeatability and interruption/multiple-selection behavior before Issue #1 is closed.

## Phase 1: receiving a camera-selected JPEG

Expected user journey:
1. On Sony RX100 VII, open **Send to Smartphone → Select on This Device** and pick an image.
2. Camera exposes Wi-Fi connection details (QR/SSID and possible password).
3. Android app joins the camera network on an explicit user action (and reuses saved details where possible).
4. App performs the Sony handshaking required to receive **the selected** JPEG, not an arbitrary/latest file.
5. Show preview, metadata (if available), and **Save** to Android gallery.

### Modules

- `camera/network`: Android Wi-Fi permission prompts, network request or suggestion, per-network socket binding, state transitions.
- `camera/sony`: discovery + protocol transport, selected-photo transfer, retries, timeouts and structured diagnostics.
- `photos`: memory-efficient JPEG handling, temporary storage and gallery MediaStore export.
- `ui`: minimal Kotlin/Compose screens for pairing, connect, session diagnostics, photo preview and Save.

### Key uncertainties to investigate

- Sony RX100 VII's exact camera-selected transfer protocol/port/handshake. Do not assume port 15740 or PTP/IP without evidence.
- Whether the camera network credentials remain stable across reboots; validate on device.
- Wi-Fi connection lifecycle on Android versions with restricted network switching, especially when the camera's hotspot has no internet.
- Whether the camera requires a pre-existing app pairing token beyond SSID/password.

### Milestone acceptance

GitHub Actions builds an installable debug APK; app starts on Android; user can connect to the camera network and see status/logs. The end-to-end selected-JPEG flow is validated with real hardware or documented as an explicit blocker with a diagnostic test plan. Do not falsely mark transfer complete.

## Future phases

**Xiaomi Bluetooth:** early Mi Portable Photo Printer with micro-USB and sliding paper cover. Verify exact model from label; investigate community reverse engineering (for example, fliptheweb/mi-portable-photo-printer), pairing, crypto/token and direct Android printing.

**Standalone bridge:** evaluate a Wi-Fi + Bluetooth Linux SBC such as Pi Zero 2 W, rechargeable battery, power budget and printer-side mounting. Reuse protocol understanding, not Android-specific networking code.

## Data handling

- Camera Wi-Fi password stored in Android-secure storage when needed; not in repository.
- Never export raw pairing QR, Wi-Fi key, location or images in logs.
- Keep architecture suitable for future Linux implementation.
