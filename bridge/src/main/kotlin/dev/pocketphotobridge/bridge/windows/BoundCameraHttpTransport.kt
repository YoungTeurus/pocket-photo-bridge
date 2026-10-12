package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.sony.CameraRequest
import dev.pocketphotobridge.sony.CameraResponse
import dev.pocketphotobridge.sony.CameraTransport
import dev.pocketphotobridge.sony.Failure
import dev.pocketphotobridge.sony.ProtocolFailure
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.Objects
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Direct HTTP over an explicitly bound IPv4 socket; JVM proxy and DNS settings are unused. */
class BoundCameraHttpTransport internal constructor(private val candidate: WindowsCameraCandidate,
    private val verifyRoute: () -> Unit = {}, private val socketFactory: () -> Socket = { Socket() },
    private val connectTimeoutMillis: Int = 5_000, private val readTimeoutMillis: Int = 15_000, private val requestTimeoutMillis: Long = 120_000) : CameraTransport, Closeable {
    private val source = WindowsCameraNetworking.privateIpv4(candidate.sourceAddress) ?: unsafe()
    private val gateway = WindowsCameraNetworking.privateIpv4(candidate.gateway) ?: unsafe()
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val sockets = mutableMapOf<Socket, ScheduledFuture<*>>()

    init { require(connectTimeoutMillis in 1..15_000 && readTimeoutMillis in 1..30_000 && requestTimeoutMillis in 1..120_000) }

    private fun checkActive(deadline: Long = Long.MAX_VALUE) {
        if (closed.get() || Thread.currentThread().isInterrupted) throw ProtocolFailure(Failure.CANCELLED)
        if (System.nanoTime() > deadline) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
    }

    override fun execute(request: CameraRequest): CameraResponse {
        checkActive()
        val uri = request.uri
        if (uri.scheme != "http" || uri.host != candidate.gateway || uri.userInfo != null || uri.fragment != null ||
            uri.port != -1 && uri.port !in 1..65535) unsafe()
        val body = request.body?.toByteArray(Charsets.UTF_8)
        if (body != null && body.size > MAX_REQUEST_BYTES) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
        val action = request.action
        if (action != null && (action.length > 8_192 || action.any { it.code !in 32..126 || it == '"' || it == '\\' })) unsafe()
        if (body != null && action == null) unsafe()
        val port = uri.port.takeIf { it != -1 } ?: 80
        val path = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        val target = path + (uri.rawQuery?.let { "?$it" } ?: "")
        if (!target.startsWith('/') || target.length > 8_192 || target.any { it.code !in 33..126 }) unsafe()
        checkActive()
        verifyRoute()
        checkActive()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis)
        val socket = socketFactory()
        try {
            synchronized(lock) {
                checkActive(deadline)
                // Socket writes have no SO_TIMEOUT. An independent deadline closes even a
                // blocked POST write, rather than relying on the next read/check.
                sockets[socket] = deadlineScheduler.schedule({ release(socket) },
                    maxOf(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            }
            socket.soTimeout = readTimeoutMillis
            socket.tcpNoDelay = true
            socket.bind(InetSocketAddress(InetAddress.getByAddress(source), 0))
            checkActive(deadline)
            socket.connect(InetSocketAddress(InetAddress.getByAddress(gateway), port), connectTimeoutMillis)
            checkActive(deadline)
            if (socket.localAddress.hostAddress != candidate.sourceAddress) unsafe()
            // Verify again after connect and before a SOAP request can change camera state.
            verifyRoute()
            checkActive(deadline)
            val headers = buildString {
                append(if (body == null) "GET" else "POST").append(' ').append(target).append(" HTTP/1.1\r\n")
                append("Host: ").append(candidate.gateway).append(':').append(port).append("\r\n")
                append("Accept-Encoding: identity\r\nConnection: close\r\n")
                if (body != null) {
                    append("Content-Type: text/xml; charset=\"utf-8\"\r\n")
                    append("SOAPACTION: \"").append(action).append("\"\r\n")
                    append("Content-Length: ").append(body.size).append("\r\n")
                }
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            val output = socket.getOutputStream()
            checkActive(deadline)
            output.write(headers)
            if (body != null) { checkActive(deadline); output.write(body) }
            output.flush()
            checkActive(deadline)
            val input = BufferedInputStream(socket.getInputStream(), 8_192)
            val reader = HeaderReader(input) { checkActive(deadline) }
            var head = reader.head()
            var interim = 0
            while (head.status in 100..199) {
                if (head.status == 101 || ++interim > 4) httpError()
                head = reader.head()
            }
            val length = head.length
            if (head.status != 200) {
                release(socket)
                return CameraResponse(head.status, ByteArrayInputStream(byteArrayOf()), length)
            }
            val stream = FramedBody(input, socket, deadline, length, head.chunked)
            return CameraResponse(head.status, stream, length) { release(socket) }
        } catch (e: Exception) {
            release(socket)
            checkActive(deadline)
            if (e is ProtocolFailure) throw e
            throw ProtocolFailure(Failure.HTTP_ERROR)
        }
    }

    private fun release(socket: Socket) {
        synchronized(lock) { sockets.remove(socket) }?.cancel(false)
        try { socket.close() } catch (_: Exception) { }
    }

    /** Closing interrupts connect/read and prevents subsequent requests. */
    override fun close() {
        closed.set(true)
        val active = synchronized(lock) { sockets.toMap().also { sockets.clear() } }
        active.forEach { (socket, timer) -> timer.cancel(false); try { socket.close() } catch (_: Exception) { } }
    }

    private data class Head(val status: Int, val length: Long?, val chunked: Boolean)

    private class HeaderReader(private val input: InputStream, private val check: () -> Unit) {
        private var consumed = 0
        fun line(): String {
            val output = ByteArrayOutputStream()
            while (true) {
                check()
                val value = input.read()
                if (value < 0) httpError()
                if (++consumed > MAX_HEADER_BYTES) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
                if (value == 13) {
                    check()
                    if (input.read() != 10) httpError()
                    if (++consumed > MAX_HEADER_BYTES) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
                    return output.toString(Charsets.US_ASCII)
                }
                if (value == 10 || value < 32 && value != 9 || value > 126) httpError()
                output.write(value)
                if (output.size() > MAX_LINE_BYTES) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
            }
        }
        fun headers(): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            var count = 0
            while (true) {
                val line = line()
                if (line.isEmpty()) return headers
                if (++count > 100 || line[0].isWhitespace()) httpError()
                val colon = line.indexOf(':')
                if (colon <= 0) httpError()
                val name = line.substring(0, colon)
                if (!TOKEN.matches(name)) httpError()
                val lower = name.lowercase(Locale.ROOT)
                val value = line.substring(colon + 1).trim(' ', '\t')
                if (headers.put(lower, value) != null) httpError()
            }
        }
        fun head(): Head {
            val status = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: [\\x20-\\x7e]*)?").matchEntire(line())
                ?.groupValues?.get(1)?.toInt() ?: httpError()
            val headers = headers()
            val encoding = headers["content-encoding"]
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) httpError()
            val transfer = headers["transfer-encoding"]
            if (transfer != null && !transfer.equals("chunked", ignoreCase = true)) httpError()
            val value = headers["content-length"]
            if (value != null && (!Regex("[0-9]+").matches(value) || transfer != null)) httpError()
            val length = value?.toLongOrNull()
            if (value != null && length == null) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
            if (length != null && length > MAX_BODY_BYTES) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
            return Head(status, length, transfer != null)
        }
    }

    private inner class FramedBody(private val input: InputStream, private val socket: Socket,
        private val deadline: Long, private val length: Long?, private val chunked: Boolean) : InputStream() {
        private var total = 0L
        private var chunkRemaining = 0L
        private var chunkCount = 0
        private var chunkNeedsCrlf = false
        private var framingBytes = 0L
        private var done = false
        private var streamClosed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
        }

        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            Objects.checkFromIndexSize(offset, count, bytes.size)
            if (count == 0) return 0
            try {
                checkActive(deadline)
                if (streamClosed) httpError()
                if (done) return -1
                if (chunked && !nextChunk()) return -1
                val remaining = if (chunked) chunkRemaining else length?.minus(total)
                if (remaining == 0L) { finish(); return -1 }
                val allowed = minOf(count.toLong(), remaining ?: count.toLong(), MAX_BODY_BYTES - total + 1).toInt()
                val received = input.read(bytes, offset, allowed)
                checkActive(deadline)
                if (received < 0) {
                    if (remaining != null && remaining > 0) httpError()
                    finish(); return -1
                }
                total += received
                if (total > MAX_BODY_BYTES) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
                if (chunked) { chunkRemaining -= received; if (chunkRemaining == 0L) chunkNeedsCrlf = true }
                return received
            } catch (e: Exception) {
                release(socket)
                checkActive(deadline)
                if (e is ProtocolFailure) throw e
                throw ProtocolFailure(Failure.HTTP_ERROR)
            }
        }

        private fun nextChunk(): Boolean {
            if (chunkRemaining > 0) return true
            if (chunkNeedsCrlf) {
                if (input.read() != 13 || input.read() != 10) httpError()
                chunkNeedsCrlf = false
            }
            if (++chunkCount > 65_536) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
            val reader = HeaderReader(input) { checkActive(deadline) }
            val line = reader.line()
            framingBytes += line.length + 2
            if (framingBytes > 1024 * 1024) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
            val value = line.substringBefore(';')
            if (!Regex("[0-9a-fA-F]{1,16}").matches(value)) httpError()
            val chunk = value.toLongOrNull(16) ?: throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
            if (chunk > MAX_BODY_BYTES - total) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
            if (chunk == 0L) {
                val trailers = reader.headers()
                if (trailers.keys.any { it in setOf("content-length", "transfer-encoding", "content-encoding", "host") }) httpError()
                finish(); return false
            }
            chunkRemaining = chunk
            return true
        }

        private fun finish() { done = true; release(socket) }
        override fun close() { streamClosed = true; release(socket) }
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        const val MAX_HEADER_BYTES = 32 * 1024
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 64L * 1024 * 1024
        val deadlineScheduler = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "camera-http-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        fun unsafe(): Nothing = throw ProtocolFailure(Failure.UNSAFE_URL)
        fun httpError(): Nothing = throw ProtocolFailure(Failure.HTTP_ERROR)
    }
}
