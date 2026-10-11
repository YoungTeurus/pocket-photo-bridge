# Design notes and test plan

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
