# Windows real camera and printer testing (0.4.2)

This build adds real Windows x64 adapters to the existing headless coordinator. The user successfully received and saved an 11,215,541-byte camera-selected JPEG from an RX100 VII with Windows 0.4.1. Windows Bluetooth connection and physical printing remain **validation pending**: that status test stopped before encrypted commands. Version 0.4.2 adds provider-compatible socket readiness and native diagnostics for the retest. The portable simulation remains available through the EXE's default operation. Linux radio adapters remain unimplemented. No local compilation, JDK, WSL, Xiaomi Home or cloud connection is needed to run either workflow.

## Download and prepare

1. In Actions → **Headless bridge**, open the successful run linked in PR #7. Download `pocket-photo-bridge-windows-x64-0.4.2`.
2. Extract GitHub's outer artifact ZIP, then extract `PocketPhotoBridge-0.4.2-windows-x64.zip`. Keep the entire `PocketPhotoBridge` folder, including `app/`, `runtime/` and licenses. The checksum procedure in [the simulation guide](windows-simulation.md) also applies here.
3. Charge/load the Xiaomi TEJ4007CN. Close Xiaomi Home and disconnect any Android printer session. In **Windows Settings → Bluetooth & devices → Add device → Bluetooth**, pair your printer. Use Windows's normal pairing UI; the bridge enumerates already paired devices and does not supply a PIN or automate pairing. If Windows cannot pair it, report that step before attempting a print. An outgoing COM port is not required.
4. Keep your own 24-character hex printer TOKEN available. This is the same device-specific token that worked in Android. Enter it only in the app's hidden prompt. It is not saved, logged or accepted as a command argument. Do not send it back.
5. Double-click **WindowsLive.cmd**. It opens the bundled EXE's real-device menu. Alternatively, from PowerShell in the extracted folder run `.\PocketPhotoBridge.exe --windows-live`. A real interactive console is required; redirected input is refused before any device action.

## First test: printer status, no paper

Choose **2 Query printer status**, select your paired Xiaomi device, then enter the token at the hidden prompt. This opens Bluetooth Classic RFCOMM and uses the proven encrypted `mixed_status` and `get_prop` operations. It submits no print and does not run `clean_data`.

Expected success includes `PRINTER_REPLY_MATCHED`, `PRINTER_STATUS_READY` and `LIVE_PRINTER_STATUS_READY`. Battery is reported as a count when present. A busy/error response, wrong key, missing SPP service, driver problem or timeout stops the operation with a fixed category. Device names are displayed only locally; addresses are not exported.

For the 0.4.2 retest, run this operation first and copy its safe report before running a camera operation. Connection stages use `WINDOWS_BLUETOOTH_...` with fixed `reason`, numeric `native_error` and `count` fields. For example, `CONNECT_PENDING` with error 10035 is a normal nonblocking connection step; a later `FAILED` stage identifies where Windows rejected the operation. A failure before `PRINTER_QUERY_STATUS` does not establish a bad token. Continue to a print test only after status succeeds.

## Second test: receive a real Sony photo, no paper

1. On the RX100 VII choose **Send to Smartphone → Select on This Device** and select **one JPEG**. Keep the sharing/SSID screen active.
2. Join that camera's Wi-Fi in Windows Settings using the camera-provided password. Keep the connection even though it has no internet. Pair/download the application beforehand; camera Wi-Fi need not reach GitHub. Other network adapters can remain active because camera sockets bind to the selected local IPv4 address.
3. Launch WindowsLive.cmd and choose **1 Receive selected Sony JPEG**. Select the interface connected to the camera. The app derives its private gateway from actual Windows network configuration, checks the on-link route, and uses source-bound HTTP with no proxy, DNS or redirects. It does not assume a universal Sony gateway.
4. The actual Sony session discovers the descriptor/SCPD and receives exactly the camera-selected PushRoot JPEG. It validates decoding before acknowledging TransferEnd. The original bytes are retained unchanged alongside a prepared `print-preview.jpg` under `%LOCALAPPDATA%\PocketPhotoBridge\windows-live\photos\<local operation folder>`.
5. Type **VIEW** at the preview prompt to open the prepared image in the Windows image viewer, or press Enter. The original and prepared preview folder is also printed locally. No printer is queried/connected during this operation.

The desktop preparer applies EXIF rotation/mirroring, then turns a remaining landscape image 90° clockwise to match the portrait paper. A 3:2 landscape photo fills the 1040×1560 raster without the former large top/bottom bars. Other aspect ratios can retain FIT margins; `--crop` selects centered cropping. Already portrait images keep their orientation. The original JPEG is unchanged; Android's proven preparation path is unchanged.

