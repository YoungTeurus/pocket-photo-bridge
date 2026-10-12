# Pocket Photo Bridge

Transfer a camera-selected photo from a **Sony RX100 VII** to Android, preview and save it, then prepare an experimental direct Bluetooth print on an original **Xiaomi Mi Portable Photo Printer**. Version **0.2.1** adds detailed redacted printer diagnostics to the token-conditional printing flow; a real Xiaomi print test is still pending.

## Roadmap

- **MVP 1 — Sony → Android:** receive the exact camera-selected JPEG, preview it and save unchanged bytes to Gallery. The first hardware test passed; remaining negative and repeatability checks are documented below.
- **MVP 2 — Android → Xiaomi:** select an Android-paired printer, diagnose Classic SPP, prepare a test chart/local image/Sony JPEG, and confirm one copy. Encrypted status and printing require an existing device-specific token; hardware compatibility remains experimental.
- **MVP 3 — Battery-powered Linux bridge:** evaluate Raspberry Pi Zero 2 W and a small enclosure that can sit on the printer.

## Important constraints

- Sony model: DSC-RX100M7. The camera advertises its own Wi-Fi access point and shows QR connection details. Credentials can be entered once and reused where Android permits.
- The user develops from an **Android tablet** using **Codex Cloud + GitHub**. A GitHub Actions workflow must compile a downloadable **debug APK**; no local Android Studio required.
- Sony **camera-selected / camera-initiated** transfer passed the recorded RX100 VII/tablet test. Unsupported or ambiguous selections stop with diagnostics; the app does not fall back to a full-card media browser.
- Manual foreground Connect/Receive with Android-approved Wi-Fi prompts is acceptable for the MVP. Background Wi-Fi polling and silent auto-connect are stretch goals.
- Do not log or commit camera QR contents, Wi-Fi passwords, Bluetooth tokens, or personal photo content. Make diagnostics safe to share.
- Printer: user-reported **TEJ4007CN**, original Micro-USB model with sliding paper cover. The community `basil` profile has not been validated on this particular printer/firmware. Bluetooth pairing alone does not supply its encryption token.
- No Xiaomi Home, Xiaomi account or cloud connection is used. No verified tablet-only, pairing-only offline token acquisition method has been established; without an existing owned token, encrypted diagnosis and printing remain blocked.

## Work item

