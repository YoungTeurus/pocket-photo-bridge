package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.sony.Failure
import dev.pocketphotobridge.sony.ProtocolFailure
import org.junit.Assert.*
import org.junit.Test

class WindowsCameraNetworkingTest {
    private val chosen = WindowsCameraCandidate(8, "Sony Wi-Fi", "192.168.122.20", "192.168.122.1")
    private fun configuration(source: String = chosen.sourceAddress, gateway: String = chosen.gateway,
        routeIndex: Int = 8, nextHop: String = "0.0.0.0", prefix: Int = 24) = """
        [{"InterfaceIndex":8,"DisplayName":"Sony Wi-Fi","SourceAddress":"$source",
          "Gateway":"$gateway","PrefixLength":$prefix,"RouteInterfaceIndex":$routeIndex,
          "RouteNextHop":"$nextHop","RouteSourceAddress":"$source"}]
    """.trimIndent()

    @Test fun discoversCanonicalPrivateSourceAndOnLinkGateway() {
        assertEquals(listOf(chosen), WindowsCameraNetworking.parseCandidates(configuration()))
        assertEquals(emptyList<WindowsCameraCandidate>(), WindowsCameraNetworking.parseCandidates("[]"))
    }

    @Test fun excludesNoncanonicalPublicAndWrongLinkConfigurations() {
        val unsafe = listOf(
            configuration(source = "192.168.122.020"), configuration(gateway = "192.168.122.001"),
            configuration(source = "8.8.8.8"), configuration(gateway = "8.8.8.8"),
            configuration(gateway = "192.168.123.1"), configuration(gateway = chosen.sourceAddress),
            configuration(routeIndex = 12), configuration(nextHop = "192.168.122.254"),
            configuration(prefix = 0), configuration(source = "192.168.122.255"),
            configuration(gateway = "192.168.122.0"), configuration(source = "192.168.122.0"))
        unsafe.forEach { assertEquals(it, emptyList<WindowsCameraCandidate>(), WindowsCameraNetworking.parseCandidates(it)) }
    }

    @Test fun malformedOrOversizedDiscoveryFailsClosed() {
        listOf("not json", "{}", "[null]", " ".repeat(65_537),
            configuration().replace("\"InterfaceIndex\":8", "\"InterfaceIndex\":8.5"),
            configuration().replace("\"RouteSourceAddress\":\"192.168.122.20\"", "\"RouteSourceAddress\":\"192.168.122.21\""))
            .forEach { assertEquals(emptyList<WindowsCameraCandidate>(), WindowsCameraNetworking.parseCandidates(it)) }
    }

    @Test fun connectingRequiresFreshCandidateAndAssignedSource() {
        var opens = 0
        fun rejected(current: List<WindowsCameraCandidate>, assigned: Boolean) {
            try {
                WindowsCameraNetworking.connectVerified(chosen, { current }, { _, _ -> assigned }, { opens++; java.net.Socket() })
                fail("Unsafe source must be rejected before socket creation")
            } catch (e: ProtocolFailure) { assertEquals(Failure.UNSAFE_URL, e.failure) }
        }
        rejected(emptyList(), true)
        rejected(listOf(chosen), false)
        assertEquals(0, opens)
        WindowsCameraNetworking.connectVerified(chosen, { listOf(chosen) }, { _, _ -> true }).use {
            assertEquals(chosen.gateway, it.endpoint.host)
            assertEquals(64321, it.endpoint.port)
        }
    }
}
