package dev.pocketphotobridge.sony

import java.net.URI

class CameraEndpoint(val host: String, val port: Int = 64321) {
    init {
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 } ||
            !(octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)) || port !in 1..65535) {
            throw ProtocolFailure(Failure.UNSAFE_URL)
        }
    }
    val base: URI get() = URI("http://$host:$port/")
    fun resolve(value: String): URI {
        val uri = try { base.resolve(value.trim()) } catch (_: Exception) { throw ProtocolFailure(Failure.UNSAFE_URL) }
        if (uri.scheme != "http" || uri.host != host || uri.userInfo != null || uri.fragment != null ||
            (uri.port != -1 && uri.port !in 1..65535)) throw ProtocolFailure(Failure.UNSAFE_URL)
        return uri
    }
}
