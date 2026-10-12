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
        assertEquals(1, api.binds)
        assertEquals(1, api.listens)
        assertEquals(1, api.selects)
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

    @Test fun `select supplies provider readiness using a full Windows fd set`() {
        val api = Api().apply { readiness = WindowsPoll.WRITE }
        val backend = WindowsWinsockBackend(api)
        assertEquals(WindowsPoll.WRITE, backend.poll(api.handle, WindowsPoll.WRITE, 75))
        assertEquals(75, api.pollTimeout)
        assertEquals(WindowsPoll.WRITE, api.pollEvents)
    }

    @Test fun `select observes exception set for a failed nonblocking connection`() {
        val api = Api().apply { readiness = WindowsPoll.ERROR; socketError = 10061 }
        val backend = WindowsWinsockBackend(api)
        assertEquals(WindowsPoll.ERROR, backend.poll(api.handle, WindowsPoll.WRITE, 100))
        assertFalse(backend.connectSucceeded(api.handle))
    }

    @Test fun `failed native connect preserves numeric errno before a reporting callback can change it`() {
        val api = Api().apply { connectResult = -1; error = 10060 }
        val events = mutableListOf<String>()
        val backend = WindowsWinsockBackend(api, WindowsBluetoothDiagnostics { event ->
            events += event
            if (event.contains("reason=FAILED")) api.error = 10054
        })
        val failure = assertThrows(PrinterFailure::class.java) { backend.beginConnect(api.handle, 0x001122334455) }
        assertEquals(PrinterFailureCode.TIMEOUT, failure.code)
        assertTrue(events.contains("WINDOWS_BLUETOOTH_CONNECT_START reason=FAILED native_error=10060 count=0"))
    }

    @Test fun `each native connection stage identifies errors with fixed labels`() {
        fun verify(stage: String, configure: Api.() -> Unit, operation: (WindowsWinsockBackend, Api) -> Unit) {
            val api = Api().apply(configure)
            val events = mutableListOf<String>()
            val backend = WindowsWinsockBackend(api, WindowsBluetoothDiagnostics(events::add))
            assertThrows(PrinterFailure::class.java) { operation(backend, api) }
            assertTrue("$stage missing: $events", events.any { it.startsWith("WINDOWS_BLUETOOTH_$stage reason=FAILED native_error=10022 ") })
        }
        verify("SOCKET_CREATE", { socketResult = -1; error = 10022 }) { backend, _ -> backend.openBluetoothSocket() }
        verify("SET_NONBLOCKING", { ioctlResult = -1; error = 10022 }) { backend, api -> backend.nonBlocking(api.handle) }
        verify("SELECT", { selectResult = -1; error = 10022 }) { backend, api -> backend.poll(api.handle, WindowsPoll.WRITE, 75) }
        verify("SO_ERROR", { getOptionResult = -1; error = 10022 }) { backend, api -> backend.connectSucceeded(api.handle) }
    }

    @Test fun `pending connect reports native pending code and keeps SPP resolution`() {
        val api = Api().apply { connectResult = -1; error = 10035 }
        val events = mutableListOf<String>()
        assertFalse(WindowsWinsockBackend(api, WindowsBluetoothDiagnostics(events::add)).beginConnect(api.handle, 0x001122334455))
        assertTrue(events.contains("WINDOWS_BLUETOOTH_CONNECT_PENDING reason=PENDING native_error=10035 count=0"))
        assertEquals(0, api.connectPort)
        assertEquals(0x1101, api.connectService)
    }

    @Test fun `diagnostic callback failures do not alter readiness errors or self test cleanup`() {
        val api = Api().apply { selectResult = -1; error = 10022 }
        val backend = WindowsWinsockBackend(api, WindowsBluetoothDiagnostics { throw IllegalStateException("private reporting failure") })
        val failure = assertThrows(PrinterFailure::class.java) { backend.poll(api.handle, WindowsPoll.WRITE, 75) }
        assertEquals(PrinterFailureCode.DISCONNECTED, failure.code)
        assertEquals("DISCONNECTED", failure.message)
        val selfTest = assertThrows(WindowsBluetoothFailure::class.java) { backend.nativeSelfTest() }
        assertEquals(WindowsBluetoothFailureCode.NATIVE_SELF_TEST_FAILED, selfTest.code)
        assertEquals(1, api.closes)
    }

    @Test fun `select rebuilds fd sets after timeout and supports readability`() {
        val api = Api()
        val backend = WindowsWinsockBackend(api)
        assertEquals(0, backend.poll(api.handle, WindowsPoll.READ, 100))
        api.readiness = WindowsPoll.READ
        assertEquals(WindowsPoll.READ, backend.poll(api.handle, WindowsPoll.READ, 100))
        assertEquals(2, api.selects)
    }

    @Test fun `diagnostic assertion failure cannot replace the original native timeout`() {
        val api = Api().apply { connectResult = -1; error = 10060 }
        val backend = WindowsWinsockBackend(api, WindowsBluetoothDiagnostics { throw AssertionError("private sink detail") })
        val failure = assertThrows(PrinterFailure::class.java) { backend.beginConnect(api.handle, 0x001122334455) }
        assertEquals(PrinterFailureCode.TIMEOUT, failure.code)
        assertEquals("TIMEOUT", failure.message)
    }

    @Test fun `failed asynchronous connection exposes SO_ERROR and releases socket even if report throws`() {
        val api = Api().apply { connectResult = -1; error = 10035; readiness = WindowsPoll.ERROR; socketError = 10060 }
        val events = mutableListOf<String>()
        val diagnostics = WindowsBluetoothDiagnostics { event -> events += event; throw IllegalStateException("private sink detail") }
        val backend = WindowsWinsockBackend(api, diagnostics)
        val failure = assertThrows(PrinterFailure::class.java) {
            WindowsBluetoothAdapter(backend, diagnostics = diagnostics).connect(WindowsPairedDevice(0x001122334455, "Private Printer"), 500)
        }
        assertEquals(PrinterFailureCode.TIMEOUT, failure.code)
        assertEquals(1, api.closes)
        assertTrue(events.any { it == "WINDOWS_BLUETOOTH_SO_ERROR reason=FAILED native_error=10060 count=0" })
        assertTrue(events.none { it.startsWith("WINDOWS_BLUETOOTH_CONNECTED ") })
        val permitted = Regex("WINDOWS_BLUETOOTH_[A-Z_]+ reason=[A-Z_]+ native_error=[0-9]+ count=[0-9]+")
        assertTrue(events.all(permitted::matches))
        assertFalse(events.joinToString().contains("Private Printer"))
        assertFalse(events.joinToString().contains("001122334455"))
        assertFalse(events.joinToString().contains(api.handle.toString()))
        assertFalse(events.joinToString().contains("private sink detail"))
    }

    @Test fun `per connection reporters share only startup state and never inherit prior callback`() {
        val api = Api()
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val base = WindowsWinsockBackend(api)
        base.withDiagnostics(WindowsBluetoothDiagnostics(first::add)).openBluetoothSocket()
        val retained = first.toList()
        base.withDiagnostics(WindowsBluetoothDiagnostics(second::add)).openBluetoothSocket()
        assertEquals(retained, first)
        assertEquals(1, api.starts)
        assertTrue(second.any { it.startsWith("WINDOWS_BLUETOOTH_SOCKET_CREATE ") })
    }

    @Test fun `self test releases listener after bind or listen failure`() {
        listOf(true, false).forEach { binding ->
            val api = Api().apply { if (binding) bindResult = -1 else listenResult = -1; error = 10022 }
            val error = assertThrows(WindowsBluetoothFailure::class.java) { WindowsWinsockBackend(api).nativeSelfTest() }
            assertEquals(WindowsBluetoothFailureCode.NATIVE_SELF_TEST_FAILED, error.code)
            assertEquals(1, api.closes)
            assertEquals(0, api.selects)
        }
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
        var binds = 0
        var listens = 0
        var selects = 0
        var startupError = 0
        var error = 0
        var closeResult = 0
        var socketResult = handle
        var ioctlResult = 0
        var bindResult = 0
        var listenResult = 0
        var selectResult: Int? = null
        var getOptionResult = 0
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
        override fun socket(family: Int, type: Int, protocol: Int): Long { opened += listOf(family, type, protocol); return socketResult }
        override fun ioctlsocket(socket: Long, command: Int, value: IntByReference): Int {
            assertEquals(handle, socket); ioctlCommand = command; ioctlValue = value.value; return ioctlResult
        }
        override fun connect(socket: Long, address: Pointer, size: Int): Int {
            assertEquals(handle, socket); connects++; connectSize = size; connectAddress = address.getLong(2)
            connectService = address.getInt(10); connectPort = address.getInt(26); return connectResult
        }
        override fun select(nfds: Int, read: Pointer?, write: Pointer?, except: Pointer?, timeout: Pointer): Int {
            selects++
            assertEquals(0, nfds)
            pollTimeout = timeout.getInt(0) * 1000 + timeout.getInt(4) / 1000
            pollEvents = if (read != null) WindowsPoll.READ else WindowsPoll.WRITE
            val status = selectResult ?: if (readiness == 0) 0 else 1
            if (status == -1) return -1
            listOf(read to WindowsPoll.READ, write to WindowsPoll.WRITE, except to WindowsPoll.ERROR).forEach { (set, flag) ->
                if (set != null) {
                    assertEquals(1, set.getInt(0))
                    assertEquals(handle, set.getLong(8))
                    set.setInt(0, if (readiness and flag != 0) 1 else 0)
                }
            }
            return status
        }
        override fun bind(socket: Long, address: Pointer, size: Int): Int {
            assertEquals(handle, socket)
            assertEquals(16, size)
            assertEquals(2.toShort(), address.getShort(0))
            assertEquals(0.toShort(), address.getShort(2))
            assertArrayEquals(byteArrayOf(127, 0, 0, 1), address.getByteArray(4, 4))
            binds++
            return bindResult
        }
        override fun listen(socket: Long, backlog: Int): Int {
            assertEquals(handle, socket)
            assertEquals(1, backlog)
            listens++
            return listenResult
        }
        override fun getsockopt(socket: Long, level: Int, option: Int, value: IntByReference, size: IntByReference): Int {
            assertEquals(handle, socket); assertEquals(0xffff, level); assertEquals(0x1007, option); assertEquals(4, size.value)
            value.value = socketError; return getOptionResult
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
