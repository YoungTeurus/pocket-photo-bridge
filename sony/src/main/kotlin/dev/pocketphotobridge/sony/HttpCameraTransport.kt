package dev.pocketphotobridge.sony

import java.io.Closeable
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class HttpCameraTransport(private val open: (URI) -> HttpURLConnection,
                          private val httpStatus: (Int) -> Unit = {}) : CameraTransport, Closeable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val connections = mutableSetOf<HttpURLConnection>()

    /** Nonblocking cancellation marker; call close off the UI thread to drain active handshakes. */
    fun cancel() { closed.set(true) }

    private fun checkActive() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw ProtocolFailure(Failure.CANCELLED)
    }

    override fun execute(request: CameraRequest): CameraResponse {
        checkActive()
        val connection = open(request.uri)
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            val bytes = request.body?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                connection.setRequestProperty("SOAPACTION", "\"${request.action}\"")
                connection.setFixedLengthStreamingMode(bytes.size)
            }
            // Serialize opening/sending headers with close. An unconnected object cannot be
            // disconnected, removed, and then implicitly opened after close has returned.
            val status = synchronized(lock) {
                checkActive()
                connections += connection
                connection.connect()
                checkActive()
                if (bytes != null) connection.outputStream.use { it.write(bytes) }
                checkActive()
                connection.responseCode
            }
            checkActive()
            httpStatus(status)
            val body = if (status == 200) connection.inputStream else ByteArrayInputStream(byteArrayOf())
            val length = connection.contentLengthLong.takeIf { it >= 0 }
            return CameraResponse(status, body, length) { release(connection) }
        } catch (e: Exception) {
            release(connection)
            throw e
        }
    }

    private fun release(connection: HttpURLConnection) {
        synchronized(lock) { connections.remove(connection) }
        connection.disconnect()
    }

    override fun close() {
        cancel()
        val active = synchronized(lock) { connections.toList().also { connections.clear() } }
        active.forEach { it.disconnect() }
    }
}
