package dev.pocketphotobridge.printing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import dev.pocketphotobridge.printer.PrinterTransport
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal interface RfcommConnection : Closeable {
    val input: InputStream
    val output: OutputStream
    fun connect()
}

/** One secure SPP connection. A closed/cancelled transport is never reusable. */
class AndroidPrinterTransport internal constructor(
    private val writeTimeoutMs: Long = 15_000,
    private val createConnection: () -> RfcommConnection
) : PrinterTransport {
    constructor(device: BluetoothDevice) : this(createConnection = { BluetoothRfcommConnection(device) })
    internal constructor(createConnection: () -> RfcommConnection, writeTimeoutMs: Long) : this(writeTimeoutMs, createConnection)

    private val lock = Any()
    private var connection: RfcommConnection? = null
    private var started = false
    @Volatile private var connected = false
    @Volatile private var failure: PrinterFailureCode? = null
    private val received = ArrayBlockingQueue<ReadResult>(16)
    private val pump = Executors.newSingleThreadExecutor { task -> Thread(task, "printer-spp-read").apply { isDaemon = true } }

    fun connect(timeoutMs: Long = 15_000) {
        checkOpen()
        if (timeoutMs !in 1..60_000) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
        synchronized(lock) {
            checkOpen()
            if (started) throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
            started = true
        }
        try {
            val created = createConnection()
            synchronized(lock) {
                if (failure != null) { runCatching { created.close() }; checkOpen() }
                connection = created
            }
            bounded(timeoutMs) { created.connect() }
            synchronized(lock) {
                checkOpen()
                connected = true
                pump.execute { receive(created) }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            stop(PrinterFailureCode.CANCELLED)
            throw PrinterFailure(PrinterFailureCode.CANCELLED)
        } catch (e: Exception) {
            stop(failure ?: PrinterFailureCode.DISCONNECTED)
            throw PrinterFailure(failure ?: PrinterFailureCode.DISCONNECTED)
        }
    }

    override fun write(bytes: ByteArray) {
        checkConnected()
        if (bytes.size > 8192) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
        val current = synchronized(lock) { checkConnected(); requireNotNull(connection) }
        try {
            bounded(writeTimeoutMs) { current.output.write(bytes); current.output.flush() }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            stop(PrinterFailureCode.CANCELLED)
            throw PrinterFailure(PrinterFailureCode.CANCELLED)
        } catch (e: Exception) {
            stop(failure ?: PrinterFailureCode.DISCONNECTED)
            throw PrinterFailure(failure ?: PrinterFailureCode.DISCONNECTED)
        }
    }

    override fun read(timeoutMs: Long): ByteArray {
        checkConnected()
        if (timeoutMs !in 1..60_000) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
        val result = try { received.poll(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            stop(PrinterFailureCode.CANCELLED)
            throw PrinterFailure(PrinterFailureCode.CANCELLED)
        }
        if (result == null) {
            stop(PrinterFailureCode.TIMEOUT)
            throw PrinterFailure(failure ?: PrinterFailureCode.TIMEOUT)
        }
        checkOpen()
        return when (result) {
            is ReadResult.Data -> result.bytes
            is ReadResult.Failed -> throw PrinterFailure(result.code)
        }
    }

    fun cancel() { stop(PrinterFailureCode.CANCELLED) }
    override fun close() { stop(PrinterFailureCode.DISCONNECTED) }

    private fun receive(current: RfcommConnection) {
        try {
            val input = current.input
            val buffer = ByteArray(4096)
            while (failure == null) {
                val count = input.read(buffer)
                if (count < 0) { stop(PrinterFailureCode.DISCONNECTED); return }
                if (count == 0) continue
                if (!received.offer(ReadResult.Data(buffer.copyOf(count)))) {
                    stop(PrinterFailureCode.LIMIT_EXCEEDED)
                    return
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            stop(PrinterFailureCode.CANCELLED)
        } catch (e: Exception) { stop(PrinterFailureCode.DISCONNECTED) }
    }

    private fun bounded(timeoutMs: Long, operation: () -> Unit) {
        checkOpen()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val watchdog: ScheduledFuture<*> = watchdogs.schedule({ stop(PrinterFailureCode.TIMEOUT) }, timeoutMs, TimeUnit.MILLISECONDS)
        try { operation(); checkOpen() } finally { watchdog.cancel(false) }
    }

    private fun checkConnected() { checkOpen(); if (!connected) throw PrinterFailure(PrinterFailureCode.DISCONNECTED) }
    private fun checkOpen() { failure?.let { throw PrinterFailure(it) } }

    private fun stop(code: PrinterFailureCode) {
        val toClose = synchronized(lock) {
            if (failure != null) return
            failure = code
            connected = false
            val current = connection
            connection = null
            received.clear()
            received.offer(ReadResult.Failed(code))
            current
        }
        runCatching { toClose?.close() }
        pump.shutdownNow()
    }

    private sealed interface ReadResult {
        data class Data(val bytes: ByteArray) : ReadResult
        data class Failed(val code: PrinterFailureCode) : ReadResult
    }

    companion object {
        private val watchdogs = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "printer-spp-timeout").apply { isDaemon = true }
        }
        private val SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }

    @SuppressLint("MissingPermission")
    private class BluetoothRfcommConnection(device: BluetoothDevice) : RfcommConnection {
        private val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP)
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream
        override fun connect() = socket.connect()
        override fun close() = socket.close()
    }
}
