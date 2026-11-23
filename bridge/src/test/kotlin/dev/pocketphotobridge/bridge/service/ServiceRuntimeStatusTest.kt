package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.PrinterConnector
import dev.pocketphotobridge.bridge.SelectionTrigger
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.bridge.simulation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

class ServiceRuntimeStatusTest {
    @Test fun theSetupStatusSaysWhyTheBridgeIsBlockedAndWhatThisBootDiscarded() {
        val root = Files.createTempDirectory("runtime-status-")
        try {
            val state = root.resolve("state")
            BridgeService.privateRoot(state)
            val photo = javaClass.getResourceAsStream("/simulation/sony-selected.jpg")!!.use { it.readBytes() }
            fun bridge(printer: PrinterConnector) = SimulationClock().let { clock ->
                AutonomousBridge(state.resolve("service"), SimulatedSony(photo), printer, JvmPrintImagePreparer(), { SIMULATION_TOKEN },
                    now = clock::now, pause = clock::pause)
            }
            fun status(): Map<String, Any> {
                val output = PrintStream(ByteArrayOutputStream())
                return ServiceRuntime(state, SimulatedServiceDevices(), ServiceDiagnostics(state, output), output,
                    "00000000-0000-4000-8000-000000000002").use { it.backend().status() }
            }
            bridge(SimulatedPrinter(PrinterFault.OFFLINE)).use {
                it.beginBoot("00000000-0000-4000-8000-000000000001", false)
                it.capture(SelectionTrigger("first"))
            }
            val afterReboot = status()
            assertEquals(false, afterReboot["blocked"])
            assertNull(afterReboot["blockedReason"])
            assertEquals(1, afterReboot["discardedAtBoot"])
            bridge(SimulatedPrinter(PrinterFault.DROP_AFTER_SUBMIT)).use {
                it.beginBoot("00000000-0000-4000-8000-000000000002", false)
                it.capture(SelectionTrigger("again"))
                assertEquals(ServiceState.NEEDS_ATTENTION, it.printNext().state)
            }
            val unresolved = status()
            assertEquals(true, unresolved["blocked"])
            assertEquals("UNRESOLVED_PRINT", unresolved["blockedReason"])
            assertEquals(1, unresolved["discardedAtBoot"])
        } finally { root.toFile().deleteRecursively() }
    }
}
