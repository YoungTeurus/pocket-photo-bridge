package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.printer.PrinterTransport
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Native return codes stay behind this boundary. -1 means only WSAEWOULDBLOCK. */
internal interface WindowsSocketBackend {
    fun openBluetoothSocket(): Long
    fun nonBlocking(socket: Long)
    fun beginConnect(socket: Long, address: Long): Boolean
    fun poll(socket: Long, events: Int, timeoutMillis: Int): Int
    fun connectSucceeded(socket: Long): Boolean
    fun send(socket: Long, bytes: ByteArray, offset: Int, length: Int): Int
    fun receive(socket: Long, bytes: ByteArray): Int
    fun shutdown(socket: Long)
    fun close(socket: Long)
}

internal object WindowsPoll {
    // WinSock2.h: these differ from POSIX poll constants.
    const val READ = 0x0100
    const val WRITE = 0x0010
    const val ERROR = 0x0001
    const val HANGUP = 0x0002
    const val INVALID = 0x0004
}

internal class WindowsBluetoothAdapter(
    private val backend: WindowsSocketBackend,
    private val nanoTime: () -> Long = System::nanoTime,
    private val writeTimeoutMillis: Int = 15_000
) {
    fun connect(device: WindowsPairedDevice, timeoutMillis: Int): PrinterTransport {
        checkInterrupted()
        val deadline = Deadline(timeoutMillis.toLong(), nanoTime)
        if (device.address !in 1..0xffffffffffffL) disconnected()
        val handle = SocketHandle(backend.openBluetoothSocket(), backend)
        try {
            handle.call { backend.nonBlocking(it) }
            if (!handle.call { backend.beginConnect(it, device.address) }) {
                waitFor(handle, WindowsPoll.WRITE, deadline, connecting = true)
            }
            deadline.check()
            handle.check()
            return Transport(handle)
        } catch (failure: Throwable) {
            handle.close()
            throw failure
        }
    }

    private inner class Transport(private val handle: SocketHandle) : PrinterTransport {
        // Separate read/write locks allow full duplex, but never interleave two frames.
        private val writeLock = ReentrantLock()
        private val readLock = ReentrantLock()

        override fun write(bytes: ByteArray) {
            handle.check()
            val deadline = Deadline(writeTimeoutMillis.toLong(), nanoTime)
            withDeadlineLock(writeLock, deadline) {
                var offset = 0
                try {
                    handle.check()
                    while (offset < bytes.size) {
                        handle.check()
                        deadline.check()
                        val count = handle.call { backend.send(it, bytes, offset, bytes.size - offset) }
                        handle.check()
                        deadline.check()
                        when {
                            count == -1 -> waitFor(handle, WindowsPoll.WRITE, deadline)
                            count <= 0 || count > bytes.size - offset -> disconnected()
                            else -> offset += count
                        }
                    }
                } catch (failure: Throwable) {
                    // A timed-out partial write must never be resumed as another frame.
                    handle.close()
                    throw safeFailure(failure)
                }
            }
        }

        override fun read(timeoutMs: Long): ByteArray {
            handle.check()
            val deadline = Deadline(timeoutMs, nanoTime)
            return withDeadlineLock(readLock, deadline) {
                val bytes = ByteArray(4096)
                try {
                    while (true) {
                        waitFor(handle, WindowsPoll.READ, deadline)
                        val count = handle.call { backend.receive(it, bytes) }
                        handle.check()
                        deadline.check()
                        if (count == -1) continue
                        if (count <= 0 || count > bytes.size) disconnected()
                        return@withDeadlineLock bytes.copyOf(count)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    byteArrayOf()
                } catch (failure: Throwable) {
                    // Reads are retryable after a deadline; EOF, cancellation and socket errors are not.
                    if (failure !is PrinterFailure || failure.code != PrinterFailureCode.TIMEOUT) handle.close()
                    throw safeFailure(failure)
                }
            }
        }

        override fun close() = handle.close()
    }

    private fun <T> withDeadlineLock(lock: ReentrantLock, deadline: Deadline, block: () -> T): T {
        val acquired = try { lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PrinterFailure(PrinterFailureCode.CANCELLED)
        }
        if (!acquired) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
        try {
            deadline.check()
            return block()
        } finally { lock.unlock() }
    }

    private fun waitFor(handle: SocketHandle, events: Int, deadline: Deadline, connecting: Boolean = false) {
        while (true) {
            handle.check()
            val ready = handle.call { backend.poll(it, events, deadline.pollMillis()) }
            handle.check()
            deadline.check()
            if (ready == 0) continue
            if (ready and WindowsPoll.INVALID != 0) disconnected()
            if (connecting && ready and (events or WindowsPoll.ERROR or WindowsPoll.HANGUP) != 0) {
                // Writable readiness alone does not establish a successful connection.
                if (!handle.call { backend.connectSucceeded(it) }) disconnected()
            }
            if (ready and (WindowsPoll.ERROR or WindowsPoll.HANGUP) != 0) disconnected()
            if (ready and events != 0) return
        }
    }

    private class Deadline(timeoutMillis: Long, private val now: () -> Long) {
        private val start = now()
        private val duration = timeoutMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000) * 1_000_000
        init { if (timeoutMillis <= 0) throw PrinterFailure(PrinterFailureCode.TIMEOUT) }
        fun check() { remainingNanos() }
        fun pollMillis(): Int = ((remainingNanos() - 1) / 1_000_000 + 1).coerceAtMost(100).toInt()
        fun remainingNanos(): Long {
            val elapsed = now() - start // Monotonic subtraction also handles nanoTime wraparound.
            if (elapsed >= duration) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
            return duration - elapsed
        }
    }

    /** Leasing prevents closesocket from recycling a handle under an in-flight native call. */
    private class SocketHandle(private val socket: Long, private val backend: WindowsSocketBackend) {
        private val lock = Any()
        private var closed = false
        private var disposed = false
        private var calls = 0

        fun check() {
            checkInterrupted()
            synchronized(lock) { if (closed) disconnected() }
        }

        fun <T> call(block: (Long) -> T): T {
            checkInterrupted()
            synchronized(lock) {
                if (closed) disconnected()
                calls++
            }
            try { return block(socket) }
            finally {
                synchronized(lock) {
                    calls--
                    if (closed && calls == 0) dispose()
                }
            }
        }

        fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                // shutdown wakes reads/poll without releasing the handle prematurely.
                try { backend.shutdown(socket) } catch (_: Exception) { } catch (_: LinkageError) { }
                if (calls == 0) dispose()
            }
        }

        private fun dispose() {
            if (!disposed) {
                disposed = true
                try { backend.close(socket) } catch (_: Exception) { } catch (_: LinkageError) { }
            }
        }
    }

    companion object {
        private fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) throw PrinterFailure(PrinterFailureCode.CANCELLED)
        }
        private fun disconnected(): Nothing = throw PrinterFailure(PrinterFailureCode.DISCONNECTED)
        private fun safeFailure(failure: Throwable): Throwable = when (failure) {
            is PrinterFailure, is WindowsBluetoothFailure -> failure
            is Exception, is LinkageError -> PrinterFailure(PrinterFailureCode.DISCONNECTED)
            else -> failure
        }
    }
}
