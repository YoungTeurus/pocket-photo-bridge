# Pocket Photo Bridge

Transfer a camera-selected photo from a **Sony RX100 VII** to Android, preview and save it, then print directly over Bluetooth on an original **Xiaomi Mi Portable Photo Printer**. The user confirmed the complete **Sony → Android → Xiaomi** printing flow with version **0.2.2** on an Android API 36 tablet and the reported **TEJ4007CN** printer.

The **Sony → Windows → Xiaomi** flow also passed the user's physical-device test with the portable **0.4.3** build: one selected JPEG received, one confirmed print submitted, and explicit printer completion reported. See [the Windows hardware result](docs/windows-live.md#successful-043-full-flow-hardware-test).

## Roadmap

- **MVP 1 — Sony → Android:** receive the exact camera-selected JPEG, preview it and save unchanged bytes to Gallery. The first hardware test passed; remaining negative and repeatability checks are documented below.
- **MVP 2 — Android → Xiaomi:** select an Android-paired printer, diagnose Classic SPP, prepare a test chart/local image/Sony JPEG, and confirm one copy. Physical printing and the Sony photo handoff passed; encrypted status and printing require an existing device-specific token. Other firmware and remaining hardware checks are unverified.
- **MVP 3 — Battery-powered Linux bridge:** evaluate Raspberry Pi Zero 2 W and a small enclosure that can sit on the printer.
- **MVP 4 — Headless bridge with Windows simulation:** run the Kotlin coordinator and real Sony/Xiaomi protocol logic against deterministic offline devices. GitHub Actions builds a Windows x64 EXE/runtime ZIP and a Linux JVM distribution; the future hardware target is the ordered Orange Pi Zero 2W, 4 GB RAM, on Linux ARM64. Physical Linux radio adapters remain unimplemented.

## Important constraints

- Sony model: DSC-RX100M7. The camera advertises its own Wi-Fi access point and shows QR connection details. Credentials can be entered once and reused where Android permits.
- Development/builds use **Codex Cloud + GitHub Actions**. The Android tablet tests the working APK; a Windows x64 computer runs the portable simulation. No local Android Studio, JDK, WSL or compilation is needed for the Windows ZIP.
- Sony **camera-selected / camera-initiated** transfer passed the recorded RX100 VII/tablet test. Unsupported or ambiguous selections stop with diagnostics; the app does not fall back to a full-card media browser.
- Manual foreground Connect/Receive with Android-approved Wi-Fi prompts is acceptable for the MVP. Background Wi-Fi polling and silent auto-connect are stretch goals.
- Do not log or commit camera QR contents, Wi-Fi passwords, Bluetooth tokens, or personal photo content. Make diagnostics safe to share.
- Printer: user-reported **TEJ4007CN**, original Micro-USB model with sliding paper cover. The community `basil` profile produced a physical print on this unit with 0.2.2; its exact firmware code and compatibility with other units are unknown. Bluetooth pairing alone does not supply its encryption token.
- No Xiaomi Home, Xiaomi account or cloud connection is used. No verified tablet-only, pairing-only offline token acquisition method has been established; without an existing owned token, encrypted diagnosis and printing remain blocked.

## Work item

