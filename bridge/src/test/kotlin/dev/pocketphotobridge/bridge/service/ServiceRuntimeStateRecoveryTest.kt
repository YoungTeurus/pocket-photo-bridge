package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.BridgeFailure
import dev.pocketphotobridge.bridge.BridgeFailureCode
import dev.pocketphotobridge.bridge.CameraLink
import dev.pocketphotobridge.bridge.SelectionTrigger
import dev.pocketphotobridge.bridge.setup.ServiceConfiguration
import dev.pocketphotobridge.bridge.setup.SetupCredentials
import dev.pocketphotobridge.printer.PrinterTransport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServiceRuntimeStateRecoveryTest {
    /** Simulated devices whose camera or printer link first runs a fault, as a storage write on a failing card would surface. */
    private class Faulty(val sim: SimulatedServiceDevices = SimulatedServiceDevices()) : ServiceDevices by sim {
        var onCamera: () -> Unit = { }
        var onPrinter: () -> Unit = { }
        override fun startSetup(credentials: SetupCredentials, wifiInterface: String) = InetSocketAddress("127.0.0.1", 0)
        override fun camera(configuration: ServiceConfiguration, trigger: SelectionTrigger): CameraLink {
            onCamera(); return sim.camera(configuration, trigger)
        }
        override fun printer(configuration: ServiceConfiguration): PrinterTransport { onPrinter(); return sim.printer(configuration) }
    }

    private class Operating(val runtime: ServiceRuntime, val diagnostics: ServiceDiagnostics, val state: Path, val service: Thread) {
        fun lines(stage: String) = diagnostics.events().filter { it.substringAfter(' ').startsWith(stage) }.map { it.substringAfter(' ') }
    }

    /** Runs the service into operation, gives it to [body], then stops it the way the shutdown hook does. */
    private fun operate(devices: Faulty, body: (Operating) -> Unit) {
        val root = Files.createTempDirectory("state-recovery-")
        try {
            val state = root.resolve("state")
            BridgeService.privateRoot(state)
            val open = CountDownLatch(1)
            val output = object : PrintStream(ByteArrayOutputStream(), true) {
                override fun println(x: String?) { super.println(x); if (x?.startsWith("Setup page:") == true) open.countDown() }
            }
            val diagnostics = ServiceDiagnostics(state, output)
            var failure: Throwable? = null
            var runtime: ServiceRuntime? = null
            val service = Thread {
                try { ServiceRuntime(state, devices, diagnostics, output).use { runtime = it; it.run() } }
                catch (thrown: Throwable) { failure = thrown } finally { open.countDown() }
            }
            service.start()
            assertTrue(open.await(60, TimeUnit.SECONDS))
            assertNull(failure)
            runtime!!.backend().saveConfig(devices.sim.configuration)
            runtime!!.backend().startOperation()
            body(Operating(runtime!!, diagnostics, state, service))
            service.interrupt(); service.join(60_000)
            assertFalse(service.isAlive)
            assertNull(failure)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun eventually(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!condition()) { assertTrue("timed out", System.nanoTime() < end); Thread.sleep(100) }
    }

    @Test fun aStorageFaultInOperationIsHealedByOneReopenAndReceivingAndPrintingResume() {
        val devices = Faulty()
        var faults = 1
        devices.onCamera = { if (faults > 0) { faults--; throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR) } }
        devices.sim.simulationAction("printer-on")
        devices.sim.simulationAction("select-photo")
        operate(devices) { service ->
            eventually { devices.sim.safeCounts()["completed"] == 1 }
            assertEquals(listOf("SERVICE_STATE_REOPENED reason=OK count=1"), service.lines("SERVICE_STATE_REOPENED"))
            assertEquals(false, service.runtime.backend().status()["blocked"])
        }
    }

    private fun assertStableStateError(service: Operating) {
        val status = service.runtime.backend().status()
        assertEquals(true, status["blocked"])
        assertEquals("STATE_ERROR", status["blockedReason"])
        assertEquals("STATE_ERROR", status["stage"])
        assertTrue(service.service.isAlive)
    }

    @Test fun aDamagedStateGetsExactlyOneReopenThenAStableStateErrorAndTheServiceKeepsRunning() {
        val devices = Faulty()
        var printerCalls = 0
        devices.sim.simulationAction("printer-on")
        devices.sim.simulationAction("select-photo")
        operate(devices) { service ->
            // The queued photo's files vanish while the printer is reached, so the reopen's own verification fails too.
            devices.onPrinter = {
                printerCalls++
                Files.list(service.state.resolve("service/images")).use { it.toList() }.forEach(Files::delete)
                throw java.io.IOException("card read failed")
            }
            eventually { service.lines("SERVICE_STATE_REOPENED").isNotEmpty() }
            // Several more print and camera steps pass; none reopens again or reaches the printer.
            Thread.sleep(6_000)
            assertEquals(listOf("SERVICE_STATE_REOPENED reason=FAILED category=PERSISTENCE_ERROR count=1"),
                service.lines("SERVICE_STATE_REOPENED"))
            assertEquals(1, printerCalls)
            assertStableStateError(service)
        }
    }

    @Test fun repeatedStateErrorsGetOneReopenEachAndAtMostThreeThenTheStateErrorStays() {
        val devices = Faulty()
        var printerCalls = 0
        devices.onPrinter = { printerCalls++; throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR) }
        devices.sim.simulationAction("printer-on")
        devices.sim.simulationAction("select-photo")
        operate(devices) { service ->
            eventually { printerCalls == 4 }
            Thread.sleep(6_000)
            assertEquals((1..3).map { "SERVICE_STATE_REOPENED reason=OK count=$it" }, service.lines("SERVICE_STATE_REOPENED"))
            // The fourth state error is not reopened, so the failed bridge never reaches the printer again.
            assertEquals(4, printerCalls)
            assertStableStateError(service)
        }
        assertEquals(0, devices.sim.safeCounts()["prints"])
    }

    @Test fun aPhotoPrintedBeforeAReopenIsStillRecognisedAfterItAndNeverPrintedTwice() {
        val devices = Faulty()
        var cameraCalls = 0
        devices.onCamera = { if (++cameraCalls == 2) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR) }
        devices.sim.simulationAction("printer-on")
        devices.sim.simulationAction("select-photo")
        operate(devices) { service ->
            // First check prints the photo; the re-check fails and reopens; the next re-check is offered the same photo.
            eventually { cameraCalls >= 3 && devices.sim.safeCounts()["captured"] == 2 }
            assertEquals(listOf("SERVICE_STATE_REOPENED reason=OK count=1"), service.lines("SERVICE_STATE_REOPENED"))
            assertTrue(service.lines("BRIDGE_").any { "category=DUPLICATE_SUPPRESSED" in it })
        }
        assertEquals(1, devices.sim.safeCounts()["prints"])
    }
}
