package dev.pocketphotobridge.camera.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import dev.pocketphotobridge.sony.CameraEndpoint
import java.net.Inet4Address

data class AttachedWifi(val network: Network, val endpoint: CameraEndpoint, val gatewayFromWifi: Boolean)

/** Android Settings owns credentials. We do not read SSID, BSSID, QR data or location. */
class WifiConnector(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Suppress("DEPRECATION")
    fun attach(onLost: () -> Unit): AttachedWifi {
        release()
        val networks = manager.allNetworks.filter {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        check(networks.isNotEmpty()) { "NO_WIFI" }
        check(networks.size == 1) { "MULTIPLE_WIFI_NETWORKS" }
        val network = networks.single()
        val gateway = manager.getLinkProperties(network)?.routes?.firstOrNull {
            it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress
        }?.gateway?.hostAddress
        val attached = AttachedWifi(network, CameraEndpoint(gateway ?: "192.168.122.1"), gateway != null)
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) { if (callback === this && lost == network) onLost() }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, listener, Handler(Looper.getMainLooper()))
        callback = listener
        return attached
    }

    fun release() {
        callback?.let { manager.unregisterNetworkCallback(it) }
        callback = null
    }
}
