# Windows 0.4.2 hardware follow-up

The user's 0.4.1 Windows test completed the Sony camera-selected JPEG receive: 11,215,541 bytes, TransferEnd acknowledged, original and preview saved locally. This validates the Windows camera path on that PC/RX100 VII setup. It does not establish an SD-card byte comparison or Orange Pi radio integration.

The paired Xiaomi device was listed, but status-only stopped with DISCONNECTED before PRINTER_QUERY_STATUS. Xiaomi Home was closed and Android disconnected. The token passed local format validation; there is no evidence yet of printer-side authentication failure because encrypted queries did not start. Version 0.4.1 discarded native socket stages/error numbers, so the trace cannot distinguish socket creation, nonblocking connect, SPP lookup or readiness failures.

The prepared preview also kept a landscape source horizontal inside the portrait 1040×1560 paper raster, producing large top/bottom FIT margins. This is placement policy after EXIF correction, not evidence of corrupt Sony transfer.

## Bounded fixes

- Apply EXIF rotations/mirrors first; if the resulting image is landscape, rotate it 90° clockwise to use the portrait paper raster before FIT/CROP. Preserve source bytes and existing JPEG constraints. A 3:2 landscape photo should fill 1040×1560 without the previous large bars; other aspect ratios can retain FIT margins. Already portrait/EXIF-rotated portrait sources must not rotate again.
- Investigate Windows Bluetooth provider compatibility against Microsoft SDK/documentation and established implementations. Preserve the documented SPP UUID/SDP connection and bounded deadlines. Add per-connection fixed stages and native error numbers to exported diagnostics without identity, secret, socket handle or raw exception text. Any implementation correction must have a reproducible regression; physical success still requires the user retest.
- Keep Android and the successful Windows Sony transport unchanged. Preserve explicit PRINT confirmation, no automatic retry and durable unknown-outcome protection. Diagnostics must never cause, hide or retry device actions.
- Build 0.4.2 entirely in Cloud/Actions, verify extracted Windows runtime and Linux ARM64, update PR #7, and instruct the user to query printer status before any print.

Independent image/native work follows the existing adapter boundaries. Integration runs the complete applicable tests and a fresh review. The source image supplied in chat is private evidence; fixtures remain synthetic and it will not be committed.

## Compatibility evidence and limits

Microsoft explicitly documents Bluetooth support for [select](https://learn.microsoft.com/windows/win32/bluetooth/bluetooth-and-listen-select-and-closesocket). Its [select contract](https://learn.microsoft.com/windows/win32/api/winsock2/nf-winsock2-select) reports nonblocking connect failures through exception readiness and SO_ERROR. Established Windows RFCOMM implementations also use select. Version 0.4.2 replaces WSAPoll with this provider-supported path while retaining SPP UUID/port-zero SDP lookup, deadlines and socket cleanup. The native self-test uses a bound loopback listener to exercise select without a Bluetooth radio.

The 0.4.1 trace does not establish WSAPoll as the cause of the user's connection failure, nor does it show token rejection. The new per-operation report records typed stages and numeric native errors to resolve that uncertainty. No successful Windows printer connection or physical print is claimed before a device retest.

## Verification record

- Synthetic image regression: 19 tests, with the landscape/composed-orientation/margin assertions observed failing before the fix and passing afterward. All eight EXIF cases, portrait preservation and original-byte preservation are covered.
- Native adapter regression: 46 tests passed after select/diagnostics changes, including exception readiness, SO_ERROR, full-width socket layouts, cleanup, bounded cancellation and reporter isolation/failure handling.
- Report routing regression failed before the live workflow passed its report callback, then passed in the integrated suite.
- Cloud integrated verification: 256 tests (bridge 152, Sony 24, printer 43, Android 37), zero failures/errors/skips; Android lint/debug APK and JVM archives built successfully. Android app, Sony/printer modules and Android workflow have no diff from the merged baseline.
- Actionlint, PowerShell parsing, shell syntax and whitespace checks passed. Windows extracted-runtime native checks and Linux ARM64 remain mandatory Actions gates for the published package. Physical Windows printer compatibility remains pending.

The first Actions attempt and its Windows-only retry both passed the new native select self-test but hit the existing eight-second camera-discovery cap before the portable-package gate. Local Windows PowerShell/CIM discovery now has a bounded 20-second allowance and exports elapsed milliseconds. A virtual ten-second process reproduced the failure before this change; hung-process termination and interruption/cancellation remain covered. The discovery script, candidate route validation, source binding and Sony protocol/HTTP deadlines are unchanged. The gate still requires actual successful discovery; no failure marker is accepted as success.

After the discovery-budget change, the integrated Cloud suite passed **259 tests** (bridge 155, Sony 24, printer 43, Android 37), zero failures/errors/skips; lint, debug APK and distributions also passed. The seven discovery tests include the slow-success, bounded-hang and interruption cases.
