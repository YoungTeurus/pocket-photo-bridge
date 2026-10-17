package dev.pocketphotobridge.bridge.linux

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.service.*
import dev.pocketphotobridge.bridge.setup.ServiceConfiguration
import dev.pocketphotobridge.bridge.simulation.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class NoPrintProbeTest {
    private val config = ServiceConfiguration("synthetic-camera", "synthetic-password",
        "02:00:00:00:00:01", SIMULATION_TOKEN)
    private fun root(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("no-print-probe-")
        try { test(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun photo() = javaClass.getResourceAsStream("/simulation/sony-selected.jpg")!!.use { it.readBytes() }

    @Test fun statusUsesOnlyReadOnlyCommandsAndNeverConnectsCamera() = root { state ->
        val printer = SimulatedPrinter()
        val camera = CameraWifiConnector { error("Status must not open camera") }
        assertEquals(0, NoPrintProbe(state, config, camera, printer, {}).run(false))
        assertTrue(printer.methods.isNotEmpty())
        assertTrue(printer.methods.all { it in setOf("mixed_status", "get_prop") })
        assertEquals(0, printer.submissions)
    }

    @Test fun receiveQueuesOriginalAcrossRestartWithoutConnectingPrinter() = root { state ->
        val sony = SimulatedSony(photo())
        val printer = PrinterConnector { error("Receive-only must not connect printer") }
        assertEquals(0, NoPrintProbe(state, config, sony, printer, {}).run(true))
        DurableCaptureQueue(state.resolve("service")).use { queue ->
            assertEquals(1, queue.size)
        }
        val originals = Files.list(state.resolve("service/images")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".original.jpg") }.toList()
        }
        assertArrayEquals(photo(), Files.readAllBytes(originals.single()))
    }

    @Test fun activeServiceLockPreventsProbeOpeningEitherTransport() = root { state ->
        DurableCaptureQueue(state.resolve("service")).use {
            try {
                NoPrintProbe(state, config, CameraWifiConnector { error("Camera must stay closed") },
                    PrinterConnector { error("Printer must stay closed") }, {}).run(false)
                fail("Expected exclusive-state rejection")
            } catch (failure: BridgeFailure) {
                assertEquals(BridgeFailureCode.INSTANCE_RUNNING, failure.code)
            }
        }
    }

    @Test fun interruptedReceiveDoesNotQueueOrConnectPrinter() = root { state ->
        val sony = SimulatedSony(photo(), SonyFault.INTERRUPTED)
        assertEquals(2, NoPrintProbe(state, config, sony,
            PrinterConnector { error("Printer must stay closed") }, {}).run(true))
        DurableCaptureQueue(state.resolve("service")).use { assertEquals(0, it.size) }
    }
}
