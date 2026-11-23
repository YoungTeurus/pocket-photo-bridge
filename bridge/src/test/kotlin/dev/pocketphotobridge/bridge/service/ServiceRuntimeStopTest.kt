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
    private class Radio(private val hotspotFails: Boolean = false, private val quickPauses: Int = 0,
        private val holdAttempt: Int = 0) : ServiceDevices {
        override val simulation = false
        val attempted = CountDownLatch(1)
        /** Entered by the first retry pause that really waits, after [quickPauses] that do not. */
        val pausing = CountDownLatch(1)
        private var pauses = 0
        override fun pause(ms: Long) { if (++pauses > quickPauses) { pausing.countDown(); Thread.sleep(ms) } }
        /** Entered by a radio step that holds the radio until the stop's interrupt reaches it. */
        val holding = CountDownLatch(1)
        @Volatile var holdStopSetup = false
        @Volatile var cleanup: String? = null
        private var attempts = 0
        override fun startSetup(credentials: SetupCredentials, wifiInterface: String): InetSocketAddress {
            attempted.countDown()
            // A slow radio cold start: this attempt runs until the stop cancels it.
            if (++attempts == holdAttempt) holdUntilInterrupted()
            if (hotspotFails) throw LinuxRadioFailure(LinuxRadioFailureCode.COMMAND_FAILED)
            return InetSocketAddress("127.0.0.1", 0)
        }
        /** Like the radio manager, steps and cleanup share one lock. */
        @Synchronized override fun close() {
            cleanup = if (Thread.currentThread().isInterrupted) "REFUSED_INTERRUPTED" else "RAN"
        }
        /** Like the bounded command runner: an interrupt cancels the command and stays pending for the caller. */
        private fun holdUntilInterrupted(): Nothing {
            holding.countDown()
            try { Thread.sleep(120_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            throw LinuxRadioFailure(LinuxRadioFailureCode.CANCELLED)
        }
        @Synchronized override fun stopSetup() { if (holdStopSetup) holdUntilInterrupted() }
        override fun cameraPresent(configuration: ServiceConfiguration) = false
        override fun camera(configuration: ServiceConfiguration, trigger: SelectionTrigger): CameraLink = throw UnsupportedOperationException()
        override fun printer(configuration: ServiceConfiguration): PrinterTransport = throw UnsupportedOperationException()
        override fun printers() = emptyList<SetupPrinter>()
        @Synchronized override fun pairPrinter(address: String) = holdUntilInterrupted()
    }

    /** Starts the service, waits for [ready], stops it as a termination signal does and checks what the stop left behind. */
    private fun stopWhen(radio: Radio, onDisk: (List<String>) -> Unit = { }, ready: (ServiceRuntime, ServiceDiagnostics) -> Boolean) {
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
            // The file is the only record that outlives the process; a pending interrupt must not keep the line out of it.
            val file = Files.readAllLines(state.resolve("diagnostics.txt"))
            assertTrue(file.any { it.endsWith(" SERVICE_STOPPED") })
            onDisk(file)
            service.join(60_000)
            assertFalse(service.isAlive)
            assertNull(failure)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun setupServed(diagnostics: ServiceDiagnostics) = diagnostics.events().any { it.endsWith(" SERVICE_SETUP") }

    @Test fun aStopDuringOperationRecordsItAndCleansUpTheRadio() {
        val radio = Radio()
        var started = false
        stopWhen(radio) { runtime, diagnostics ->
            if (!started && setupServed(diagnostics)) {
                started = true
                runtime.backend().saveConfig(SimulatedServiceDevices().configuration)
                runtime.backend().startOperation()
            }
            diagnostics.events().any { it.endsWith(" SERVICE_OPERATION") }
        }
    }

    @Test fun onceTheSwitchIntoOperationHasClosedThePageALateRequestNoLongerReachesTheBridge() {
        val radio = Radio()
        var started = false
        val late = mutableListOf<String?>()
        stopWhen(radio) { runtime, diagnostics ->
            if (!started && setupServed(diagnostics)) {
                started = true
                runtime.backend().saveConfig(SimulatedServiceDevices().configuration)
                runtime.backend().startOperation()
            }
            val operating = diagnostics.events().any { it.endsWith(" SERVICE_OPERATION") }
            if (operating) {
                // A request the closing page let through, arriving after the reopen with its handler's interrupt pending.
                Thread.currentThread().interrupt()
                try {
                    late += (runCatching { runtime.backend().acknowledgeCheckedPrinter() }.exceptionOrNull() as? IllegalStateException)?.message
                    late += (runCatching { runtime.backend().status() }.exceptionOrNull() as? IllegalStateException)?.message
                } finally { Thread.interrupted() }
                assertFalse(diagnostics.events().any { "BRIDGE_STATE_ERROR" in it })
            }
            operating
        }
        assertEquals(listOf("SETUP_REQUIRED", "SETUP_REQUIRED"), late)
    }

    @Test fun aRadioStepCancelledByTheStopLeavesTheInterruptPendingAndTheCleanupStillRuns() {
        val radio = Radio().apply { holdStopSetup = true }
        var started = false
        stopWhen(radio) { runtime, diagnostics ->
            if (!started && setupServed(diagnostics)) {
                started = true
                runtime.backend().saveConfig(SimulatedServiceDevices().configuration)
                runtime.backend().startOperation()
            }
            radio.holding.count == 0L
        }
    }

    @Test fun aStopDuringPrinterPairingStillCleansUpTheRadioWithinTheWait() {
        val radio = Radio()
        var started = false
        stopWhen(radio) { runtime, diagnostics ->
            if (!started && setupServed(diagnostics)) { started = true; runtime.backend().pairPrinter("02:00:00:00:00:01") }
            radio.holding.count == 0L
        }
    }

    @Test fun aStopWhileTheHotspotIsStillBeingRetriedRecordsItAndCleansUpTheRadio() {
        val radio = Radio(hotspotFails = true)
        stopWhen(radio) { _, diagnostics -> radio.attempted.count == 0L && diagnostics.events().any { "SERVICE_SETUP_FAILED" in it } }
    }

    @Test fun aStopDuringTheHotspotRetryPauseStillCountsEveryFailedAttempt() {
        // The third failure is due no line of its own (counts 1, 2, 4 ...), and the stop lands in the pause after it.
        val radio = Radio(hotspotFails = true, quickPauses = 2)
        stopWhen(radio, onDisk = { file ->
            assertEquals("SERVICE_SETUP_FAILED category=COMMAND_FAILED count=3",
                file.map { it.substringAfter(' ') }.last { it.startsWith("SERVICE_SETUP_FAILED") })
        }) { _, _ -> radio.pausing.count == 0L }
    }

    @Test fun aStopDuringAHotspotAttemptIsNotCountedAsAFailedAttempt() {
        // Three failures, the third due no line of its own, then the stop cancels the fourth attempt while it runs.
        val radio = Radio(hotspotFails = true, quickPauses = Int.MAX_VALUE, holdAttempt = 4)
        stopWhen(radio, onDisk = { file ->
            assertEquals("SERVICE_SETUP_FAILED category=COMMAND_FAILED count=3",
                file.map { it.substringAfter(' ') }.last { it.startsWith("SERVICE_SETUP_FAILED") })
        }) { _, _ -> radio.holding.count == 0L }
    }
}
