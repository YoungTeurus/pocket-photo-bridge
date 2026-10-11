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

## MVP 1 implementation status

An experimental Kotlin Android app is implemented for Android 10+ (API 29). It provides foreground Wi-Fi attachment, read-only service diagnostics, an evidence-backed Sony SOAP selected-JPEG receiver, a sampled preview, and unchanged-byte MediaStore export to `Pictures/Pocket Photo Bridge`.

**RX100 VII hardware acceptance is pending.** The first tablet test on Android API 36 reached the camera and parsed its main service descriptor, then stopped at action-description parsing. Version 0.1.1 fixes a reproduced parser bug involving nested action argument names; receiving the selected JPEG, preview and Gallery save still require a camera retest. It stops on unsupported service/selection responses; it never substitutes an arbitrary/latest photo or a full-card browser. See [protocol evidence and remaining questions](docs/sony-protocol.md).

Pair through Android Wi-Fi Settings, which retains the camera password. The app does not collect QR codes, credentials, SSIDs, MAC addresses or location. All camera HTTP uses that Wi-Fi Network, including when it has no internet. No sign-in, server, cloud upload, printer or background scanning is included.

## Install and test from an Android tablet

1. Open [Actions → Android debug APK](https://github.com/YoungTeurus/pocket-photo-bridge/actions/workflows/android.yml), choose the latest successful run for the implementation branch/PR, and download **pocket-photo-bridge-debug** under Artifacts while signed in to GitHub.
2. Extract its ZIP in Files and install `app-debug.apk`; allow installation from your browser/Files app when Android prompts. The ZIP also includes a SHA-256 checksum. No computer or Android Studio is needed.
3. Select **one JPEG on the camera** using **Send to Smartphone → Select on This Device → This Image**. Join the displayed camera Wi-Fi in Android Settings and accept staying connected without internet.
4. Return to Pocket Photo Bridge → **Connect to current Wi-Fi** → **Diagnose** → **Receive selected JPEG**. Confirm the preview matches the exact selected photo, then **Save to Gallery**.

Follow [the complete tablet test and troubleshooting guide](docs/tablet-testing.md), including the older-photo and two-photo negative tests. Keep the app visible while receiving/saving. Diagnostics can be shared manually; they contain fixed stages, counts/statuses and error categories, never raw XML, network identifiers, credentials or photos.

## Build and software verification

GitHub Actions runs JVM protocol/transport tests, a Robolectric Android lifecycle regression, Android lint and `assembleDebug`, then uploads the APK/checksum and verification reports. CI pins action commits and the Gradle distribution checksum. Debug signing is for sideload testing; different runs may require uninstalling the prior APK due to changed debug keys.

With Java 17 and an Android SDK installed:

```sh
./gradlew :sony:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The pure Kotlin `sony` module can be reused with another HTTP transport. Fixture tests cover selected-list-only behavior, discovery, escaped DIDL, pagination, ambiguity, thumbnail/icon rejection, unsafe URLs/XML and interrupted downloads. Transport tests exercise cancellation interleavings; the Android API 29 lifecycle test ensures camera Wi-Fi loss does not discard a pending preview or stop a local save. Robolectric is a test-only dependency, not an emulator or a device validation. Android Wi-Fi, rendering, Gallery integration and actual camera behavior require the documented device test. No camera/emulator is attached to the cloud implementation environment.
