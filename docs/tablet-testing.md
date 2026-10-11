# Test MVP 1 using only an Android tablet

## Install the APK

1. Sign in to GitHub in the tablet browser. Open the implementation PR, then its **Android debug APK** check → **Details**. Alternatively open [Actions](https://github.com/YoungTeurus/pocket-photo-bridge/actions/workflows/android.yml), choose the feature branch's latest successful run, and verify its commit matches the PR head.
2. In Artifacts, download **pocket-photo-bridge-debug**. Extract the ZIP in the tablet's Files app. It contains `app-debug.apk` and `app-debug.apk.sha256`. Do not install an older run after downloading a newer one.
3. Open `app-debug.apk`, allow installation from your browser/Files app when Android prompts, and launch **Pocket Photo Bridge**. Android 10 or newer is required. No Android Studio or local build is needed.
4. These are debug builds. Different CI runs may use different debug signing keys; if an update reports a signature conflict, uninstall the previous app and install the new APK. Gallery photos already saved remain; private preview files do not.

For the first hardware-test failure (`DESCRIPTOR → HTTP_STATUS 200 → INVALID_XML` on 0.1.0), install **0.1.1 or later**. The version appears below the app title and in shared diagnostics. A reproduced service-description parser bug counted nested argument names as action names. After updating, re-enter the camera's send mode, Connect, then Diagnose; expect `ACTIONS` before trying Receive.

## Five-minute selected-photo experiment

1. On the RX100 VII, pick an easily recognized **JPEG that is not the newest photo**. Record its displayed file number privately. Avoid RAW/movie selection and select just one photo.
2. Enter **Send to Smartphone → Select on This Device → This Image**. Keep the camera on its connection-information screen. Display SSID/password with the camera's Delete button if needed. Do not share a QR screenshot or Wi-Fi password.
3. In Pocket Photo Bridge, tap **Open Wi-Fi settings**. Join that exact camera AP with its displayed password. Accept **stay connected/use this network without internet** if Android asks. Android retains the credentials; this app does not read or store them. Return to the app.
4. Tap **Connect to current Wi-Fi**. Confirm “Wi-Fi attached” and note whether diagnostics say `GATEWAY_FROM_WIFI` or `GATEWAY_FALLBACK`. Connect establishes the route; it is not evidence of a completed camera handshake.
5. Tap **Diagnose**. Expect `DESCRIPTOR` then `ACTIONS`. This does not start a transfer. If it fails, use the matrix below.
6. Tap **Receive selected JPEG**. Expected trace: TRANSFER_START → PUSH_PAGE → SELECTION count=1 → JPEG_BYTES → TRANSFER_END (or END_UNCONFIRMED). Confirm the preview depicts the exact older photo selected on the camera. The app does not offer an SD-card browser.
7. Tap **Save to Gallery**, then open Gallery → Pictures/Pocket Photo Bridge. Compare the saved photo's dimensions/file size with the camera JPEG information. Preview sampling does not resize the saved file. If you later obtain the SD-card original, compare SHA-256 values to establish byte-exact original transfer.
8. Repeat with a different older JPEG. Then deliberately select two photos: the app must stop with AMBIGUOUS_SELECTION and save neither. Repeat after camera/tablet restart to check Android's saved Wi-Fi credentials.

Keep the app visible during transfer/save. Cancel, switch apps, lock the screen, or turn off camera Wi-Fi to test interruption; no incomplete image should appear in Gallery. Returning after an interrupted transfer may require re-entering the camera's send mode and tapping Connect again. Rotating/recreating the activity clears its private preview; save first or receive again.

## Troubleshooting and safe evidence

| Result | Next experiment |
| --- | --- |
| NO_WIFI / no network attached | Enable Wi-Fi and join the camera AP in Settings; accept no-internet prompt; return and Connect. Disconnect VPN temporarily if the OEM blocks local access. |
| Multiple Wi-Fi networks | Use Android Settings to leave the other Wi-Fi connection, then Connect. The app refuses an ambiguous route. |
| GATEWAY_FALLBACK + TIMEOUT/IO_ERROR | Confirm the AP is the camera and the camera is still on its send screen. Tell us that the gateway was unavailable; do not export addresses or a network screenshot. |
| DESCRIPTOR failure / WRONG_MODE | Exit and re-enter **Select on This Device**, select one JPEG, rejoin AP and rerun Diagnose. Select on Smartphone/Send to Computer are not equivalent. |
| UNSUPPORTED_SERVICE / INVALID_XML | The service/action response was unsupported or rejected by the parser. First verify the installed version is 0.1.1 or later. Share the redacted trace and firmware version; do not paste raw XML or QR data. This can indicate a parser compatibility problem as well as a firmware difference. |
| PUSH_PAGE / WRONG_MODE | PushRoot was rejected. Confirm camera-side selection and retry. The app will not fall back to all card images. |
| NO_JPEG | Select a JPEG, not RAW/movie; thumbnail-only or unrecognized resources are rejected. Share the redacted trace. |
| AMBIGUOUS_SELECTION | Select exactly one JPEG on camera. Multiple files are outside this MVP. |
| TRUNCATED_JPEG / INVALID_JPEG / LIMIT_EXCEEDED | Keep camera awake and tablet close; re-enter send mode and retry. Files/XML/paging are bounded to prevent runaway or partial transfers. |
| END_UNCONFIRMED | A complete JPEG was received but the camera did not acknowledge completion, possibly because it closed Wi-Fi. Check the preview, Save, then exit the camera's transfer screen manually. |
| SAVE_FAILED | Check free tablet storage and retry Save while the preview is still present. A pending/partial Gallery row is removed on failure. No storage permission is needed on Android 10+. |

Tap **Share safe diagnostics** after the failure or success. The share sheet includes app/Android API version and only fixed event names, counts, HTTP statuses and error categories. Add tablet model, Android version, camera firmware, camera menu used, whether the selected older image matched, and whether Gallery save succeeded **in your own words**. Share through the existing issue/PR manually. Never attach photos, raw network captures, SSID, password, QR data, IP/MAC addresses, filenames or raw XML. The app does not automatically send a report anywhere.

## Acceptance status

The first user test on Android API 36 confirmed Wi-Fi attachment, a gateway obtained from Wi-Fi, and HTTP 200 responses for the main descriptor and advertised action description. It stopped before transfer with INVALID_XML; 0.1.1 fixes a parser defect reproduced with a realistic SCPD fixture. The actual camera XML was not collected. Selected-image identity, original-quality resource, preview and Gallery save remain unverified and need the retest above. No physical camera or Android emulator is attached to this cloud task. MVP 1 remains experimental and Issue #1 stays open until hardware acceptance.
