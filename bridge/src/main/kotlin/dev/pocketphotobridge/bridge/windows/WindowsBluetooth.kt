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
    fun connect(device: WindowsPairedDevice, timeoutMillis: Int = 15_000): PrinterTransport =
        nativeCall { WindowsBluetoothAdapter(WindowsBluetoothLibraries.winsock).connect(device, timeoutMillis) }
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
