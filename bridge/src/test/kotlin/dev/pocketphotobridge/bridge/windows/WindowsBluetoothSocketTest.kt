package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WindowsBluetoothSocketTest {
    private val device = WindowsPairedDevice(0x001122334455, "local printer")

    @Test fun `successful connected event is private and belongs only to that connection`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val native = FakeSocket()
        WindowsBluetoothAdapter(native, diagnostics = WindowsBluetoothDiagnostics(first::add)).connect(device, 500).close()
        WindowsBluetoothAdapter(native, diagnostics = WindowsBluetoothDiagnostics(second::add)).connect(device, 500).close()
        assertEquals(listOf("WINDOWS_BLUETOOTH_CONNECTED reason=OK native_error=0 count=0"), first)
        assertEquals(first, second)
        assertFalse(first.joinToString().contains(device.name))
        assertFalse(first.joinToString().contains(device.address.toString()))
    }

    @Test fun `throwing connected report cannot change socket success or cleanup`() {
        val native = FakeSocket()
        WindowsBluetoothAdapter(native, diagnostics = WindowsBluetoothDiagnostics { throw UnsatisfiedLinkError("secret handle") })
            .connect(device, 500).close()
        assertEquals(1, native.closeCount)
    }

    @Test fun `writes every byte exactly once after partial writes and backpressure`() {
        val native = FakeSocket()
        native.sends.addAll(listOf(2, -1, 1, 2))
        val transport = adapter(native).connect(device, 500)
        transport.write(byteArrayOf(10, 20, 30, 40, 50))
        assertArrayEquals(byteArrayOf(10, 20, 30, 40, 50), native.sent.toByteArray())
        assertEquals(listOf(WindowsPoll.WRITE), native.pollEvents)
        transport.close()
    }

    @Test fun `connect times out at one deadline and releases its socket`() {
        val native = FakeSocket().apply { connected = false; ready = 0 }
        failure(PrinterFailureCode.TIMEOUT) { adapter(native).connect(device, 250) }
        assertEquals(listOf(100, 100, 50), native.pollTimeouts)
        assertEquals(1, native.shutdownCount)
        assertEquals(1, native.closeCount)
    }

    @Test fun `connect readiness must pass SO_ERROR before transport is returned`() {
        val native = FakeSocket().apply { connected = false; succeeded = false }
        failure(PrinterFailureCode.DISCONNECTED) { adapter(native).connect(device, 500) }
        assertEquals(1, native.connectChecks)
        assertEquals(1, native.closeCount)
    }

    @Test fun `read deadline survives spurious readiness and would block`() {
        val native = FakeSocket().apply { receiveResults.addAll(listOf(-1, -1, -1)); pollAdvanceMillis = 50 }
        val transport = adapter(native).connect(device, 500)
        failure(PrinterFailureCode.TIMEOUT) { transport.read(120) }
        assertEquals(listOf(100, 70, 20), native.pollTimeouts)
        transport.close()
    }

    @Test fun `write uses one deadline across partial progress`() {
        val native = FakeSocket().apply { sends.addAll(listOf(1, -1, 1, -1)); ready = 0 }
        val transport = adapter(native, writeTimeout = 150).connect(device, 500)
        failure(PrinterFailureCode.TIMEOUT) { transport.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1), native.sent.toByteArray())
        assertEquals(listOf(100, 50), native.pollTimeouts)
        transport.close()
    }

    @Test fun `read returns received bytes and maps EOF to disconnected`() {
        val native = FakeSocket().apply { receiveResults.addAll(listOf(3, 0)) }
        val transport = adapter(native).connect(device, 500)
        assertArrayEquals(byteArrayOf(1, 2, 3), transport.read(500))
        failure(PrinterFailureCode.DISCONNECTED) { transport.read(500) }
        transport.close()
    }

    @Test fun `poll errors and hangup are fixed disconnected failures`() {
        listOf(WindowsPoll.ERROR, WindowsPoll.HANGUP, WindowsPoll.INVALID).forEach { event ->
            val native = FakeSocket().apply { ready = event }
            val transport = adapter(native).connect(device, 500)
            failure(PrinterFailureCode.DISCONNECTED) { transport.read(500) }
            transport.close()
        }
    }

    @Test fun `zero length write checks open state and close is idempotent`() {
        val native = FakeSocket()
        val transport = adapter(native).connect(device, 500)
        transport.write(byteArrayOf())
        transport.close()
        transport.close()
        failure(PrinterFailureCode.DISCONNECTED) { transport.write(byteArrayOf()) }
        failure(PrinterFailureCode.DISCONNECTED) { transport.read(500) }
        assertEquals(1, native.shutdownCount)
        assertEquals(1, native.closeCount)
        assertEquals(0, native.sent.size())
    }

    @Test fun `close interrupts active poll without closing a leased socket`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = FakeSocket().apply { pollHook = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) } }
        val transport = adapter(native).connect(device, 500)
        val outcome = AtomicReference<Throwable>()
        val reader = Thread { try { transport.read(500) } catch (failure: Throwable) { outcome.set(failure) } }
        reader.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        transport.close()
        assertEquals(1, native.shutdownCount)
        assertEquals(0, native.closeCount)
        release.countDown()
        reader.join(2_000)
        assertFalse(reader.isAlive)
        assertEquals(PrinterFailureCode.DISCONNECTED, (outcome.get() as PrinterFailure).code)
        assertEquals(1, native.closeCount)
        assertEquals(0, native.receiveCalls)
    }

    @Test fun `interruption cancels without clearing the interrupt flag`() {
        val native = FakeSocket()
        val transport = adapter(native).connect(device, 500)
        Thread.currentThread().interrupt()
        try {
            failure(PrinterFailureCode.CANCELLED) { transport.read(500) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(native.pollEvents.isEmpty())
        } finally { Thread.interrupted(); transport.close() }
    }

    @Test fun `late final write still fails its deadline and closes the connection`() {
        val native = FakeSocket().apply { sendAdvanceMillis = 501 }
        val transport = adapter(native).connect(device, 500)
        failure(PrinterFailureCode.TIMEOUT) { transport.write(byteArrayOf(1)) }
        assertEquals(1, native.closeCount)
    }

    @Test fun `late native receive does not return bytes past the deadline`() {
        val native = FakeSocket().apply { receiveAdvanceMillis = 501 }
        val transport = adapter(native).connect(device, 500)
        failure(PrinterFailureCode.TIMEOUT) { transport.read(500) }
        transport.close()
    }

    @Test fun `unexpected native exception is sanitized after transport is returned`() {
        val native = FakeSocket().apply { pollHook = { throw IllegalStateException("native sensitive identifier") } }
        val transport = adapter(native).connect(device, 500)
        failure(PrinterFailureCode.DISCONNECTED) { transport.read(500) }
        assertEquals(1, native.closeCount)
    }

    @Test fun `read deadline includes waiting for another reader`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = FakeSocket().apply { pollHook = { entered.countDown(); release.await(2, TimeUnit.SECONDS) } }
        val transport = WindowsBluetoothAdapter(native).connect(device, 500)
        val first = Thread { try { transport.read(5_000) } catch (_: PrinterFailure) { } }
        val outcome = AtomicReference<Throwable>()
        val second = Thread { try { transport.read(30) } catch (failure: Throwable) { outcome.set(failure) } }
        try {
            first.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            second.start()
            second.join(500)
            assertFalse("reader must expire while the other reader is active", second.isAlive)
            assertEquals(PrinterFailureCode.TIMEOUT, (outcome.get() as PrinterFailure).code)
        } finally {
            transport.close()
            release.countDown()
            first.join(2_000)
            second.join(2_000)
        }
    }

    @Test fun `rejects expired operations and invalid device without native traffic`() {
        val native = FakeSocket()
        failure(PrinterFailureCode.TIMEOUT) { adapter(native).connect(device, 0) }
        failure(PrinterFailureCode.DISCONNECTED) { adapter(native).connect(device.copy(address = -1), 500) }
        assertEquals(0, native.openCount)
        val transport = adapter(native).connect(device, 500)
        failure(PrinterFailureCode.TIMEOUT) { transport.read(0) }
        assertTrue(native.pollEvents.isEmpty())
        transport.close()
    }

    private fun adapter(native: FakeSocket, writeTimeout: Int = 500) =
        WindowsBluetoothAdapter(native, { native.millis * 1_000_000 }, writeTimeout)

    private fun failure(code: PrinterFailureCode, block: () -> Unit) {
        val error = assertThrows(PrinterFailure::class.java, block)
        assertEquals(code, error.code)
        assertEquals(code.name, error.message)
        assertNull(error.cause)
    }

    private class FakeSocket : WindowsSocketBackend {
        var millis = 0L
        var connected = true
        var succeeded = true
        var ready = WindowsPoll.READ or WindowsPoll.WRITE
        var openCount = 0
        var closeCount = 0
        var shutdownCount = 0
        var connectChecks = 0
        var receiveCalls = 0
        var sendAdvanceMillis = 0
        var receiveAdvanceMillis = 0
        var pollAdvanceMillis: Int? = null
        var pollHook: (() -> Unit)? = null
        val sends = ArrayDeque<Int>()
        val receiveResults = ArrayDeque<Int>()
        val sent = ByteArrayOutputStream()
        val pollEvents = mutableListOf<Int>()
        val pollTimeouts = mutableListOf<Int>()
        // Deliberately exceeds 32 bits: truncating SOCKET would break every operation.
        private val handle = 0x1234567800000042L
        override fun openBluetoothSocket(): Long { openCount++; return handle }
        override fun nonBlocking(socket: Long) { assertEquals(handle, socket) }
        override fun beginConnect(socket: Long, address: Long): Boolean { assertEquals(handle, socket); return connected }
        override fun poll(socket: Long, events: Int, timeoutMillis: Int): Int {
            assertEquals(handle, socket)
            pollEvents += events
            pollTimeouts += timeoutMillis
            pollHook?.invoke()
            millis += pollAdvanceMillis?.coerceAtMost(timeoutMillis) ?: if (ready == 0) timeoutMillis else 0
            return ready
        }
        override fun connectSucceeded(socket: Long): Boolean { assertEquals(handle, socket); connectChecks++; return succeeded }
        override fun send(socket: Long, bytes: ByteArray, offset: Int, length: Int): Int {
            assertEquals(handle, socket)
            millis += sendAdvanceMillis
            val count = sends.removeFirstOrNull() ?: length
            if (count > 0) sent.write(bytes, offset, count)
            return count
        }
        override fun receive(socket: Long, bytes: ByteArray): Int {
            assertEquals(handle, socket)
            millis += receiveAdvanceMillis
            receiveCalls++
            val count = receiveResults.removeFirstOrNull() ?: 1
            if (count > 0) repeat(count) { bytes[it] = (it + 1).toByte() }
            return count
        }
        override fun shutdown(socket: Long) { assertEquals(handle, socket); shutdownCount++ }
        override fun close(socket: Long) { assertEquals(handle, socket); closeCount++ }
    }
}
