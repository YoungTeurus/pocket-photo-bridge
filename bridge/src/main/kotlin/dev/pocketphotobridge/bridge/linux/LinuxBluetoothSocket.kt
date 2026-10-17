package dev.pocketphotobridge.bridge.linux

import dev.pocketphotobridge.bridge.PrinterConnector
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import dev.pocketphotobridge.printer.PrinterTransport
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class LinuxPrinterConnector(private val address: String, private val report: (String) -> Unit = {}) : PrinterConnector {
    init { bluetoothAddress(address) }
    override fun connect(): PrinterTransport {
        checkRadioInterrupted()
        try {
            val channel = LinuxSppDiscovery(report = report).channel(address)
            return LinuxBluetoothAdapter(LinuxNativeSocketBackend(LinuxBluetooth.library(), report), report = report)
                .connect(address, channel, 15_000)
        } catch (failure: LinuxRadioFailure) { throw failure }
        catch (failure: PrinterFailure) { throw failure }
        catch (_: Exception) { radioFail(LinuxRadioFailureCode.NATIVE_UNAVAILABLE) }
        catch (_: LinkageError) { radioFail(LinuxRadioFailureCode.NATIVE_UNAVAILABLE) }
    }
}

/** Native -1 means only EAGAIN/EINTR; all other errno values become fixed failures. */
internal interface LinuxSocketBackend {
    fun open(): Int
    fun beginConnect(socket: Int, address: String, channel: Int): Boolean
    fun poll(socket: Int, events: Int, timeoutMillis: Int): Int
    fun connectSucceeded(socket: Int): Boolean
    fun send(socket: Int, bytes: ByteArray, offset: Int, count: Int): Int
    fun receive(socket: Int, bytes: ByteArray): Int
    fun shutdown(socket: Int)
    fun close(socket: Int)
}
internal object LinuxPoll {
    const val READ = 0x001
    const val WRITE = 0x004
    const val ERROR = 0x008
    const val HANGUP = 0x010
    const val INVALID = 0x020
}

internal class LinuxBluetoothAdapter(private val backend: LinuxSocketBackend,
    private val nanoTime: () -> Long = System::nanoTime, private val writeTimeoutMillis: Int = 15_000,
    private val report: (String) -> Unit = {}) {
    fun connect(address: String, channel: Int, timeoutMillis: Int): PrinterTransport {
        interrupted()
        bluetoothAddress(address)
        if (channel !in 1..30) radioFail(LinuxRadioFailureCode.INVALID_INPUT)
        val deadline = Deadline(timeoutMillis.toLong())
        val handle = Handle(backend.open())
        try {
            if (!handle.call { backend.beginConnect(it, address, channel) }) waitFor(handle, LinuxPoll.WRITE, deadline, connecting = true)
            deadline.remaining(); handle.check()
            reportRadio(report, "LINUX_BLUETOOTH_CONNECTED")
            return Transport(handle)
        } catch (failure: Throwable) { handle.close(); throw safe(failure) }
    }

    private inner class Transport(private val handle: Handle) : PrinterTransport {
        private val reads = ReentrantLock()
        private val writes = ReentrantLock()
        override fun write(bytes: ByteArray) {
            handle.check()
            val deadline = Deadline(writeTimeoutMillis.toLong())
            withLock(writes, deadline) {
                try {
                    var offset = 0
                    while (offset < bytes.size) {
                        handle.check(); deadline.remaining()
                        val sent = handle.call { backend.send(it, bytes, offset, bytes.size - offset) }
                        handle.check(); deadline.remaining()
                        when {
                            sent == -1 -> waitFor(handle, LinuxPoll.WRITE, deadline)
                            sent <= 0 || sent > bytes.size - offset -> disconnected()
                            else -> offset += sent
                        }
                    }
                } catch (failure: Throwable) { handle.close(); throw safe(failure) }
            }
        }
        override fun read(timeoutMs: Long): ByteArray {
            handle.check()
            val deadline = Deadline(timeoutMs)
            return withLock(reads, deadline) {
                val bytes = ByteArray(4096)
                try {
                    while (true) {
                        waitFor(handle, LinuxPoll.READ, deadline)
                        val count = handle.call { backend.receive(it, bytes) }
                        handle.check(); deadline.remaining()
                        if (count == -1) continue
                        if (count <= 0 || count > bytes.size) disconnected()
                        return@withLock bytes.copyOf(count)
                    }
                    @Suppress("UNREACHABLE_CODE") byteArrayOf()
                } catch (failure: Throwable) {
                    if (failure !is PrinterFailure || failure.code != PrinterFailureCode.TIMEOUT) handle.close()
                    throw safe(failure)
                }
            }
        }
        override fun close() = handle.close()
    }

    private fun waitFor(handle: Handle, events: Int, deadline: Deadline, connecting: Boolean = false) {
        while (true) {
            handle.check()
            val ready = handle.call { backend.poll(it, events, deadline.pollMillis()) }
            handle.check(); deadline.remaining()
            if (ready == 0) continue
            if (ready and LinuxPoll.INVALID != 0) disconnected()
            if (connecting && ready and (events or LinuxPoll.ERROR or LinuxPoll.HANGUP) != 0 && !handle.call { backend.connectSucceeded(it) }) disconnected()
            if (ready and (LinuxPoll.ERROR or LinuxPoll.HANGUP) != 0) disconnected()
            if (ready and events != 0) return
        }
    }

    private fun <T> withLock(lock: ReentrantLock, deadline: Deadline, block: () -> T): T {
        val acquired = try { lock.tryLock(deadline.remaining(), TimeUnit.NANOSECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw PrinterFailure(PrinterFailureCode.CANCELLED) }
        if (!acquired) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
        try { deadline.remaining(); return block() } finally { lock.unlock() }
    }
    private inner class Deadline(timeoutMillis: Long) {
        private val start = nanoTime()
        private val duration = timeoutMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000) * 1_000_000
        init { if (timeoutMillis <= 0) throw PrinterFailure(PrinterFailureCode.TIMEOUT) }
        fun remaining(): Long {
            interrupted()
            val elapsed = nanoTime() - start
            if (elapsed >= duration) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
            return duration - elapsed
        }
        fun pollMillis(): Int = ((remaining() - 1) / 1_000_000 + 1).coerceAtMost(100).toInt()
    }

    /** shutdown wakes operations; the descriptor is closed only after all leased native calls finish. */
    private inner class Handle(private val fd: Int) {
        private val lock = Any()
        private var closed = false
        private var disposed = false
        private var calls = 0
        fun check() { interrupted(); synchronized(lock) { if (closed) disconnected() } }
        fun <T> call(block: (Int) -> T): T {
            synchronized(lock) { check(); calls++ }
            try { return block(fd) }
            finally { synchronized(lock) { calls--; if (closed && calls == 0) dispose() } }
        }
        fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                try { backend.shutdown(fd) } catch (_: Exception) { } catch (_: LinkageError) { }
                if (calls == 0) dispose()
            }
        }
        private fun dispose() {
            if (!disposed) { disposed = true; try { backend.close(fd) } catch (_: Exception) { } catch (_: LinkageError) { } }
        }
    }
    private companion object {
        fun interrupted() { if (Thread.currentThread().isInterrupted) throw PrinterFailure(PrinterFailureCode.CANCELLED) }
        fun disconnected(): Nothing = throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
        fun safe(failure: Throwable): Throwable = when (failure) {
            is PrinterFailure, is LinuxRadioFailure -> failure
            is Exception, is LinkageError -> PrinterFailure(PrinterFailureCode.DISCONNECTED)
            else -> failure
        }
    }
}
