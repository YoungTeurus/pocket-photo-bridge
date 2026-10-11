package dev.pocketphotobridge.camera.network

import android.net.Network
import dev.pocketphotobridge.sony.CameraRequest
import dev.pocketphotobridge.sony.CameraResponse
import dev.pocketphotobridge.sony.CameraTransport
import dev.pocketphotobridge.sony.Failure
import dev.pocketphotobridge.sony.ProtocolFailure
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.Proxy

/** All HTTP uses the camera Network, even when Android prefers an internet/VPN route. */
class NetworkCameraTransport(private val network: Network, private val httpStatus: (Int) -> Unit) : CameraTransport, Closeable {
    private val lock = Any()
    private val connections = mutableSetOf<HttpURLConnection>()
    private var closed = false

    override fun execute(request: CameraRequest): CameraResponse {
        val connection = network.openConnection(request.uri.toURL(), Proxy.NO_PROXY) as HttpURLConnection
        synchronized(lock) {
            if (closed || Thread.currentThread().isInterrupted) {
                connection.disconnect()
                throw ProtocolFailure(Failure.CANCELLED)
            }
            connections += connection
        }
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            request.body?.let { body ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                connection.setRequestProperty("SOAPACTION", "\"${request.action}\"")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
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
        val current = synchronized(lock) {
            closed = true
            connections.toList().also { connections.clear() }
        }
        current.forEach { it.disconnect() }
    }
}