Expected success includes `SONY_SELECTION count=1`, a real `SONY_JPEG_BYTES count=...`, `LIVE_PHOTO_SAVED` and `LIVE_CAMERA_RECEIVED`, exit 0. Confirm that the image matches the camera selection. This test does not print the retained file later; the full-flow operation below receives a fresh selection.

## Third test: receive and print once

1. Exit and re-enter camera sharing; select one JPEG, then rejoin the camera's Wi-Fi if needed.
2. Launch WindowsLive.cmd and choose **3 Receive selected JPEG and confirm one print**.
3. Choose the camera interface and paired printer, then enter your token. The coordinator receives and prepares the photo before connecting the printer.
4. Inspect the local preview. At the paper-consumption prompt, type the exact word **PRINT** to authorize one copy. Any other answer cancels, before printer connection or job reservation.
5. Wait for the printer to finish. Expected success includes `LIVE_PRINT_CONFIRMED`, `PRINTER_JOB_RESERVED`, `PRINTER_UPLOAD`, `PRINTER_PRINT_FINISHED` and `BRIDGE_PRINTED`, exit 0. Confirm the physical output before claiming success.

The same image's content is suppressed on later selections, including after restarting the EXE. This intentionally prevents automatic duplicate paper consumption. Intentional reprinting is outside this milestone; do not delete state to bypass the guard.

## Unknown outcomes and recovery

Exit 3 / `BRIDGE_UNKNOWN_OUTCOME` means the printer may have accepted a job. The real journal at `%LOCALAPPDATA%\PocketPhotoBridge\windows-live\physical-jobs` blocks new physical jobs. Check the actual printer and paper; there is no automatic retry. You may still run the read-only status operation.

Only after checking the actual printer, choose **5 Acknowledge after checking actual printer**, and type **CHECKED**. This clears the uncertainty gate while retaining attempted-photo tombstones. It does not cancel printer-side work, retry the job or make that photo eligible again. Fresh different photo content can then be tested. The simulation acknowledgement command targets simulation state; use the live menu for physical recovery.

Closing the console during submission is a process interruption. After restarting, preserve state and follow the unknown-outcome procedure. Software reservation is durable before print_job; actual Windows storage/power-loss behavior still requires hardware validation.

## Diagnostics to send

Send the contents of `%LOCALAPPDATA%\PocketPhotoBridge\windows-live\diagnostics.txt`, the selected operation (1/2/3/4/5), the exit code and whether the correct photo was received or a physical print completed. Each operation replaces the safe report, so copy its contents before starting the next test. **Do not send the token, state snapshot, printer address, photo files or the local UI transcript**, which contains photo paths and device/interface names.

Choose **4 Read-only native diagnostics** if pairing/status fails. Copy the status report first: this operation replaces it. `WINDOWS_WINSOCK_BINDINGS_READY` proves native calls loaded and a loopback TCP listener exercised the native select/readiness layout; it does not prove a Bluetooth radio or printer connection. `WINDOWS_PAIRED_DEVICES count=...` reports enumeration only. `WINDOWS_BLUETOOTH_UNAVAILABLE category=...` is an actionable native/provider limitation. A noninteractive read-only probe is also available as `.\PocketPhotoBridge.exe --windows-native-check`.

The simulator cannot establish real Sony gateway/firmware, Windows Bluetooth pairing/SPP availability, token acceptance, Wi-Fi/Bluetooth coexistence or print quality. Automated tests cover native ABI mappings, local HTTP framing/routing, bounded socket behavior and the same real orchestration/protocol logic; your three device tests provide the missing physical evidence.

## Implementation evidence

- Native layouts and constants follow Microsoft's [ws2bth.h](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/ws2bth.h), [BluetoothAPIs.h](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/bluetoothapis.h) and WinSock2.h declarations. SOCKADDR_BTH is packed, and SOCKET is 64-bit on x64.
- The SPP UUID asks Windows to perform SDP service/channel lookup; no RFCOMM channel is invented. The printer must actually advertise that service on your firmware/driver combination.
- Readiness uses Microsoft's explicitly supported Bluetooth [select](https://learn.microsoft.com/windows/win32/bluetooth/bluetooth-and-listen-select-and-closesocket) API, including exception readiness and SO_ERROR for nonblocking connect. This replaces WSAPoll to improve provider compatibility. The old trace omitted native error numbers, so it cannot prove WSAPoll caused the user's failure; the new report is needed to establish the actual failing stage.
- JNA5.17.0 is pinned and distributed under its Apache2.0 option, with JNA/libffi notices in `licenses/jna`. Native code remains confined to the Windows adapter; existing Android and wire protocols are unchanged.
