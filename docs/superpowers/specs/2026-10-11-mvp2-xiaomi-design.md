# MVP 2: direct Xiaomi Bluetooth printing

The goal is to print a local test image, then a Sony-received JPEG, directly from the Android tablet without Xiaomi Home or a cloud service. The working MVP 1 receive/preview/Gallery path is the baseline (PR #2 is unmerged; base commit 4d9b73b). The user reports printer SKU TEJ4007CN, Micro-USB and sliding paper compartment. A SKU alone does not establish firmware/protocol compatibility.

## Evidence and chosen approach

Use the MIT-licensed fliptheweb/mi-portable-photo-printer implementation pinned at 85335b639f3d818c3fe9a892e328864fc3d37250 as a protocol reference. Its tested printer is hannto.printer.basil, XMKDDYJ01HM / XMKDDYJHT02, using Classic RFCOMM SPP, frame-local RC4 with a unique 12-byte token, JSON-RPC and baseline JPEG 1040×1560. Its macOS transport cannot run on Android. The Pro's AES/DH scheme is a different profile and is out of scope.

Implement a Kotlin protocol port and Android SPP transport. Pairing does not yield the encryption token. The reference's acquisition routes require existing Xiaomi account data or an existing print capture; no independently verified offline pairing-only acquisition exists. Support a user-owned 24-hex token entered locally, kept only in memory. With no token, provide paired-device selection, bounded SPP connection diagnostics and complete local image preparation, then report KEY_REQUIRED rather than send invented authentication. No token extraction, Xiaomi login, cloud call or invented auth handshake is included.

## Components and flow

- `printer/` is a pure Kotlin/JVM module. It owns strict bounded frame parsing/checksum, RC4, bounded JSON-RPC, JPEG header validation, status classification, chunking (924 bytes plus 4-byte job id), print sequence and conservative job outcomes. Android types do not cross this boundary.
- Android `printing/` owns permissions, paired-device selection, SPP connection/read/write timeouts, image decoding/EXIF/crop-or-fit, private prepared-image cache, and the printer screen. Discovery/pairing uses Android Bluetooth Settings; listing already paired devices needs BLUETOOTH_CONNECT on Android 12+, and avoids app scanning/location permission. Android 10–11 use legacy Bluetooth permissions.
- Test printing opens a separate PrinterActivity with a generated neutral test chart and an optional system document picker. Sony preview gains Print, staging a separate private copy before opening PrinterActivity so the existing camera Activity can stop/cancel its network normally. Gallery save remains unchanged. Sony Wi-Fi transfer and printer Bluetooth are sequential.
- The printer screen allows paired-device selection, optional token entry, Connect/Diagnose, prepare local image, fit or center crop, and a prepared preview. Printing requires an explicit dialog confirming one copy and the selected printer. No automatic printing or automatic print_job retries.

## Job integrity and lifecycle

Read status before any reset/job command. Reject known busy/processing/error states and unsupported responses. `clean_data` is fire-and-forget; then `print_job` reserves a job, chunked encrypted file frames upload the JPEG, `confirm_job` commits it, and `job_info` must explicitly report the same job finished. Idle alone never means printed. Unknown errors stop with a fixed code. No undocumented cancel command is sent.

Persist a local non-secret job guard before any print_job transmission. Disconnect, cancellation, timeout or process death after that point leaves OUTCOME_UNKNOWN; the guard survives Activity/process recreation and prevents a new job until the user explicitly acknowledges having checked the printer. No Bluetooth identity, photo filename, token, raw response or device identifiers are stored in this guard. A known rejected job or explicit finished response resolves the guard. Closing Bluetooth can stop upload, but cannot promise to retract an already accepted physical print.

Timeouts are bounded for connect, per-frame/RPC, upload and total job. UI updates are generation-checked; cancellation closes socket promptly. Printer work uses a separate foreground worker; leaving the screen cancels/clears token and temporary print copies. PrinterActivity does not mutate Sony's private preview or gallery original. Unknown completion remains explicit.

## Verification and hardware boundary

Keep all existing Sony and lifecycle tests. Add fixed wire vectors, checksum/fragmentation/encryption tests, command-order/reply-id tests, file chunk boundaries, status/error tests, explicit completion and uncertain/duplicate-job tests. Android tests cover image geometry/rotation/JPEG output, staging a separate Sony print copy, and durable guard behavior. CI tests sony/printer/app, lints, assembles and uploads a versioned debug APK plus checksum/reports.

Device diagnostics report only fixed stage/error labels, counts and whitelisted printer phases/battery. Bluetooth names are shown locally for selection but excluded from reports. Photos and secrets remain private; backups disabled. Documentation provides tablet-only pairing/connect/key-required and token-enabled print experiments, plus Sony regression steps. Actual TEJ4007CN encrypted status and physical printing remain pending user validation.
