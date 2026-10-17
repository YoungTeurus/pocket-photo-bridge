package dev.pocketphotobridge.bridge.linux

import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LinuxBluetoothSocketTest {
    @Test fun `partial sends and would block send every byte exactly once`() {
        val fake = FakeSocket().apply { sends.addAll(listOf(2, -1, 1, 2)) }
        val connection = adapter(fake).connect("AA:BB:CC:DD:EE:FF", 7, 500)
        connection.write(byteArrayOf(10, 20, 30, 40, 50))
        assertArrayEquals(byteArrayOf(10, 20, 30, 40, 50), fake.sent.toByteArray())
        assertEquals(listOf(LinuxPoll.WRITE), fake.events)
        connection.close()
    }

    @Test fun `in progress connect verifies socket error and uses discovered channel`() {
        val fake = FakeSocket().apply { connected = false }
        adapter(fake).connect("AA:BB:CC:DD:EE:FF", 19, 500).close()
        assertEquals(19, fake.channel)
        assertEquals(1, fake.errorChecks)
        val failed = FakeSocket().apply { connected = false; succeeded = false }
        failure(PrinterFailureCode.DISCONNECTED) { adapter(failed).connect("AA:BB:CC:DD:EE:FF", 19, 500) }
        assertEquals(1, failed.closed)
    }

    @Test fun `connect and writes have one deadline and release failed sockets`() {
        val fake = FakeSocket().apply { connected = false; ready = 0 }
        failure(PrinterFailureCode.TIMEOUT) { adapter(fake).connect("AA:BB:CC:DD:EE:FF", 7, 250) }
        assertEquals(listOf(100, 100, 50), fake.timeouts)
        assertEquals(1, fake.closed)
        val partial = FakeSocket().apply { sends.addAll(listOf(1, -1)); ready = 0 }
        val connection = adapter(partial, 150).connect("AA:BB:CC:DD:EE:FF", 7, 500)
        failure(PrinterFailureCode.TIMEOUT) { connection.write(byteArrayOf(1, 2)) }
        assertEquals(1, partial.closed)
    }

    @Test fun `read timeout can be retried but EOF closes transport`() {
        val fake = FakeSocket().apply { ready = 0 }
        val connection = adapter(fake).connect("AA:BB:CC:DD:EE:FF", 7, 500)
        failure(PrinterFailureCode.TIMEOUT) { connection.read(120) }
        assertEquals(0, fake.closed)
        fake.ready = LinuxPoll.READ
        fake.receives.addAll(listOf(3, 0))
        assertArrayEquals(byteArrayOf(1, 2, 3), connection.read(500))
        failure(PrinterFailureCode.DISCONNECTED) { connection.read(500) }
        assertEquals(1, fake.closed)
    }

    @Test fun `close interrupts active poll without recycling descriptor`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeSocket().apply { pollHook = { entered.countDown(); release.await(2, TimeUnit.SECONDS) } }
        val connection = LinuxBluetoothAdapter(fake).connect("AA:BB:CC:DD:EE:FF", 7, 500)
        val outcome = AtomicReference<Throwable>()
        val thread = Thread { try { connection.read(500) } catch (failure: Throwable) { outcome.set(failure) } }
        thread.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        connection.close()
        assertEquals(1, fake.shutdown)
        assertEquals(0, fake.closed)
        release.countDown(); thread.join(2_000)
        assertFalse(thread.isAlive)
        assertEquals(PrinterFailureCode.DISCONNECTED, (outcome.get() as PrinterFailure).code)
        assertEquals(1, fake.closed)
    }

    @Test fun `interrupt is preserved and reconnect uses a new socket`() {
        val fake = FakeSocket()
        val connection = adapter(fake).connect("AA:BB:CC:DD:EE:FF", 7, 500)
        Thread.currentThread().interrupt()
        try { failure(PrinterFailureCode.CANCELLED) { connection.read(500) }; assertTrue(Thread.currentThread().isInterrupted) }
        finally { Thread.interrupted(); connection.close() }
        adapter(fake).connect("AA:BB:CC:DD:EE:FF", 8, 500).close()
        assertEquals(2, fake.opened)
        assertEquals(2, fake.closed)
        assertEquals(8, fake.channel)
    }

    @Test fun `poll error hangup invalid descriptor and late writes fail closed`() {
        listOf(LinuxPoll.ERROR, LinuxPoll.HANGUP, LinuxPoll.INVALID).forEach { event ->
            val fake = FakeSocket().apply { ready = event }
            val connection = adapter(fake).connect("AA:BB:CC:DD:EE:FF", 7, 500)
            failure(PrinterFailureCode.DISCONNECTED) { connection.read(500) }
            assertEquals(1, fake.closed)
        }
        val fake = FakeSocket().apply { sendAdvance = 501 }
        val connection = adapter(fake).connect("AA:BB:CC:DD:EE:FF", 7, 500)
        failure(PrinterFailureCode.TIMEOUT) { connection.write(byteArrayOf(1)) }
        assertEquals(1, fake.closed)
    }

    private fun adapter(fake: FakeSocket, writeTimeout: Int = 500) = LinuxBluetoothAdapter(fake, { fake.millis * 1_000_000 }, writeTimeout)
    private fun failure(code: PrinterFailureCode, action: () -> Unit) {
        val error = assertThrows(PrinterFailure::class.java, action)
        assertEquals(code, error.code); assertEquals(code.name, error.message); assertNull(error.cause)
    }

    private class FakeSocket : LinuxSocketBackend {
        var connected = true
        var succeeded = true
        var channel = 0
        var millis = 0L
        var ready = LinuxPoll.READ or LinuxPoll.WRITE
        var opened = 0
        var closed = 0
        var shutdown = 0
        var errorChecks = 0
        var sendAdvance = 0L
        var pollHook: (() -> Unit)? = null
        val sends = ArrayDeque<Int>()
        val receives = ArrayDeque<Int>()
        val sent = ByteArrayOutputStream()
        val events = mutableListOf<Int>()
        val timeouts = mutableListOf<Int>()
        override fun open(): Int { opened++; return 42 + opened }
        override fun beginConnect(socket: Int, address: String, channel: Int): Boolean { this.channel = channel; return connected }
        override fun poll(socket: Int, events: Int, timeoutMillis: Int): Int { this.events += events; timeouts += timeoutMillis; pollHook?.invoke(); if (ready == 0) millis += timeoutMillis; return ready }
        override fun connectSucceeded(socket: Int): Boolean { errorChecks++; return succeeded }
        override fun send(socket: Int, bytes: ByteArray, offset: Int, count: Int): Int { millis += sendAdvance; val sentCount = sends.removeFirstOrNull() ?: count; if (sentCount > 0) sent.write(bytes, offset, sentCount); return sentCount }
        override fun receive(socket: Int, bytes: ByteArray): Int { val count = receives.removeFirstOrNull() ?: 1; if (count > 0) repeat(count) { bytes[it] = (it + 1).toByte() }; return count }
        override fun shutdown(socket: Int) { shutdown++ }
        override fun close(socket: Int) { closed++ }
    }
}
