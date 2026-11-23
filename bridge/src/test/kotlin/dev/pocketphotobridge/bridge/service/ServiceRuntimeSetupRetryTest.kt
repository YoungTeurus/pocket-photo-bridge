package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.CameraLink
import dev.pocketphotobridge.bridge.SelectionTrigger
import dev.pocketphotobridge.bridge.linux.LinuxRadioFailure
import dev.pocketphotobridge.bridge.linux.LinuxRadioFailureCode
import dev.pocketphotobridge.bridge.setup.ServiceConfiguration
import dev.pocketphotobridge.bridge.setup.SetupCredentials
import dev.pocketphotobridge.bridge.setup.SetupPrinter
import dev.pocketphotobridge.printer.PrinterTransport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServiceRuntimeSetupRetryTest {
    /** A radio whose hotspot start fails [failures] times, each attempt taking [attemptMs] on its own clock. */
    private class SlowRadio(private val failures: Int, private val attemptMs: Long) : ServiceDevices {
        override val simulation = false
        var clock = 0L
        val starts = mutableListOf<Long>()
        val pauses = mutableListOf<Long>()
        override fun startSetup(credentials: SetupCredentials, wifiInterface: String): InetSocketAddress {
            starts += clock; clock += attemptMs
            if (starts.size <= failures) throw LinuxRadioFailure(LinuxRadioFailureCode.COMMAND_FAILED)
            return InetSocketAddress("127.0.0.1", 0)
        }
        override fun now() = clock
        override fun pause(ms: Long) { pauses += ms; clock += ms }
        override fun stopSetup() { }
        override fun cameraPresent(configuration: ServiceConfiguration) = false
        override fun camera(configuration: ServiceConfiguration, trigger: SelectionTrigger): CameraLink = throw UnsupportedOperationException()
        override fun printer(configuration: ServiceConfiguration): PrinterTransport = throw UnsupportedOperationException()
        override fun printers() = emptyList<SetupPrinter>()
        override fun pairPrinter(address: String) = throw UnsupportedOperationException()
        override fun close() { }
    }

    private class Run(val events: List<String>, val failure: Throwable?)

    /** Runs the service until setup opens, then stops it the way the shutdown hook does. */
    private fun run(radio: SlowRadio): Run {
        val root = Files.createTempDirectory("setup-retry-")
        try {
            val state = root.resolve("state")
            BridgeService.privateRoot(state)
            val finished = CountDownLatch(1)
            val output = object : PrintStream(ByteArrayOutputStream(), true) {
                override fun println(x: String?) { super.println(x); if (x?.startsWith("Setup page:") == true) finished.countDown() }
            }
            val diagnostics = ServiceDiagnostics(state, output, radio::now, null)
            var failure: Throwable? = null
            val service = Thread {
                try { ServiceRuntime(state, radio, diagnostics, output).use { it.run() } }
                catch (thrown: Throwable) { failure = thrown } finally { finished.countDown() }
            }
            service.start()
            assertTrue(finished.await(60, TimeUnit.SECONDS))
            service.interrupt(); service.join(60_000)
            assertFalse(service.isAlive)
            return Run(diagnostics.events(), failure)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun aHotspotThatFailsTwiceIsRetriedFiveSecondsAfterEachAttemptAndSetupOpens() {
        val radio = SlowRadio(failures = 2, attemptMs = 1_000)
        val run = run(radio)
        assertNull(run.failure)
        assertTrue(run.events.any { it.endsWith(" SERVICE_SETUP") })
        assertEquals(listOf(0L, 6_000L, 12_000L), radio.starts)
        assertEquals(listOf(5_000L, 5_000L), radio.pauses)
    }
}
