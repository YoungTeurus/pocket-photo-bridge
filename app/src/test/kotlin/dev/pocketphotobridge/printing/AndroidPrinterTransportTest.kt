package dev.pocketphotobridge.printing

import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidPrinterTransportTest {
    @Test fun connectTimeoutClosesBlockedConnection() {
        val connection = BlockingConnection(blockConnect = true)
        val transport = AndroidPrinterTransport { connection }
        val start = System.nanoTime()
        val failure = assertThrows(PrinterFailure::class.java) { transport.connect(50) }
        assertEquals(PrinterFailureCode.TIMEOUT, failure.code)
        assertTrue(connection.closed)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
    }

    @Test fun readTimeoutClosesPumpAndReportsFixedCode() {
        val connection = BlockingConnection()
        val transport = AndroidPrinterTransport { connection }
        transport.connect(1000)
        val failure = assertThrows(PrinterFailure::class.java) { transport.read(50) }
        assertEquals(PrinterFailureCode.TIMEOUT, failure.code)
        assertEquals("TIMEOUT", failure.message)
        assertTrue(connection.closed)
    }

    @Test fun cancellationUnblocksPendingReadAndPreventsReconnect() {
        val connection = BlockingConnection()
        val transport = AndroidPrinterTransport { connection }
        transport.connect(1000)
        val finished = CountDownLatch(1)
        var result: PrinterFailureCode? = null
        val worker = Thread {
            try { transport.read(10_000) } catch (e: PrinterFailure) { result = e.code }
            finally { finished.countDown() }
        }
        worker.start()
        transport.cancel()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(PrinterFailureCode.CANCELLED, result)
        assertEquals(PrinterFailureCode.CANCELLED,
            assertThrows(PrinterFailure::class.java) { transport.connect(1000) }.code)
        transport.close()
        assertTrue(connection.closed)
    }

    @Test fun writeTimeoutClosesBlockedOutput() {
        val connection = BlockingConnection(blockWrite = true)
        val transport = AndroidPrinterTransport({ connection }, writeTimeoutMs = 50)
        transport.connect(1000)
        val failure = assertThrows(PrinterFailure::class.java) { transport.write(byteArrayOf(1)) }
        assertEquals(PrinterFailureCode.TIMEOUT, failure.code)
        assertTrue(connection.closed)
    }

    @Test fun closeBeforeConnectNeverCreatesSocket() {
        var created = false
        val transport = AndroidPrinterTransport { created = true; BlockingConnection() }
        transport.close()
        assertEquals(PrinterFailureCode.DISCONNECTED,
            assertThrows(PrinterFailure::class.java) { transport.connect() }.code)
        assertFalse(created)
    }

    @Test fun socketExceptionsAreSanitized() {
        val transport = AndroidPrinterTransport { throw IOException("device MAC and raw private payload") }
        val failure = assertThrows(PrinterFailure::class.java) { transport.connect(1000) }
        assertEquals(PrinterFailureCode.DISCONNECTED, failure.code)
        assertEquals("DISCONNECTED", failure.message)
        assertNull(failure.cause)
    }

    private class BlockingConnection(
        private val blockConnect: Boolean = false,
        private val blockWrite: Boolean = false
    ) : RfcommConnection {
        private val closedSignal = CountDownLatch(1)
        @Volatile var closed = false
        override val input = object : InputStream() {
            override fun read(): Int { closedSignal.await(); throw IOException("closed") }
        }
        override val output = object : OutputStream() {
            override fun write(value: Int) {
                if (blockWrite) { closedSignal.await(); throw IOException("closed") }
            }
        }
        override fun connect() {
            if (blockConnect) { closedSignal.await(); throw IOException("closed") }
        }
        override fun close() { closed = true; closedSignal.countDown() }
    }
}
