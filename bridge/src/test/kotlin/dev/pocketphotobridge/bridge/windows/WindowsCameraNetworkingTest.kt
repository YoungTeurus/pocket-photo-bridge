package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.sony.Failure
import dev.pocketphotobridge.sony.ProtocolFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

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

    @Test fun coldWindowsDiscoveryCanFinishBeyondEightSecondsWithoutRelaxingRouteValidation() {
        val process = DiscoveryProcess(configuration().toByteArray(), completesAfterMillis = 10_000)
        val report = mutableListOf<String>()
        assertEquals(listOf(chosen), WindowsCameraNetworking.readDiscovery(process, report::add))
        assertTrue(process.budgetMillis in 10_000..30_000)
        assertTrue(process.destroyed)
        assertTrue(report.any { it.startsWith("WINDOWS_CAMERA_DISCOVERY_ELAPSED_MILLIS count=") })
        assertFalse(report.joinToString().contains(chosen.sourceAddress))
        assertFalse(report.joinToString().contains(chosen.displayName))
    }

    @Test fun hungDiscoveryRemainsBoundedAndIsTerminatedWithoutUsingItsOutput() {
        val process = DiscoveryProcess(configuration().toByteArray(), completesAfterMillis = Long.MAX_VALUE)
        val report = mutableListOf<String>()
        val error = assertThrows(ProtocolFailure::class.java) { WindowsCameraNetworking.readDiscovery(process, report::add) }
        assertEquals(Failure.HTTP_ERROR, error.failure)
        assertTrue(process.budgetMillis in 1..30_000)
        assertTrue(process.destroyed)
        assertTrue(report.contains("WINDOWS_CAMERA_DISCOVERY_TIMEOUT"))
        assertFalse(report.joinToString().contains(chosen.sourceAddress))
    }

    @Test fun discoveryInterruptionCancelsAndTerminatesTheProcess() {
        val process = DiscoveryProcess(configuration().toByteArray(), interrupted = true)
        try {
            val error = assertThrows(ProtocolFailure::class.java) { WindowsCameraNetworking.readDiscovery(process) {} }
            assertEquals(Failure.CANCELLED, error.failure)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(process.destroyed)
        } finally { Thread.interrupted() }
    }

    private class DiscoveryProcess(private val bytes: ByteArray, private val completesAfterMillis: Long = 0,
        private val interrupted: Boolean = false) : Process() {
        var budgetMillis = 0L
        @Volatile var destroyed = false
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            budgetMillis = unit.toMillis(timeout)
            if (interrupted) throw InterruptedException()
            return completesAfterMillis <= budgetMillis
        }
        override fun exitValue() = 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }
}
