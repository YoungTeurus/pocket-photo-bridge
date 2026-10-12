package dev.pocketphotobridge.bridge.windows

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import org.junit.Assert.*
import org.junit.Test

class WindowsBluetoothWinsockTest {
    @Test fun `native self test uses ordinary TCP without opening Bluetooth`() {
        val api = Api()
        WindowsWinsockBackend(api).nativeSelfTest()
        assertEquals(listOf(listOf(2, 1, 6)), api.opened)
        assertEquals(1, api.starts)
        assertEquals(1, api.closes)
        assertEquals(0, api.connects)
    }

    @Test fun `WSAStartup negotiates 2 2 and is shared across socket operations`() {
        val api = Api()
        val backend = WindowsWinsockBackend(api)
        backend.nativeSelfTest()
        assertEquals(api.handle, backend.openBluetoothSocket())
        assertEquals(listOf(32, 1, 3), api.opened.last())
        assertEquals(1, api.starts)
    }

    @Test fun `native self test startup failure has only a fixed category`() {
        val api = Api().apply { startupError = 10092 }
        val failure = assertThrows(WindowsBluetoothFailure::class.java) { WindowsWinsockBackend(api).nativeSelfTest() }
        assertEquals(WindowsBluetoothFailureCode.NATIVE_SELF_TEST_FAILED, failure.code)
        assertEquals("NATIVE_SELF_TEST_FAILED", failure.message)
        assertNull(failure.cause)
        assertTrue(api.opened.isEmpty())
    }

    @Test fun `self test fails when ordinary socket cannot be closed`() {
        val api = Api().apply { closeResult = -1 }
        val failure = assertThrows(WindowsBluetoothFailure::class.java) { WindowsWinsockBackend(api).nativeSelfTest() }
        assertEquals(WindowsBluetoothFailureCode.NATIVE_SELF_TEST_FAILED, failure.code)
        assertEquals(1, api.closes)
    }

    @Test fun `native connection is nonblocking and passes packed SPP address`() {
        val api = Api().apply { connectResult = -1; error = 10035 }
        val backend = WindowsWinsockBackend(api)
        backend.nonBlocking(api.handle)
        assertFalse(backend.beginConnect(api.handle, 0x001122334455))
        assertEquals(0x8004667e.toInt(), api.ioctlCommand)
        assertEquals(1, api.ioctlValue)
        assertEquals(30, api.connectSize)
        assertEquals(0x001122334455L, api.connectAddress)
        assertEquals(0, api.connectPort)
        assertEquals(0x1101, api.connectService)
    }

    @Test fun `poll reads Windows readiness from packed fd result`() {
        val api = Api().apply { readiness = WindowsPoll.WRITE }
        val backend = WindowsWinsockBackend(api)
        assertEquals(WindowsPoll.WRITE, backend.poll(api.handle, WindowsPoll.WRITE, 75))
        assertEquals(75, api.pollTimeout)
        assertEquals(WindowsPoll.WRITE, api.pollEvents)
    }

    @Test fun `SO_ERROR is queried with Win32 integer width`() {
        val api = Api().apply { socketError = 10061 }
        assertFalse(WindowsWinsockBackend(api).connectSucceeded(api.handle))
        api.socketError = 0
        assertTrue(WindowsWinsockBackend(api).connectSucceeded(api.handle))
    }

    @Test fun `partial native send uses requested slice and receive returns only written bytes`() {
        val api = Api().apply { sendResult = 2; received = byteArrayOf(5, 6) }
        val backend = WindowsWinsockBackend(api)
        assertEquals(2, backend.send(api.handle, byteArrayOf(99, 1, 2, 3, 98), 1, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), api.sent)
        val buffer = ByteArray(4)
        assertEquals(2, backend.receive(api.handle, buffer))
        assertArrayEquals(byteArrayOf(5, 6, 0, 0), buffer)
    }

    @Test fun `would block is retryable but native errors never escape`() {
        val api = Api().apply { sendResult = -1; error = 10035 }
        val backend = WindowsWinsockBackend(api)
        assertEquals(-1, backend.send(api.handle, byteArrayOf(1), 0, 1))
        api.error = 10054
        val failure = assertThrows(PrinterFailure::class.java) { backend.send(api.handle, byteArrayOf(1), 0, 1) }
        assertEquals(PrinterFailureCode.DISCONNECTED, failure.code)
        assertEquals("DISCONNECTED", failure.message)
        assertNull(failure.cause)
    }

    private class Api : WindowsWinsockApi {
        val handle = 0x1234567800000042L
        var starts = 0
        var closes = 0
        var connects = 0
        var startupError = 0
        var error = 0
        var closeResult = 0
        var connectResult = 0
        var connectAddress = 0L
        var connectService = 0
        var connectPort = -1
        var connectSize = 0
        var ioctlCommand = 0
        var ioctlValue = 0
        var readiness = 0
        var pollTimeout = 0
        var pollEvents = 0
        var socketError = 0
        var sendResult = 0
        var sent = byteArrayOf()
        var received = byteArrayOf()
        val opened = mutableListOf<List<Int>>()
        override fun WSAStartup(version: Short, data: Pointer): Int {
            assertEquals(0x0202.toShort(), version)
            data.setShort(0, 0x0202)
            starts++
            return startupError
        }
        override fun WSACleanup() = 0
        override fun socket(family: Int, type: Int, protocol: Int): Long { opened += listOf(family, type, protocol); return handle }
        override fun ioctlsocket(socket: Long, command: Int, value: IntByReference): Int {
            assertEquals(handle, socket); ioctlCommand = command; ioctlValue = value.value; return 0
        }
        override fun connect(socket: Long, address: Pointer, size: Int): Int {
            assertEquals(handle, socket); connects++; connectSize = size; connectAddress = address.getLong(2)
            connectService = address.getInt(10); connectPort = address.getInt(26); return connectResult
        }
        override fun WSAPoll(fds: Pointer, count: Int, timeout: Int): Int {
            assertEquals(handle, fds.getLong(0)); assertEquals(1, count)
            pollEvents = fds.getShort(8).toInt(); pollTimeout = timeout; fds.setShort(10, readiness.toShort())
            return if (readiness == 0) 0 else 1
        }
        override fun getsockopt(socket: Long, level: Int, option: Int, value: IntByReference, size: IntByReference): Int {
            assertEquals(handle, socket); assertEquals(0xffff, level); assertEquals(0x1007, option); assertEquals(4, size.value)
            value.value = socketError; return 0
        }
        override fun send(socket: Long, bytes: Pointer, length: Int, flags: Int): Int {
            assertEquals(handle, socket); assertEquals(0, flags); sent = bytes.getByteArray(0, length); return sendResult
        }
        override fun recv(socket: Long, bytes: Pointer, length: Int, flags: Int): Int {
            assertEquals(handle, socket); assertEquals(0, flags)
            bytes.write(0, received, 0, received.size); return received.size
        }
        override fun shutdown(socket: Long, how: Int): Int { assertEquals(handle, socket); assertEquals(2, how); return 0 }
        override fun closesocket(socket: Long): Int { assertEquals(handle, socket); closes++; return closeResult }
        override fun WSAGetLastError() = error
    }
}
