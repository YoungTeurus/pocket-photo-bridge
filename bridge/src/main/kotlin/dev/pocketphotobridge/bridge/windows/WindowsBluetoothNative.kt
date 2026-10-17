package dev.pocketphotobridge.bridge.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode

/* Platform declarations, not copied implementations:
 * https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/ws2bth.h
 * https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/WinSock2.h
 * https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/bluetoothapis.h
 * https://learn.microsoft.com/windows/win32/api/bluetoothapis/nf-bluetoothapis-bluetoothfindfirstdevice
 * https://learn.microsoft.com/windows/win32/bluetooth/bluetooth-and-connect
 * https://learn.microsoft.com/windows/win32/bluetooth/bluetooth-and-listen-select-and-closesocket
 * https://learn.microsoft.com/windows/win32/api/winsock2/nf-winsock2-select
 * The SDK's pshpack1.h makes SOCKADDR_BTH 30 bytes. Socket connects use SPP UUID + port=0 for SDP.
 * BluetoothFindFirstDevice documents Bthprops.cpl; newer systems also export via BluetoothAPIs.dll.
 * JNA 5.17.0 is an external LGPL2.1/Apache2 dual-licensed dependency; distribution includes its notices.
 */

/** Fixed Windows x64 layouts; Windows WCHAR is UTF16 even when tests run on Linux. */
internal object WindowsBluetoothAbi {
    fun socketAddress(address: Long): Memory = cleared(30).apply {
        setShort(0, 32) // AF_BTH
        setLong(2, address)
        // GUID 00001101-0000-1000-8000-00805f9b34fb (Serial Port service class).
        setInt(10, 0x00001101)
        setShort(16, 0x1000)
        write(18, byteArrayOf(0x80.toByte(), 0, 0, 0x80.toByte(), 0x5f, 0x9b.toByte(), 0x34, 0xfb.toByte()), 0, 8)
        // port stays zero: Windows resolves the serviceClassId through SDP.
    }
    fun searchParams(): Memory = cleared(40).apply {
        setInt(0, 40)
        setInt(4, 1) // authenticated
        setInt(8, 1) // remembered
        // unknown, connected-only, inquiry, inquiry timeout and hRadio stay zero.
    }
    fun deviceInfo(): Memory = cleared(560).apply { setInt(0, 560) }
    fun pairedDevice(info: Pointer): WindowsPairedDevice? {
        if (info.getInt(24) == 0 && info.getInt(28) == 0) return null
        val address = info.getLong(8)
        if (address !in 1..0xffffffffffffL) return null
        val name = StringBuilder()
        for (i in 0 until 248) {
            val char = info.getShort(64L + i * 2).toInt() and 0xffff
            if (char == 0) break
            name.append(char.toChar())
        }
        return WindowsPairedDevice(address, name.toString())
    }
    // WinSock2.h: u_int fd_count, 4 bytes alignment padding, SOCKET fd_array[64].
    fun fdSet(socket: Long): Memory = cleared(520).apply {
        setInt(0, 1)
        setLong(8, socket)
    }
    fun containsSocket(set: Pointer, socket: Long): Boolean {
        val count = set.getInt(0)
        if (count !in 0..64) throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
        return (0 until count).any { set.getLong(8L + it * 8) == socket }
    }
    // Windows long is 32 bits, including under LLP64. This is not JNA NativeLong on Linux.
    fun timeval(timeoutMillis: Int): Memory = cleared(8).apply {
        setInt(0, timeoutMillis / 1000)
        setInt(4, (timeoutMillis % 1000) * 1000)
    }
    fun loopbackAddress(): Memory = cleared(16).apply {
        setShort(0, 2) // AF_INET; port zero lets Windows allocate an ephemeral loopback port.
        write(4, byteArrayOf(127, 0, 0, 1), 0, 4)
    }
    private fun cleared(size: Long) = Memory(size).apply { clear() }
}

internal interface WindowsDeviceBackend {
    fun first(search: Pointer, info: Pointer): Pointer?
    fun next(handle: Pointer, info: Pointer): Boolean
    fun lastError(): Int
    fun close(handle: Pointer)
}

internal class WindowsBluetoothDevices(private val backend: WindowsDeviceBackend) {
    fun pairedDevices(): List<WindowsPairedDevice> {
        WindowsBluetoothAbi.searchParams().use { search ->
            WindowsBluetoothAbi.deviceInfo().use { info ->
                val handle = backend.first(search, info)
                if (handle == null) {
                    if (backend.lastError() == 259) return emptyList() // ERROR_NO_MORE_ITEMS
                    failed()
                }
                try {
                    val devices = linkedMapOf<Long, WindowsPairedDevice>()
                    var records = 0
                    while (true) {
                        // Bound malformed/unexpected native enumeration as well as retained identities.
                        if (++records > 1024) failed()
                        WindowsBluetoothAbi.pairedDevice(info)?.let { devices.putIfAbsent(it.address, it) }
                        info.clear()
                        info.setInt(0, 560)
                        if (!backend.next(handle, info)) {
                            if (backend.lastError() != 259) failed()
                            return devices.values.toList()
                        }
                    }
                } finally { backend.close(handle) }
            }
        }
    }
    private fun failed(): Nothing = throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.ENUMERATION_FAILED)
}

internal interface WindowsBluetoothApi : StdCallLibrary {
    fun BluetoothFindFirstDevice(search: Pointer, info: Pointer): Pointer?
    fun BluetoothFindNextDevice(handle: Pointer, info: Pointer): Int
    fun BluetoothFindDeviceClose(handle: Pointer): Int
}

internal class WindowsDeviceJnaBackend(private val api: WindowsBluetoothApi) : WindowsDeviceBackend {
    private val error = ThreadLocal.withInitial { 0 }
    override fun first(search: Pointer, info: Pointer): Pointer? {
        val handle = api.BluetoothFindFirstDevice(search, info)
        error.set(if (handle == null) Native.getLastError() else 0)
        return handle
    }
    override fun next(handle: Pointer, info: Pointer): Boolean {
        val found = api.BluetoothFindNextDevice(handle, info) != 0
        error.set(if (!found) Native.getLastError() else 0)
        return found
    }
    override fun lastError(): Int = error.get()
    override fun close(handle: Pointer) {
        if (api.BluetoothFindDeviceClose(handle) == 0) {
            throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.ENUMERATION_FAILED)
        }
    }
}

/** Neither Windows DLL loads until the guarded public API calls the relevant operation. */
internal object WindowsBluetoothLibraries {
    val winsock: WindowsWinsockBackend by lazy {
        WindowsWinsockBackend(Native.load("Ws2_32", WindowsWinsockApi::class.java))
    }
    val devices: WindowsBluetoothDevices by lazy {
        val root = System.getenv("SystemRoot")
            ?: throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_UNAVAILABLE)
        val library = Path.of(root, "System32", "Bthprops.cpl")
        if (!library.isAbsolute) throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_UNAVAILABLE)
        // JNA 5.17 does not recognize .cpl in mapSharedLibraryName; an absolute path preserves it.
        // https://github.com/java-native-access/jna/blob/5.17.0/src/com/sun/jna/NativeLibrary.java
        WindowsBluetoothDevices(WindowsDeviceJnaBackend(Native.load(library.toString(), WindowsBluetoothApi::class.java)))
    }
}
