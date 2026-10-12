package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.bridge.simulation.SIMULATION_TOKEN
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SimulatedServiceDevicesTest {
    @Test fun printerPowerOrderAndRepeatedSelectionExerciseRealWireProtocols() {
        val devices = SimulatedServiceDevices()
        val config = devices.configuration
        val state = Files.createTempDirectory("service-wire-")
        fun open() = AutonomousBridge(state, CameraWifiConnector { devices.camera(config, it) },
            PrinterConnector { devices.printer(config) }, JvmPrintImagePreparer(), { SIMULATION_TOKEN },
            now = devices.clock::now, pause = devices.clock::pause)
        devices.simulationAction("printer-off")
        devices.simulationAction("select-photo")
        open().use { bridge ->
            assertEquals(1, bridge.capture(SelectionTrigger("first")).queued)
            assertEquals(ServiceState.WAITING_PRINTER, bridge.printNext().state)
        }
        devices.simulationAction("printer-on")
        open().use { bridge ->
            assertEquals(ServiceState.PRINTED, bridge.printNext().state)
            devices.simulationAction("duplicate-photo")
            bridge.capture(SelectionTrigger("same-bytes")); bridge.printNext()
        }
        assertEquals(1, devices.safeCounts()["prints"])
        assertEquals(1, devices.safeCounts()["completed"])
    }

    @Test fun uncertainCompletionPausesWithoutAnotherPrintAndInterruptedTransferDoesNotEnqueue() {
        val devices = SimulatedServiceDevices(); val config = devices.configuration
        AutonomousBridge(Files.createTempDirectory("service-unknown-"),
            CameraWifiConnector { devices.camera(config, it) }, PrinterConnector { devices.printer(config) },
            JvmPrintImagePreparer(), { SIMULATION_TOKEN }, now = devices.clock::now, pause = devices.clock::pause).use { bridge ->
            devices.simulationAction("interrupted-download")
            assertEquals(0, bridge.capture(SelectionTrigger("partial")).queued)
            assertEquals(0, devices.safeCounts()["prints"])
            devices.simulationAction("uncertain-print")
            devices.simulationAction("select-photo")
            bridge.capture(SelectionTrigger("uncertain"))
            assertEquals(ServiceState.NEEDS_ATTENTION, bridge.printNext().state)
            devices.simulationAction("printer-ready")
            assertEquals(ServiceState.NEEDS_ATTENTION, bridge.printNext().state)
            assertEquals(1, devices.safeCounts()["prints"])
        }
    }
}
