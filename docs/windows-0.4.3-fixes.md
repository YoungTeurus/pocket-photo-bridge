# Windows 0.4.3 native error capture

## Device evidence

The user's 0.4.2 status-only report completed WSAStartup, AF_BTH socket creation and nonblocking setup, then immediately emitted `CONNECT_START reason=FAILED native_error=0`, `SHUTDOWN reason=FAILED native_error=0`, and DISCONNECTED. It never reached SELECT, CONNECTED or PRINTER_QUERY_STATUS. No token command or print submission occurred. Xiaomi Home and the tablet were already disconnected during this test.

The zero values expose a defect in native error retrieval. They do not establish which socket error Windows originally returned, whether the service lookup succeeded, or whether the printer would accept encrypted commands.

## Bounded correction

JNA [Native.getLastError](https://github.com/java-native-access/jna/blob/5.17.0/src/com/sun/jna/Native.java) reads the error saved per thread by [the native dispatcher](https://github.com/java-native-access/jna/blob/5.17.0/native/dispatch.c) immediately after a mapped call. Another mapped DLL call can observe a live error changed during JNI/VM transitions. JNA even [special-cases Kernel32.GetLastError](https://github.com/java-native-access/jna/blob/5.17.0/src/com/sun/jna/NativeLibrary.java) to return its saved value; that workaround does not apply to the adapter's old Ws2_32.WSAGetLastError mapping.

The WinSock backend now reads the saved JNA error immediately after failed native operations. This preserves pending 10035–10037 connects and retryable would-block reads/writes, as well as actual terminal errors. It removes the unsafe mapped error getter. WSAStartup still returns its own error directly; SO_ERROR still supplies its output through an integer reference. Error capture precedes callbacks and socket cleanup. Existing deadlines, cancellation, select/SDP behavior, no automatic job retry and durable uncertainty protection remain intact.

The native self-test clears the saved error, deliberately calls ioctlsocket with INVALID_SOCKET and requires WSAENOTSOCK (10038), then exercises the existing loopback-listener select check. It uses no printer/radio, secret, device address or remote host. The extracted Windows package gate now requires the captured-error success marker as well as successful interface discovery. A zero or incorrect captured error fails the gate.

Sony HTTP/protocol code, camera discovery, the 0.4.2 paper-rotation correction, Android and shared printer commands are unchanged. Physical Windows connection/printing remains pending a new device test.

## Regression and retest

The focused native suite initially had 22 tests and six expected failures: lost pending-connect/read/write errors, missing true error metadata and the missing failed-socket self-test. With the correction all 22 tests passed. The test native API models JNA's captured per-thread value separately from a lost live value; error categories and safe diagnostics are asserted through the real backend. The Windows Actions runtime check additionally exercises a real failed native call rather than relying only on a fake.

A further regression reproduced a stale saved error falsely passing the self-test. The probe now clears it before the invalid-socket call; the native suite has 23 tests with this case included.

Download the successful **0.4.3** Windows artifact, extract both ZIP layers and launch WindowsLive.cmd. Confirm its header says 0.4.3. Run **2 Query printer status** first with the same token that worked on Android. Copy `%LOCALAPPDATA%\PocketPhotoBridge\windows-live\diagnostics.txt` before any camera or native-diagnostic operation replaces it. If status fails, send that safe report; it should now identify the real pending/terminal socket error. Only proceed to preview/PRINT after status succeeds. Do not delete physical job state or send the token, address, state snapshot, photo or local UI transcript.

Cloud final verification passed **263 tests** (bridge 159, Sony 24, printer 43, Android 37), zero failures/errors/skips, plus Android lint/debug APK and JVM archives. Actionlint, PowerShell parsing, shell syntax and whitespace checks passed. Android app, shared Sony/printer modules and Android workflow have no diff from the merged baseline. The Windows package still requires real native error-capture verification in Actions before release.
