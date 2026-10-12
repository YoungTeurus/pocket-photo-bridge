package dev.pocketphotobridge.bridge.linux

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode

/** Linux LP64 ABI: int descriptors, socklen_t uint32, size_t/ssize_t native long. */
internal interface LinuxLibC : Library {
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun connect(fd: Int, address: Pointer, length: Int): Int
    fun poll(fds: Pointer, count: NativeLong, timeout: Int): Int
    fun getsockopt(fd: Int, level: Int, option: Int, value: IntByReference, length: IntByReference): Int
    fun send(fd: Int, buffer: Pointer, count: NativeLong, flags: Int): NativeLong
    fun recv(fd: Int, buffer: Pointer, count: NativeLong, flags: Int): NativeLong
    fun shutdown(fd: Int, how: Int): Int
    fun close(fd: Int): Int
}

internal object LinuxBluetoothAbi {
    fun address(address: String, channel: Int): Memory {
        val validated = bluetoothAddress(address)
        if (channel !in 1..30) radioFail(LinuxRadioFailureCode.INVALID_INPUT)
        return Memory(10).apply {
            clear(); setShort(0, 31)
            write(2, validated.split(':').reversed().map { it.toInt(16).toByte() }.toByteArray(), 0, 6)
            setByte(8, channel.toByte())
        }
    }
    fun pollfd(fd: Int, events: Int): Memory = Memory(8).apply { clear(); setInt(0, fd); setShort(4, events.toShort()) }
}

internal class LinuxNativeSocketBackend(private val api: LinuxLibC,
    private val report: (String) -> Unit = {}, private val lastError: () -> Int = Native::getLastError) : LinuxSocketBackend {
    override fun open(): Int {
        val fd = api.socket(31, 1 or 0x800 or 0x80000, 3) // AF_BLUETOOTH / STREAM / NONBLOCK / CLOEXEC / RFCOMM
        if (fd < 0) failed("SOCKET", lastError())
        emit("SOCKET", "OK")
        return fd
    }
    override fun beginConnect(socket: Int, address: String, channel: Int): Boolean {
        LinuxBluetoothAbi.address(address, channel).use {
            if (api.connect(socket, it, it.size().toInt()) == 0) { emit("CONNECT", "OK"); return true }
            val savedError = lastError() // Capture before reports or cleanup can call native code.
            if (savedError in setOf(11, 114, 115)) { emit("CONNECT", "PENDING", savedError); return false }
            failed("CONNECT", savedError)
        }
    }
    override fun poll(socket: Int, events: Int, timeoutMillis: Int): Int {
        LinuxBluetoothAbi.pollfd(socket, events).use {
            val count = api.poll(it, NativeLong(1), timeoutMillis)
            if (count < 0) {
                val savedError = lastError()
                if (savedError == 4) { emit("POLL", "PENDING", savedError); return 0 } // EINTR; caller checks cancellation/deadline.
                failed("POLL", savedError)
            }
            return if (count == 0) 0 else it.getShort(6).toInt() and 0xffff
        }
    }
    override fun connectSucceeded(socket: Int): Boolean {
        val value = IntByReference(); val length = IntByReference(4)
        if (api.getsockopt(socket, 1, 4, value, length) != 0) failed("SO_ERROR", lastError())
        if (length.value != 4) failed("SO_ERROR", 22)
        val error = value.value
        emit("SO_ERROR", if (error == 0) "OK" else "FAILED", error)
        if (error == 110) fail(error)
        return error == 0
    }
    override fun send(socket: Int, bytes: ByteArray, offset: Int, count: Int): Int {
        if (count <= 0 || offset < 0 || offset > bytes.size - count) throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR)
        Memory(count.toLong()).use {
            try {
                it.write(0, bytes, offset, count)
                val sent = api.send(socket, it, NativeLong(count.toLong()), 0x4000).toLong() // MSG_NOSIGNAL
                if (sent < 0) return pendingOrFail("SEND", lastError())
                if (sent > count) failed("SEND", 22)
                return sent.toInt()
            } finally { it.clear() }
        }
    }
    override fun receive(socket: Int, bytes: ByteArray): Int {
        if (bytes.isEmpty()) throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR)
        Memory(bytes.size.toLong()).use {
            try {
                val received = api.recv(socket, it, NativeLong(bytes.size.toLong()), 0).toLong()
                if (received < 0) return pendingOrFail("RECEIVE", lastError())
                if (received > bytes.size) failed("RECEIVE", 22)
                if (received > 0) it.read(0, bytes, 0, received.toInt())
                return received.toInt()
            } finally { it.clear() }
        }
    }
    override fun shutdown(socket: Int) {
        if (api.shutdown(socket, 2) != 0) { val error = lastError(); emit("SHUTDOWN", "FAILED", error) }
    }
    override fun close(socket: Int) {
        if (api.close(socket) != 0) { val error = lastError(); emit("CLOSE", "FAILED", error) }
    }
    private fun pendingOrFail(stage: String, savedError: Int): Int {
        if (savedError == 11 || savedError == 4) { emit(stage, "PENDING", savedError); return -1 }
        failed(stage, savedError)
    }
    private fun failed(stage: String, savedError: Int): Nothing { emit(stage, "FAILED", savedError); fail(savedError) }
    private fun fail(error: Int): Nothing = throw PrinterFailure(if (error == 110) PrinterFailureCode.TIMEOUT else PrinterFailureCode.DISCONNECTED)
    private fun emit(stage: String, reason: String, error: Int = 0) = reportRadio(report, "LINUX_BLUETOOTH_$stage reason=$reason native_error=${error.coerceAtLeast(0)}")
}

