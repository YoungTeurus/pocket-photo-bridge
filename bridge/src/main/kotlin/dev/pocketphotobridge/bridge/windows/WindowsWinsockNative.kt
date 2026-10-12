package dev.pocketphotobridge.bridge.windows

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode

/** WinSock2.h uses UINT_PTR SOCKET: Java Long is correct only for guarded Windows x64. */
internal interface WindowsWinsockApi : StdCallLibrary {
    fun WSAStartup(version: Short, data: Pointer): Int
    fun WSACleanup(): Int
    fun socket(family: Int, type: Int, protocol: Int): Long
    fun ioctlsocket(socket: Long, command: Int, value: IntByReference): Int
    fun connect(socket: Long, address: Pointer, size: Int): Int
    fun WSAPoll(fds: Pointer, count: Int, timeout: Int): Int
    fun getsockopt(socket: Long, level: Int, option: Int, value: IntByReference, size: IntByReference): Int
    fun send(socket: Long, bytes: Pointer, length: Int, flags: Int): Int
    fun recv(socket: Long, bytes: Pointer, length: Int, flags: Int): Int
    fun shutdown(socket: Long, how: Int): Int
    fun closesocket(socket: Long): Int
    fun WSAGetLastError(): Int
}

internal class WindowsWinsockBackend(private val api: WindowsWinsockApi) : WindowsSocketBackend {
    private var started = false

    fun nativeSelfTest() {
        try {
            ensureStarted()
            // AF_INET/SOCK_STREAM/IPPROTO_TCP validates Winsock independently of AF_BTH/radios.
            val socket = api.socket(2, 1, 6)
            if (socket == -1L) throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
            if (api.closesocket(socket) != 0) throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
        } catch (_: PrinterFailure) {
            throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_SELF_TEST_FAILED)
        } catch (_: WindowsBluetoothFailure) {
            throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_SELF_TEST_FAILED)
        }
    }

    override fun openBluetoothSocket(): Long {
        ensureStarted()
        // AF_BTH/SOCK_STREAM/BTHPROTO_RFCOMM; no BLE or driver-created COM port.
        val socket = api.socket(32, 1, 3)
        if (socket == -1L) nativeFailure()
        return socket
    }

    @Synchronized private fun ensureStarted() {
        if (started) return
        // Win64 WSADATA is 408 bytes, including its 8-byte-aligned vendor pointer.
        Memory(408).use { data ->
            data.clear()
            if (api.WSAStartup(0x0202, data) != 0) {
                throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_UNAVAILABLE)
            }
            if (data.getShort(0) != 0x0202.toShort()) {
                api.WSACleanup()
                throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_UNAVAILABLE)
            }
        }
        // One successful startup reference lives for the process; cleanup must not invalidate live sockets.
        started = true
    }

    override fun nonBlocking(socket: Long) {
        // FIONBIO: Windows unsigned long command/value are both 32 bits.
        if (api.ioctlsocket(socket, 0x8004667e.toInt(), IntByReference(1)) != 0) nativeFailure()
    }

    override fun beginConnect(socket: Long, address: Long): Boolean {
        WindowsBluetoothAbi.socketAddress(address).use { endpoint ->
            if (api.connect(socket, endpoint, endpoint.size().toInt()) == 0) return true
            return when (api.WSAGetLastError()) {
                10035, 10036, 10037 -> false // WSAEWOULDBLOCK/WSAEINPROGRESS/WSAEALREADY
                10060 -> throw PrinterFailure(PrinterFailureCode.TIMEOUT)
                else -> throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
            }
        }
    }

    override fun poll(socket: Long, events: Int, timeoutMillis: Int): Int {
        WindowsBluetoothAbi.pollFd(socket, events).use { fd ->
            val count = api.WSAPoll(fd, 1, timeoutMillis)
            if (count == -1) nativeFailure()
            return if (count == 0) 0 else fd.getShort(10).toInt() and 0xffff
        }
    }

    override fun connectSucceeded(socket: Long): Boolean {
        val error = IntByReference()
        val size = IntByReference(4)
        if (api.getsockopt(socket, 0xffff, 0x1007, error, size) != 0) nativeFailure()
        if (size.value != 4) throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
        if (error.value == 10060) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
        return error.value == 0
    }

    override fun send(socket: Long, bytes: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0 || offset < 0 || offset > bytes.size - length) {
            throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR)
        }
        Memory(length.toLong()).use { buffer ->
            try {
                buffer.write(0, bytes, offset, length)
                val count = api.send(socket, buffer, length, 0)
                return if (count == -1) wouldBlockOrFail() else count
            } finally { buffer.clear() }
        }
    }

    override fun receive(socket: Long, bytes: ByteArray): Int {
        if (bytes.isEmpty()) throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR)
        Memory(bytes.size.toLong()).use { buffer ->
            try {
                val count = api.recv(socket, buffer, bytes.size, 0)
                if (count == -1) return wouldBlockOrFail()
                if (count > bytes.size) throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
                if (count > 0) buffer.read(0, bytes, 0, count)
                return count
            } finally { buffer.clear() }
        }
    }

    override fun shutdown(socket: Long) { api.shutdown(socket, 2) } // SD_BOTH; cleanup tolerates unconnected sockets.
    override fun close(socket: Long) {
        if (api.closesocket(socket) != 0) nativeFailure()
    }
    private fun wouldBlockOrFail(): Int {
        val error = api.WSAGetLastError()
        if (error == 10035) return -1
        fail(error)
    }
    private fun nativeFailure(): Nothing = fail(api.WSAGetLastError())
    private fun fail(error: Int): Nothing = throw PrinterFailure(
        if (error == 10060) PrinterFailureCode.TIMEOUT else PrinterFailureCode.DISCONNECTED)
}
