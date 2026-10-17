package dev.pocketphotobridge.bridge.linux

import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import org.junit.Assert.*
import org.junit.Test

class LinuxBluetoothNativeTest {
    @Test fun `RFCOMM address has Linux family little endian address channel and trailing padding`() {
        LinuxBluetoothAbi.address("12:34:56:78:9A:BC", 17).use {
            assertEquals(10L, it.size())
            assertEquals(31, it.getShort(0).toInt())
            assertArrayEquals(byteArrayOf(0xbc.toByte(), 0x9a.toByte(), 0x78, 0x56, 0x34, 0x12), it.getByteArray(2, 6))
            assertEquals(17, it.getByte(8).toInt()); assertEquals(0, it.getByte(9).toInt())
        }
    }

    @Test fun `native pending and would block errno are saved before a reporting callback`() {
        val native = FakeLibC()
        var errno = 115
        val reports = mutableListOf<String>()
        val backend = LinuxNativeSocketBackend(native, { reports += it; errno = 9 }, { errno })
        assertFalse(backend.beginConnect(42, "AA:BB:CC:DD:EE:FF", 7))
        assertTrue(reports.single().contains("native_error=115"))
        errno = 11
        assertEquals(-1, backend.send(42, byteArrayOf(1), 0, 1))
        assertTrue(reports.last().contains("native_error=11"))
        errno = 110
        val failure = assertThrows(PrinterFailure::class.java) { backend.receive(42, ByteArray(1)) }
        assertEquals(PrinterFailureCode.TIMEOUT, failure.code)
        assertTrue(reports.last().contains("native_error=110"))
    }

    @Test fun `socket opens nonblocking cloexec classic RFCOMM and poll maps native structure`() {
        val native = FakeLibC().apply { socketResult = 42 }
        val backend = LinuxNativeSocketBackend(native)
        assertEquals(42, backend.open())
        assertEquals(listOf(31, 1 or 0x800 or 0x80000, 3), native.socketArguments)
        assertEquals(LinuxPoll.READ, backend.poll(42, LinuxPoll.READ, 100))
        assertEquals(8, native.pollSize)
    }

    @Test fun `SO_ERROR value and native timeout are mapped without arbitrary text`() {
        val native = FakeLibC()
        val backend = LinuxNativeSocketBackend(native)
        assertTrue(backend.connectSucceeded(42))
        native.socketError = 111
        assertFalse(backend.connectSucceeded(42))
        native.socketError = 110
        assertEquals(PrinterFailureCode.TIMEOUT, assertThrows(PrinterFailure::class.java) { backend.connectSucceeded(42) }.code)
    }

    private class FakeLibC : LinuxLibC {
        var socketResult = -1
        var socketError = 0
        var pollSize = 0
        var socketArguments = emptyList<Int>()
        override fun socket(domain: Int, type: Int, protocol: Int): Int { socketArguments = listOf(domain, type, protocol); return socketResult }
        override fun connect(fd: Int, address: Pointer, length: Int): Int = -1
        override fun poll(fds: Pointer, count: NativeLong, timeout: Int): Int { pollSize = 8; assertEquals(42, fds.getInt(0)); fds.setShort(6, LinuxPoll.READ.toShort()); return 1 }
        override fun getsockopt(fd: Int, level: Int, option: Int, value: IntByReference, length: IntByReference): Int { assertEquals(1, level); assertEquals(4, option); value.value = socketError; return 0 }
        override fun send(fd: Int, buffer: Pointer, count: NativeLong, flags: Int): NativeLong = NativeLong(-1)
        override fun recv(fd: Int, buffer: Pointer, count: NativeLong, flags: Int): NativeLong = NativeLong(-1)
        override fun shutdown(fd: Int, how: Int): Int = 0
        override fun close(fd: Int): Int = 0
    }
}
