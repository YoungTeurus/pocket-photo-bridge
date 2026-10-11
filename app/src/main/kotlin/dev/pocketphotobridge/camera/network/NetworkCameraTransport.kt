package dev.pocketphotobridge.camera.network

import android.net.Network
import dev.pocketphotobridge.sony.CameraRequest
import dev.pocketphotobridge.sony.CameraResponse
import dev.pocketphotobridge.sony.CameraTransport
import dev.pocketphotobridge.sony.HttpCameraTransport
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.Proxy

/** All HTTP uses the camera Network, even when Android prefers an internet/VPN route. */
class NetworkCameraTransport(network: Network, httpStatus: (Int) -> Unit) : CameraTransport, Closeable {
    private val http = HttpCameraTransport({ network.openConnection(it.toURL(), Proxy.NO_PROXY) as HttpURLConnection }, httpStatus)
    override fun execute(request: CameraRequest): CameraResponse = http.execute(request)
    fun cancel() = http.cancel()
    override fun close() = http.close()
}
