package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.printer.PrinterTransport
import dev.pocketphotobridge.printer.PrinterFailure
import com.sun.jna.Native

/** Device identity is for the local selection UI only, never exported diagnostics. */
data class WindowsPairedDevice(val address: Long, val name: String)

enum class WindowsBluetoothFailureCode { UNSUPPORTED_PLATFORM, NATIVE_UNAVAILABLE, ENUMERATION_FAILED, NATIVE_SELF_TEST_FAILED }
class WindowsBluetoothFailure(val code: WindowsBluetoothFailureCode) : RuntimeException(code.name)

object WindowsBluetooth {
    fun pairedDevices(): List<WindowsPairedDevice> = nativeCall { WindowsBluetoothLibraries.devices.pairedDevices() }
    fun connect(device: WindowsPairedDevice, timeoutMillis: Int = 15_000, report: (String) -> Unit = {}): PrinterTransport =
        nativeCall {
            val diagnostics = WindowsBluetoothDiagnostics(report)
            val backend = WindowsBluetoothLibraries.winsock.withDiagnostics(diagnostics)
            WindowsBluetoothAdapter(backend, diagnostics = diagnostics).connect(device, timeoutMillis)
        }
    fun nativeSelfTest(): Unit = nativeCall { WindowsBluetoothLibraries.winsock.nativeSelfTest() }

    private fun <T> nativeCall(block: () -> T): T {
        val windows = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
        val x64 = System.getProperty("os.arch", "").lowercase() in setOf("amd64", "x86_64")
        if (!windows || !x64) throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.UNSUPPORTED_PLATFORM)
        try {
            if (Native.POINTER_SIZE != 8) throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.UNSUPPORTED_PLATFORM)
            return block()
        } catch (failure: WindowsBluetoothFailure) { throw failure }
        catch (failure: PrinterFailure) { throw failure }
        catch (_: Exception) { throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_UNAVAILABLE) }
        catch (_: LinkageError) { throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_UNAVAILABLE) }
    }
}

internal enum class WindowsBluetoothDiagnosticStage {
    WINSOCK_STARTUP, SOCKET_CREATE, SET_NONBLOCKING, CONNECT_START, CONNECT_PENDING,
    SELECT, SO_ERROR, CONNECTED, SEND, RECEIVE, SHUTDOWN, CLOSE, SELF_TEST_BIND, SELF_TEST_LISTEN
}
internal enum class WindowsBluetoothDiagnosticReason { START, OK, PENDING, READY, TIMEOUT, FAILED, EOF }

/** Only typed stages, reasons and numeric native metadata reach the per-connection sink. */
internal class WindowsBluetoothDiagnostics(private val report: (String) -> Unit = {}) {
    fun emit(stage: WindowsBluetoothDiagnosticStage, reason: WindowsBluetoothDiagnosticReason,
        nativeError: Int = 0, count: Int = 0) {
        val event = "WINDOWS_BLUETOOTH_${stage.name} reason=${reason.name}" +
            " native_error=${nativeError.coerceAtLeast(0)} count=${count.coerceAtLeast(0)}"
        // Diagnostic sinks cannot replace a native failure or prevent socket cleanup.
        try { report(event) } catch (_: Throwable) { }
    }
}
