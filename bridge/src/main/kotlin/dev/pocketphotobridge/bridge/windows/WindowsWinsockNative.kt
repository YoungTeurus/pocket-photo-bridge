package dev.pocketphotobridge.bridge.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import dev.pocketphotobridge.bridge.windows.WindowsBluetoothDiagnosticStage.*
import dev.pocketphotobridge.bridge.windows.WindowsBluetoothDiagnosticReason.*

/** WinSock2.h uses UINT_PTR SOCKET: Java Long is correct only for guarded Windows x64. */
internal interface WindowsWinsockApi : StdCallLibrary {
    fun WSAStartup(version: Short, data: Pointer): Int
    fun WSACleanup(): Int
    fun socket(family: Int, type: Int, protocol: Int): Long
    fun ioctlsocket(socket: Long, command: Int, value: IntByReference): Int
    fun connect(socket: Long, address: Pointer, size: Int): Int
    fun select(nfds: Int, read: Pointer?, write: Pointer?, except: Pointer?, timeout: Pointer): Int
    fun bind(socket: Long, address: Pointer, size: Int): Int
    fun listen(socket: Long, backlog: Int): Int
    fun getsockopt(socket: Long, level: Int, option: Int, value: IntByReference, size: IntByReference): Int
    fun send(socket: Long, bytes: Pointer, length: Int, flags: Int): Int
    fun recv(socket: Long, bytes: Pointer, length: Int, flags: Int): Int
    fun shutdown(socket: Long, how: Int): Int
    fun closesocket(socket: Long): Int
}

