# Pocket Photo Bridge

A pocket-sized workflow to transfer a camera-selected photo from a **Sony RX100 VII** to Android, then (later) print it on a **Xiaomi Mi Portable Photo Printer**.

## Roadmap

- **MVP 1 — Sony → Android:** select a JPEG on the camera via **Send to Smartphone → Select on This Device**, receive that exact photo in an Android app, preview it and save to Gallery. This is the *only* feature to implement now.
- **MVP 2 — Android → Xiaomi:** investigate direct Bluetooth printing, device identity, encryption/pairing and paper formats.
- **MVP 3 — Battery-powered Linux bridge:** evaluate Raspberry Pi Zero 2 W and a small enclosure that can sit on the printer.

## Important constraints

- Sony model: DSC-RX100M7. The camera advertises its own Wi-Fi access point and shows QR connection details. Credentials can be entered once and reused where Android permits.
- The user develops from an **Android tablet** using **Codex Cloud + GitHub**. A GitHub Actions workflow must compile a downloadable **debug APK**; no local Android Studio required.
- The precise Sony **camera-selected / camera-initiated** transfer handshake is not yet verified. A phone-initiated media browser (PTP/IP or Sony API) is *not* proof that this particular send mode works. Research first and instrument testable diagnostics.
- Manual foreground Connect/Receive with Android-approved Wi-Fi prompts is acceptable for the MVP. Background Wi-Fi polling and silent auto-connect are stretch goals.
- Do not log or commit camera QR contents, Wi-Fi passwords, Bluetooth tokens, or personal photo content. Make diagnostics safe to share.
- No printer functionality in MVP 1.

## Work item

See [Issue #1](https://github.com/YoungTeurus/pocket-photo-bridge/issues/1), [architecture](docs/architecture.md), and [Codex task prompt](docs/CODEX_PROMPT.md).

## Build and test

No working Android app has been implemented **yet**. Codex should scaffold a Kotlin Android project, add a CI build, and supply reproducible test instructions. Download the generated debug APK from the Actions workflow artifact, install it on the Android tablet (allow installation from your browser/files app as prompted), and test while the camera is in **Send to Smartphone → Select on This Device**.

Never describe Sony photo transfer as working until verified with actual RX100 VII hardware.
