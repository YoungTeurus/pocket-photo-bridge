# Xiaomi printer test from an Android tablet

Version **0.2.1** supports local image preparation and experimental direct Bluetooth printing for the original Mi Portable `basil` profile. The user reports SKU **TEJ4007CN**, Micro-USB and sliding paper compartment. SPP connection and image preparation passed the tablet experiment; a physical print on this particular printer/firmware is **not yet verified**. [Protocol evidence](xiaomi-protocol.md) distinguishes the upstream tested models from this reported SKU.

## Install and prepare

1. In [Actions → Android debug APK](https://github.com/YoungTeurus/pocket-photo-bridge/actions/workflows/android.yml), open the latest successful run for the MVP 2 branch/PR and download **pocket-photo-bridge-debug** while signed in.
2. Extract the ZIP in Files and install `app-debug.apk`. It includes an APK SHA-256 checksum. Android may ask to allow installation from Files/the browser. No computer or Android Studio is needed. If Android refuses an update because debug signing certificates differ, first check the physical printer for any unresolved job, then uninstall the old app and install this APK. Uninstalling clears app-private cache and the unknown-job marker; Gallery photos remain. Never uninstall to bypass an unknown print outcome.
3. Charge the printer, insert the correct 50×76 mm ZINK paper according to its instructions and close the cover. Keep the printer near the tablet.
4. Open Pocket Photo Bridge → **Print test image**. Use **Open Bluetooth settings**, turn Bluetooth on and pair the printer through Android. Return to the app and tap **Select paired printer**. On Android 12+, grant Nearby devices and tap selection again if requested. Select the device you physically paired.

Opening Settings cancels an active operation and clears any token. The prepared printer image survives returning from Settings. Rotation keeps the image/placement but cancels transport and requires selecting the printer and entering any token again.

## First check: connection without a token

Leave the token field empty and tap **Connect / Diagnose**. Expected safe diagnostics after a successful Classic SPP connection are `PRINTER_SPP_CONNECTED` followed by `PRINTER_KEY_REQUIRED`. This proves the tablet opened SPP; it does not prove the encryption token, printer identity or print profile.

If the printer does not appear, pair it in Android Bluetooth Settings, then return and select again. If connection fails, check power, Bluetooth and physical proximity, and disconnect another device that may be using the printer. Share the app's fixed error category. A paired device that lacks the supported Classic SPP service is not compatible with this profile. BLE-only devices, 1S and Pro have not been implemented.

## Prepare an image without printing

1. Tap **Generate test image** to show orientation labels, color blocks, gray steps, a circle and border. It prepares a 1040×1560 baseline JPEG at quality 90 with standard Annex K Huffman tables.
2. Tap **Choose local image** and use Android's document picker to select a JPEG, PNG or WebP. Large or unsupported inputs stop with a safe error rather than using the previous preview.
3. Use **Fit / Crop**: **Fit · white borders** preserves the full image; **Crop · fill paper** centers and trims it to fill the frame. Confirm orientation and visible cropping in the prepared preview. **Prepare preview** rebuilds it.
4. Return from Bluetooth Settings and rotate the screen to check the image is retained. These actions cancel an active operation. Finishing the printer screen removes its private copies; process startup clears abandoned cache. Original local files are read only.

These checks need no token and consume no paper. A preview is not a physical print test.

## Token boundary and encrypted diagnosis

Encrypted status and printing require an existing, legitimate **device-specific token of 24 hexadecimal characters**. Android Bluetooth pairing does not provide it, and no verified tablet-only pairing-only offline way to obtain it has been established. If you do not already own this token, record `KEY_REQUIRED` as the current blocker and complete the connection/preview checks above. The app has no Xiaomi Home, account or cloud dependency and does not supply a default/guessed token.

If you already own the token, enter it only in the printer screen, then tap **Connect / Diagnose**. The input clears when used: enter it again for a later print or diagnose operation. Leaving the foreground also clears it. It is not persisted, backed up, logged or included in reports; the printer screen blocks screenshots. Never paste it into chat, GitHub or a test report.

A successful encrypted diagnosis shows a known printer phase and, when available, battery level. `INVALID_KEY` or `KEY_OR_PROFILE_REJECTED` means the token/profile check failed; SPP success alone cannot distinguish a wrong token from incompatible firmware. Exact paper, cover and battery fault mappings for TEJ4007CN remain unverified; physically inspect the printer when a fault is reported.

## Investigating the 0.2.0 protocol error with 0.2.1

The user's 0.2.0 trace reached `PRINTER_SPP_CONNECTED`, then `PRINTER_PROTOCOL_ERROR` after entering a 24-character field named `TOKEN`. This proves SPP attachment; the generic error does not show which reply differed. The secret was not supplied to the project. There is no evidence yet that the key itself is wrong or that encrypted status/printing succeeds.

1. Install 0.2.1, close other printer apps, open **Print test image** and select the paired printer. Keep the app visible.
2. Enter the token locally again and tap **Connect / Diagnose** once. **Do not tap Print for this experiment.** Diagnosis sends only `mixed_status` and, if status parses, `get_prop ["device_info"]`; it sends no reset, job submission or JPEG upload.
3. Use **Share safe printer diagnostics** and provide the full exported text. It includes token length only, query stages, RX byte counts, frame version/reserved/channel/interactive/encoding/encryption/package/body-length/attribute fields, and a fixed rejection reason. It excludes the token, sequence IDs, decrypted/encrypted payloads, raw JSON, Bluetooth identifiers and device information. Do not add the token or a screenshot of it.

`PRINTER_QUERY_STATUS` locates the first request; `PRINTER_STATUS_PARSED` followed by `PRINTER_QUERY_DEVICE_INFO` locates the second. Relevant rejection reasons:

| Reason | What it establishes |
| --- | --- |
| `STREAM_HEADER`, `FRAME_*` | Input differs from the supported frame layout/checksum; metadata helps identify the structural difference. |
| `REPLY_PROFILE` | A frame is present but uses an unsupported reply cipher/encoding/multipart profile. |
| `REPLY_JSON` | The decrypted body is not a JSON object; wrong key or incompatible profile remains possible. |
| `RPC_RESULT_MISSING` | A correlated JSON reply lacks the expected result field. |
| `STATUS_*`, `DEVICE_INFO_RESULT_TYPE` | The correlated reply's schema or status values differ from the supported profile. |

Strict checks remain enabled. Unsupported values are not printed into diagnostics or guessed into known states. A token with another length/format is rejected without using a shortened prefix; do not trim or pad it to force acceptance. Once diagnosis succeeds, proceed to the separately confirmed physical experiment below.

## One-copy physical experiment

1. Start with the generated chart, a selected paired printer and no unresolved previous job. Verify the prepared preview.
2. Enter your existing owned token again. Tap **Print one copy**, review the selected device and placement, then explicitly confirm **Print one copy** in the dialog. This consumes one sheet.
3. Keep the printer screen visible. Upload, confirmation and printer phases may appear. There is no automatic retry or reconnect-and-reprint. The job deadline is bounded to four minutes.
4. Success requires an explicit matching job-finished response. Check the physical sheet separately: one copy, TOP/BOTTOM orientation, complete border/circle, sensible colors and no visible truncation/corruption. An idle status, completed upload or quiet printer does not establish success.
5. Record the app version, Android API, reported printer SKU, safe stages/error category, whether the printer produced a sheet, and the observed rendering. Do not include tokens, Bluetooth addresses/names or personal photos. If the first chart works, repeat with one manually confirmed local FIT/CROP image.

Until this succeeds on real hardware, printer compatibility and physical image quality remain pending even when software tests pass.

## Cancellation and an unknown outcome

**Cancel / Disconnect**, leaving the foreground, opening Settings, rotating or process death interrupts the tablet's transport. A job accepted by the printer may still print; closing the socket is not a guaranteed printer-side cancel. Check the printer before another submission.

An uncertain submitted job keeps a persisted guard and disables **Print one copy**, including after reopening/restarting the app. Wait for the printer to stop, inspect any output and ensure there is no active job. Only then tap **I checked the printer · clear unknown job**, read the dialog and acknowledge **I checked the printer**. Clearing this marker cannot cancel an accepted job and does not trigger a retry. A new print requires fresh manual confirmation and token entry.

For a controlled cancellation check, start one explicitly approved chart print and cancel once; observe whether a sheet still appears and whether the app blocks another submission. Do not repeatedly submit to recover from silence or a missing reply. Keep the unknown marker until the physical printer has been checked.

## Sony photo handoff and regression

1. Follow [the Sony tablet guide](tablet-testing.md) to receive one camera-selected JPEG. Verify it is the exact selected photo. Keep the preview open: **Save to Gallery** clears it after saving, so use the direct preview Print action first. You can also print a previously saved Gallery image through **Choose local image**.
2. Tap **Print received JPEG**. The app stages a separate private copy, then prepares the printer preview. Verify it matches the received photo; choose FIT/CROP and select the paired printer. The Sony original and Gallery bytes are unchanged.
3. If a valid token and chart print have already been established, explicitly confirm one copy of this prepared photo and inspect the sheet.
4. Return to the camera screen and **Save to Gallery**; the received original remains available after printing. Repeat receive/preview/Gallery export. Do not infer Sony regression success from a printer-only test.

The recorded Sony 0.1.1 hardware success was an RX100 VII on an Android API 36 tablet: **13,805,942-byte** selected JPEG received, preview displayed, Gallery save completed, camera TransferEnd acknowledged and Wi-Fi disconnected after save. MVP 2 must preserve that path. Remaining Sony original-hash, repeatability and negative tests are listed in the Sony guide.

## Share a safe result

Use **Share safe printer diagnostics** after the operation; sharing leaves the foreground and cancels any remaining transport. The report includes fixed stages/progress/error categories, app version and Android API. It excludes tokens, raw responses, Bluetooth identifiers and photo contents. Add the physically observed result in your own words. Report `KEY_REQUIRED` honestly when no legitimate token is available; no physical print result has been established by connection or preview alone.