internal class WindowsWinsockBackend private constructor(private val api: WindowsWinsockApi,
    private val diagnostics: WindowsBluetoothDiagnostics, private val startup: Startup) : WindowsSocketBackend {
    constructor(api: WindowsWinsockApi, diagnostics: WindowsBluetoothDiagnostics = WindowsBluetoothDiagnostics()) :
        this(api, diagnostics, Startup(api))

    /** Shares only Winsock's startup reference. Each connection retains its own immutable diagnostic sink. */
    fun withDiagnostics(diagnostics: WindowsBluetoothDiagnostics) = WindowsWinsockBackend(api, diagnostics, startup)

    fun nativeSelfTest() {
        try {
            startup.ensureStarted(diagnostics)
            // JNA saves Win32 last-error immediately after each mapped call. A second
            // mapped WSAGetLastError can lose it during JNI/VM transitions. Exercise a
            // deterministic real failure on Windows, with no radio or remote endpoint.
            Native.setLastError(0) // A stale 10038 must not make the probe pass.
            val rejected = api.ioctlsocket(-1L, 0x8004667e.toInt(), IntByReference(1))
            val captured = Native.getLastError()
            if (rejected != -1 || captured != 10038) // WSAENOTSOCK for INVALID_SOCKET.
                throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_SELF_TEST_FAILED)
            // AF_INET/SOCK_STREAM/IPPROTO_TCP validates Winsock independently of AF_BTH/radios.
            val socket = api.socket(2, 1, 6)
            if (socket == -1L) throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
            try {
                nonBlocking(socket)
                WindowsBluetoothAbi.loopbackAddress().use { local ->
                    if (api.bind(socket, local, local.size().toInt()) != 0) nativeFailure(SELF_TEST_BIND)
                }
                if (api.listen(socket, 1) != 0) nativeFailure(SELF_TEST_LISTEN)
                // A bound listener exercises real select/fd_set/timeval ABI without a radio or remote host.
                // A fresh unconnected TCP socket is unsuitable: select can reject it with WSAEINVAL.
                poll(socket, WindowsPoll.READ, 0)
            } finally { close(socket) }
        } catch (_: PrinterFailure) {
            throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_SELF_TEST_FAILED)
        } catch (_: WindowsBluetoothFailure) {
            throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_SELF_TEST_FAILED)
        }
    }

    override fun openBluetoothSocket(): Long {
        startup.ensureStarted(diagnostics)
        // AF_BTH/SOCK_STREAM/BTHPROTO_RFCOMM; no BLE or driver-created COM port.
        diagnostics.emit(SOCKET_CREATE, START)
        val socket = api.socket(32, 1, 3)
        if (socket == -1L) nativeFailure(SOCKET_CREATE)
        diagnostics.emit(SOCKET_CREATE, OK)
        return socket
    }

    private class Startup(private val api: WindowsWinsockApi) {
        private var started = false
        @Synchronized fun ensureStarted(diagnostics: WindowsBluetoothDiagnostics) {
            if (started) return
            diagnostics.emit(WINSOCK_STARTUP, START)
            // Win64 WSADATA is 408 bytes, including its 8-byte-aligned vendor pointer.
            Memory(408).use { data ->
                data.clear()
                val error = api.WSAStartup(0x0202, data)
                if (error != 0) {
                    diagnostics.emit(WINSOCK_STARTUP, FAILED, error)
                    throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_UNAVAILABLE)
                }
                if (data.getShort(0) != 0x0202.toShort()) {
                    api.WSACleanup()
                    diagnostics.emit(WINSOCK_STARTUP, FAILED)
                    throw WindowsBluetoothFailure(WindowsBluetoothFailureCode.NATIVE_UNAVAILABLE)
                }
            }
            // One startup reference lives for the process; cleanup must not invalidate live sockets.
            started = true
            diagnostics.emit(WINSOCK_STARTUP, OK)
        }
    }

    override fun nonBlocking(socket: Long) {
        // FIONBIO: Windows unsigned long command/value are both 32 bits.
        diagnostics.emit(SET_NONBLOCKING, START)
        if (api.ioctlsocket(socket, 0x8004667e.toInt(), IntByReference(1)) != 0) nativeFailure(SET_NONBLOCKING)
        diagnostics.emit(SET_NONBLOCKING, OK)
    }

    override fun beginConnect(socket: Long, address: Long): Boolean {
        WindowsBluetoothAbi.socketAddress(address).use { endpoint ->
            diagnostics.emit(CONNECT_START, START)
            if (api.connect(socket, endpoint, endpoint.size().toInt()) == 0) {
                diagnostics.emit(CONNECT_START, OK)
                return true
            }
            // Capture errno before a sink can make another native call and alter thread-local error state.
            val error = Native.getLastError()
            if (error in 10035..10037) {
                diagnostics.emit(CONNECT_PENDING, PENDING, error)
                return false
            }
            diagnostics.emit(CONNECT_START, FAILED, error)
            fail(error)
        }
    }

    override fun poll(socket: Long, events: Int, timeoutMillis: Int): Int {
        // Bluetooth explicitly supports select. Winsock dispatches it through the socket's provider.
        // https://learn.microsoft.com/windows/win32/bluetooth/bluetooth-and-listen-select-and-closesocket
        // Existing RFCOMM example: https://github.com/brltty/brltty/blob/master/Programs/bluetooth_windows.c
        WindowsBluetoothAbi.fdSet(socket).use { ready ->
            WindowsBluetoothAbi.fdSet(socket).use { exceptional ->
                WindowsBluetoothAbi.timeval(timeoutMillis).use { timeout ->
                    val reading = events and WindowsPoll.READ != 0
                    val writing = events and WindowsPoll.WRITE != 0
                    diagnostics.emit(SELECT, START)
                    val count = api.select(0, if (reading) ready else null,
                        if (writing) ready else null, exceptional, timeout)
                    if (count == -1) nativeFailure(SELECT)
                    diagnostics.emit(SELECT, if (count == 0) TIMEOUT else READY, count = count)
                    if (count == 0) return 0
                    var result = if (WindowsBluetoothAbi.containsSocket(exceptional, socket)) WindowsPoll.ERROR else 0
                    if (WindowsBluetoothAbi.containsSocket(ready, socket)) result = result or events
                    return result
                }
            }
        }
    }

    override fun connectSucceeded(socket: Long): Boolean {
        val error = IntByReference()
        val size = IntByReference(4)
        diagnostics.emit(SO_ERROR, START)
        if (api.getsockopt(socket, 0xffff, 0x1007, error, size) != 0) nativeFailure(SO_ERROR)
        if (size.value != 4) throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
        diagnostics.emit(SO_ERROR, if (error.value == 0) OK else FAILED, error.value)
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
                return if (count == -1) wouldBlockOrFail(SEND) else count
            } finally { buffer.clear() }
        }
    }

    override fun receive(socket: Long, bytes: ByteArray): Int {
        if (bytes.isEmpty()) throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR)
        Memory(bytes.size.toLong()).use { buffer ->
            try {
                val count = api.recv(socket, buffer, bytes.size, 0)
                if (count == -1) return wouldBlockOrFail(RECEIVE)
                if (count > bytes.size) throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
                if (count == 0) diagnostics.emit(RECEIVE, EOF)
                if (count > 0) buffer.read(0, bytes, 0, count)
                return count
            } finally { buffer.clear() }
        }
    }

    override fun shutdown(socket: Long) {
        if (api.shutdown(socket, 2) != 0) {
            val error = Native.getLastError()
            diagnostics.emit(SHUTDOWN, FAILED, error)
        }
    } // SD_BOTH; cleanup tolerates unconnected sockets.
    override fun close(socket: Long) {
        if (api.closesocket(socket) != 0) nativeFailure(CLOSE)
        diagnostics.emit(CLOSE, OK)
    }
    private fun wouldBlockOrFail(stage: WindowsBluetoothDiagnosticStage): Int {
        val error = Native.getLastError()
        if (error == 10035) {
            diagnostics.emit(stage, PENDING, error)
            return -1
        }
        diagnostics.emit(stage, FAILED, error)
        fail(error)
    }
    private fun nativeFailure(stage: WindowsBluetoothDiagnosticStage): Nothing {
        val error = Native.getLastError()
        diagnostics.emit(stage, FAILED, error)
        fail(error)
    }
    private fun fail(error: Int): Nothing = throw PrinterFailure(
        if (error == 10060) PrinterFailureCode.TIMEOUT else PrinterFailureCode.DISCONNECTED)
}
