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

class ServiceRuntimeStopTest {
    /** A radio whose cleanup notes whether it could run: the bounded command runner refuses an interrupted thread. */
    private class Radio(private val hotspotFails: Boolean) : ServiceDevices {
        override val simulation = false
        val attempted = CountDownLatch(1)
        @Volatile var cleanup: String? = null
        override fun startSetup(credentials: SetupCredentials, wifiInterface: String): InetSocketAddress {
            attempted.countDown()
            if (hotspotFails) throw LinuxRadioFailure(LinuxRadioFailureCode.COMMAND_FAILED)
            return InetSocketAddress("127.0.0.1", 0)
        }
        override fun close() {
            cleanup = if (Thread.currentThread().isInterrupted) "REFUSED_INTERRUPTED" else "RAN"
        }
        override fun stopSetup() { }
        override fun cameraPresent(configuration: ServiceConfiguration) = false
        override fun camera(configuration: ServiceConfiguration, trigger: SelectionTrigger): CameraLink = throw UnsupportedOperationException()
        override fun printer(configuration: ServiceConfiguration): PrinterTransport = throw UnsupportedOperationException()
        override fun printers() = emptyList<SetupPrinter>()
        override fun pairPrinter(address: String) = throw UnsupportedOperationException()
    }

    /** Starts the service, waits for [ready], stops it as a termination signal does and checks what the stop left behind. */
    private fun stopWhen(radio: Radio, ready: (ServiceRuntime, ServiceDiagnostics) -> Boolean) {
        val root = Files.createTempDirectory("service-stop-")
        try {
            val state = root.resolve("state")
            BridgeService.privateRoot(state)
            val output = PrintStream(ByteArrayOutputStream(), true)
            val diagnostics = ServiceDiagnostics(state, output)
            var failure: Throwable? = null
            var runtime: ServiceRuntime? = null
            val service = Thread {
                try { ServiceRuntime(state, radio, diagnostics, output).use { runtime = it; it.run() } }
                catch (thrown: Throwable) { failure = thrown }
            }
            service.start()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (runtime == null || !ready(runtime!!, diagnostics)) { assertTrue(System.nanoTime() < end); Thread.sleep(50) }
            val start = System.nanoTime()
            runtime!!.stop()
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(ServiceRuntime.STOP_WAIT_MS))
            // The stop returns only after the cleanup, which ran with the interrupt flag clear.
            assertEquals("RAN", radio.cleanup)
            assertTrue(diagnostics.events().any { it.endsWith(" SERVICE_STOPPED") })
            service.join(60_000)
            assertFalse(service.isAlive)
            assertNull(failure)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun aStopDuringOperationRecordsItAndCleansUpTheRadio() {
        val radio = Radio(hotspotFails = false)
        var started = false
        stopWhen(radio) { runtime, diagnostics ->
            if (!started && diagnostics.events().any { it.endsWith(" SERVICE_SETUP") }) {
                started = true
                runtime.backend().saveConfig(SimulatedServiceDevices().configuration)
                runtime.backend().startOperation()
            }
            diagnostics.events().any { it.endsWith(" SERVICE_OPERATION") }
        }
    }

    @Test fun aStopWhileTheHotspotIsStillBeingRetriedRecordsItAndCleansUpTheRadio() {
        val radio = Radio(hotspotFails = true)
        stopWhen(radio) { _, diagnostics -> radio.attempted.count == 0L && diagnostics.events().any { "SERVICE_SETUP_FAILED" in it } }
    }
}