object LinuxBluetooth {
    internal fun library(): LinuxLibC {
        if (!System.getProperty("os.name", "").equals("Linux", true) || Native.POINTER_SIZE != 8 || Native.LONG_SIZE != 8 ||
            System.getProperty("os.arch", "").lowercase() !in setOf("amd64", "x86_64", "aarch64", "arm64")) radioFail(LinuxRadioFailureCode.UNSUPPORTED_PLATFORM)
        return try { Native.load("c", LinuxLibC::class.java) }
        catch (_: Exception) { radioFail(LinuxRadioFailureCode.NATIVE_UNAVAILABLE) }
        catch (_: LinkageError) { radioFail(LinuxRadioFailureCode.NATIVE_UNAVAILABLE) }
    }

    /** No valid descriptor is created; this never pairs, connects, scans, changes a radio or prints. */
    fun nativeSelfTest() {
        val api = library()
        LinuxBluetoothAbi.address("12:34:56:78:9A:BC", 17).use { address ->
            if (address.size() != 10L || address.getShort(0).toInt() != 31 || address.getByte(8).toInt() != 17) radioFail(LinuxRadioFailureCode.NATIVE_SELF_TEST_FAILED)
        }
        val error = IntByReference(); val length = IntByReference(4)
        val result = api.getsockopt(-1, 1, 4, error, length)
        val savedError = Native.getLastError()
        if (result != -1 || savedError != 9) radioFail(LinuxRadioFailureCode.NATIVE_SELF_TEST_FAILED)
        Memory(1).use {
            val received = api.recv(-1, it, NativeLong(1), 0).toLong()
            val receiveError = Native.getLastError()
            if (received != -1L || receiveError != 9) radioFail(LinuxRadioFailureCode.NATIVE_SELF_TEST_FAILED)
        }
        LinuxBluetoothAbi.pollfd(-1, LinuxPoll.READ).use {
            if (api.poll(it, NativeLong(1), 0) != 0 || it.getShort(6).toInt() != 0) radioFail(LinuxRadioFailureCode.NATIVE_SELF_TEST_FAILED)
        }
    }
}