See [MVP 1 Issue #1](https://github.com/YoungTeurus/pocket-photo-bridge/issues/1), [MVP 2 Issue #3](https://github.com/YoungTeurus/pocket-photo-bridge/issues/3), [MVP 4 Issue #6](https://github.com/YoungTeurus/pocket-photo-bridge/issues/6), [architecture](docs/architecture.md), and [MVP 1 Codex task prompt](docs/CODEX_PROMPT.md).

## MVP 1 implementation status

An experimental Kotlin Android app is implemented for Android 10+ (API 29). It provides foreground Wi-Fi attachment, read-only service diagnostics, an evidence-backed Sony SOAP selected-JPEG receiver, a sampled preview, and unchanged-byte MediaStore export to `Pictures/Pocket Photo Bridge`.

**The first RX100 VII end-to-end hardware test passed.** The user confirmed that version 0.1.1 on an Android API 36 tablet received one camera-selected JPEG (13,805,942 bytes), displayed its preview, and saved it to Gallery. The camera acknowledged TransferEnd; Wi-Fi disconnected after the save. This validates the tested camera/tablet combination; an SD-card original hash comparison, repeatability and negative device tests are still outstanding. It stops on unsupported service/selection responses; it never substitutes an arbitrary/latest photo or a full-card browser. See [the recorded hardware result and remaining checks](docs/sony-protocol.md).

Pair through Android Wi-Fi Settings, which retains the camera password. The app does not collect QR codes, credentials, SSIDs, MAC addresses or location. All camera HTTP uses that Wi-Fi Network, including when it has no internet. No sign-in, server, cloud upload or background scanning is included.

## MVP 2 implementation status

**The direct Xiaomi print and Sony photo handoff passed on 2026-10-12.** The user's 0.2.2 / Android API 36 trace shows parsed encrypted status, correlated job replies and `PRINTER_PRINT_FINISHED`, with physical output confirmed. The follow-up confirmed receiving a photo from the camera and printing it directly from the app: **Sony → Android → Xiaomi** works on the tested setup using the locally entered token. Gallery saving passed in the earlier Sony test; a Gallery save after printing, detailed image quality, repeatability and fault/cancellation checks have not been separately reported. See [the recorded full-flow result](docs/xiaomi-protocol.md#sony-photo-to-physical-print-2026-10-12).

Open **Print test image** to use the printer screen without a camera, or **Print received JPEG** after receiving a Sony photo. Pair in Android Bluetooth Settings and choose **Select paired printer**; Android 12+ may request Nearby devices permission. **Connect / Diagnose** with an empty token verifies an actual Classic SPP connection, then reports `KEY_REQUIRED` before encrypted commands. SPP connection success does not establish printer-profile compatibility.

An existing user-owned 24-character hexadecimal token can be entered locally for one diagnose or print operation. The input clears when consumed and when the screen stops; re-enter it for each operation. Tokens are never persisted or shared, and the printer screen blocks screenshots. The app provides no token bootstrap or guessed/default key. See [the protocol evidence and authentication limit](docs/xiaomi-protocol.md).

The generated test chart and local JPEG/PNG/WebP inputs produce a **1040×1560 baseline JPEG at quality 90 with standard Annex K Huffman tables**. FIT adds white borders; CROP fills the frame. Preview it, select a paired printer, then explicitly confirm **Print one copy**. Sony printing uses a private staged copy and does not change the received original or Gallery export. Printer image copies survive Settings return and rotation; they are deleted when the screen finishes, with abandoned cache cleared at process startup.

There is no automatic retry. A persisted unresolved-job marker blocks new submissions after an uncertain result, including process death. Check the physical printer and any output before using **I checked the printer · clear unknown job**. **Cancel / Disconnect**, leaving the foreground or rotating closes the transport; a job already accepted may still print. Completion requires an explicit matching job-finished response and should be checked against the physical sheet.

Follow [the Android-only printer test guide](docs/printer-tablet-testing.md). The implementation follows MIT-licensed community evidence pinned at [85335b6](https://github.com/fliptheweb/mi-portable-photo-printer/tree/85335b639f3d818c3fe9a892e328864fc3d37250); its license notice is included in the APK. The user's physical result establishes the first hardware success separately from software verification.

The first tablet experiment on Android API 36 confirmed image preparation and an actual SPP connection. Version 0.2.1 located the first protocol mismatch: the printer sent a version-1, interactive-6 encrypted JSON frame, while the app required version 100. Version 0.2.2 accepts incoming versions 1/100 and correlates interactive-6/7 JSON replies by request ID; method-bearing requests/notifications cannot satisfy a reply. Outgoing frames remain version 100, and checksum, encryption and result/schema checks remain enabled. Token input is validated whole rather than silently shortened. Diagnostics exclude tokens, frame sequences, reply bodies and identities. The subsequent 0.2.2 trace and physical print confirm that this receive correction works on the tested unit.

## MVP 4: run on Windows without compiling

The **headless bridge 0.4.3** is separate from the unchanged **Android 0.2.2** application. Synthetic Sony SOAP/HTTP selection and Xiaomi encrypted byte-stream devices exercise the real protocol sessions through the same `PrintCoordinator`. The suite covers normal printing, connection failures, missing/partial selections, busy/offline printer, lost completion, duplicates, restart and checked-printer recovery. A separate-process power-cut experiment proves the persisted unknown-outcome gate. Simulation consumes no physical paper and uses only project-generated photos and a public synthetic fixture key.

For **real Windows camera/printer testing**, launch **WindowsLive.cmd** from the same portable folder. This adds manual Sony Wi-Fi association, paired Xiaomi Bluetooth Classic/SPP selection, hidden token input, receive-only/status-only probes and a one-copy print requiring the word PRINT. The user confirmed Windows Sony receive/save with 0.4.1 and the complete camera-to-printer flow with 0.4.3 on the tested setup. Version 0.4.2 rotates landscape photos to portrait paper after EXIF correction and adds Bluetooth provider compatibility and native error diagnostics. See [the real-device procedure and hardware result](docs/windows-live.md). Linux radios remain unimplemented.

Version **0.4.3** fixes native error capture: the 0.4.2 printer report stopped at connect with an incorrectly zero-valued error. The adapter now reads JNA's saved per-thread error immediately, so an in-progress connect/read/write remains retryable within its existing deadline. The packaged Windows native check also verifies a real failed-socket error without a radio or print. The subsequent user retest captured normal pending code 10035, completed the connection, uploaded the prepared photo and reached `PRINTER_PRINT_FINISHED` / `BRIDGE_PRINTED`.

1. Open [Actions → Headless bridge](https://github.com/YoungTeurus/pocket-photo-bridge/actions/workflows/bridge.yml) and a successful run for this implementation.
2. Download `pocket-photo-bridge-windows-x64-0.4.3` and extract GitHub's outer ZIP.
3. Extract the inner `PocketPhotoBridge-0.4.3-windows-x64.zip`, keep its complete folder together, then double-click **PocketPhotoBridge.exe**.
4. Expect `SIMULATION_ONLY`, `NO_PHYSICAL_PRINT` and `SIMULATION_SUITE_PASS checks=14`. Send back `%LOCALAPPDATA%\PocketPhotoBridge\simulation\diagnostics.txt` if a check fails; it contains fixed stages/counts and no identities, tokens, fingerprints or photo bytes.

The package contains the EXE **and a bundled Java runtime**, including Java desktop imaging. Explicit `--simulate`, `--simulate-all`, `--simulate-failure=<scenario>` and `--help` commands also work in PowerShell. Repeating an ordinary simulation on the same state suppresses its already-consumed selection. Manual acknowledgement clears uncertainty only and never replays its photo. A known rejected submission is conservatively suppressed too; intentional reprinting is outside this milestone.

Follow [the Windows download/run/diagnostics guide](docs/windows-simulation.md). The [Linux/ARM64 guide](docs/linux-bridge.md) describes the JVM distribution, future NetworkManager/BlueZ integration, protected credentials and an inactive systemd template. `--hardware` currently refuses to run. Windows/Linux simulations and ARM64 CI do not validate Orange Pi Wi-Fi/Bluetooth, autonomous physical printing or power-loss behavior on its storage.

The [architecture proposal](docs/superpowers/specs/2026-10-12-mvp4-headless-design.md) and [implementation plan](docs/superpowers/plans/2026-10-12-mvp4-headless.md) were documented before implementation. The existing Android Actions workflow and Android/protocol sources remain unchanged.

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

The pure Kotlin `printer` module keeps framing, frame-local RC4, correlated status/job replies, bounded transfer and JPEG Huffman normalization separate from Android transport/UI. Printer tests use synthetic fixtures and transports. The user's 0.2.2 hardware results confirm working encrypted replies, physical completion and the Sony photo handoff on the tested setup; detailed rendering, repeatability and fault responses still need tablet checks.