See [MVP 1 Issue #1](https://github.com/YoungTeurus/pocket-photo-bridge/issues/1), [MVP 2 Issue #3](https://github.com/YoungTeurus/pocket-photo-bridge/issues/3), [architecture](docs/architecture.md), and [MVP 1 Codex task prompt](docs/CODEX_PROMPT.md).

## MVP 1 implementation status

An experimental Kotlin Android app is implemented for Android 10+ (API 29). It provides foreground Wi-Fi attachment, read-only service diagnostics, an evidence-backed Sony SOAP selected-JPEG receiver, a sampled preview, and unchanged-byte MediaStore export to `Pictures/Pocket Photo Bridge`.

**The first RX100 VII end-to-end hardware test passed.** The user confirmed that version 0.1.1 on an Android API 36 tablet received one camera-selected JPEG (13,805,942 bytes), displayed its preview, and saved it to Gallery. The camera acknowledged TransferEnd; Wi-Fi disconnected after the save. This validates the tested camera/tablet combination; an SD-card original hash comparison, repeatability and negative device tests are still outstanding. It stops on unsupported service/selection responses; it never substitutes an arbitrary/latest photo or a full-card browser. See [the recorded hardware result and remaining checks](docs/sony-protocol.md).

Pair through Android Wi-Fi Settings, which retains the camera password. The app does not collect QR codes, credentials, SSIDs, MAC addresses or location. All camera HTTP uses that Wi-Fi Network, including when it has no internet. No sign-in, server, cloud upload or background scanning is included.

## MVP 2 implementation status

Open **Print test image** to use the printer screen without a camera, or **Print received JPEG** after receiving a Sony photo. Pair in Android Bluetooth Settings and choose **Select paired printer**; Android 12+ may request Nearby devices permission. **Connect / Diagnose** with an empty token verifies an actual Classic SPP connection, then reports `KEY_REQUIRED` before encrypted commands. SPP connection success does not establish printer-profile compatibility.

An existing user-owned 24-character hexadecimal token can be entered locally for one diagnose or print operation. The input clears when consumed and when the screen stops; re-enter it for each operation. Tokens are never persisted or shared, and the printer screen blocks screenshots. The app provides no token bootstrap or guessed/default key. See [the protocol evidence and authentication limit](docs/xiaomi-protocol.md).

The generated test chart and local JPEG/PNG/WebP inputs produce a **1040×1560 baseline JPEG at quality 90 with standard Annex K Huffman tables**. FIT adds white borders; CROP fills the frame. Preview it, select a paired printer, then explicitly confirm **Print one copy**. Sony printing uses a private staged copy and does not change the received original or Gallery export. Printer image copies survive Settings return and rotation; they are deleted when the screen finishes, with abandoned cache cleared at process startup.

There is no automatic retry. A persisted unresolved-job marker blocks new submissions after an uncertain result, including process death. Check the physical printer and any output before using **I checked the printer · clear unknown job**. **Cancel / Disconnect**, leaving the foreground or rotating closes the transport; a job already accepted may still print. Completion requires an explicit matching job-finished response and should be checked against the physical sheet.

Follow [the Android-only printer test guide](docs/printer-tablet-testing.md). The implementation follows MIT-licensed community evidence pinned at [85335b6](https://github.com/fliptheweb/mi-portable-photo-printer/tree/85335b639f3d818c3fe9a892e328864fc3d37250); its license notice is included in the APK. Software verification does not establish TEJ4007CN printing support.

The first tablet experiment on Android API 36 confirmed image preparation and an actual SPP connection. With a user-obtained 24-character `TOKEN`, version 0.2.0 stopped at generic `PROTOCOL_ERROR`; that trace does not locate the failing reply or establish a wrong token. Version 0.2.1 logs fixed request stages, structural frame metadata and specific rejection reasons while excluding tokens, frame sequences, reply bodies and identities. Token input is validated whole rather than silently shortened. Use **Connect / Diagnose** and share safe diagnostics before attempting a physical print.

## Install and test from an Android tablet

1. Open [Actions → Android debug APK](https://github.com/YoungTeurus/pocket-photo-bridge/actions/workflows/android.yml), choose the latest successful run for the implementation branch/PR, and download **pocket-photo-bridge-debug** under Artifacts while signed in to GitHub.
2. Extract its ZIP in Files and install `app-debug.apk`; allow installation from your browser/Files app when Android prompts. The ZIP also includes a SHA-256 checksum. No computer or Android Studio is needed.
3. Select **one JPEG on the camera** using **Send to Smartphone → Select on This Device → This Image**. Join the displayed camera Wi-Fi in Android Settings and accept staying connected without internet.
4. Return to Pocket Photo Bridge → **Connect to current Wi-Fi** → **Diagnose** → **Receive selected JPEG**. Confirm the preview matches the exact selected photo, then **Save to Gallery**.

Follow [the complete tablet test and troubleshooting guide](docs/tablet-testing.md), including the older-photo and two-photo negative tests. Keep the app visible while receiving/saving. Diagnostics can be shared manually; they contain fixed stages, counts/statuses and error categories, never raw XML, network identifiers, credentials or photos.

## Build and software verification

GitHub Actions runs Sony and printer JVM protocol/transport tests, Android lifecycle/image/transport regression tests, Android lint and `assembleDebug`, then uploads the APK/checksum and verification reports. CI pins action commits and the Gradle distribution checksum. Debug signing is for sideload testing; different runs may require uninstalling the prior APK due to changed debug keys.

With Java 17 and an Android SDK installed:

```sh
./gradlew :sony:test :printer:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The pure Kotlin `sony` module can be reused with another HTTP transport. Fixture tests cover selected-list-only behavior, discovery, escaped DIDL, pagination, ambiguity, thumbnail/icon rejection, unsafe URLs/XML and interrupted downloads. Transport tests exercise cancellation interleavings; the Android API 29 lifecycle test ensures camera Wi-Fi loss does not discard a pending preview or stop a local save. Robolectric is a test-only dependency, not an emulator or a device validation. The user's tablet test confirms the basic Wi-Fi/receive/preview/Gallery path; repeatability and negative device tests follow the documented procedure. No camera/emulator is attached to the cloud implementation environment.

The pure Kotlin `printer` module keeps framing, frame-local RC4, correlated status/job replies, bounded transfer and JPEG Huffman normalization separate from Android transport/UI. Printer tests use synthetic fixtures and transports; real token acceptance, image rendering, fault responses and physical completion await the tablet experiment.
