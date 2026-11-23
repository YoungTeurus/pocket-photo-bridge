package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.bridge.simulation.SIMULATION_TOKEN
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SimulatedServiceDevicesTest {
    private fun open(devices: SimulatedServiceDevices, images: PrintImagePreparer = JvmPrintImagePreparer()) =
        AutonomousBridge(Files.createTempDirectory("service-series-"), CameraWifiConnector { devices.camera(devices.configuration, it) },
            PrinterConnector { devices.printer(devices.configuration) }, images, { SIMULATION_TOKEN },
            now = devices.clock::now, pause = devices.clock::pause)

    /** The service's own loop around a real bridge; [duringPrint] runs inside each print step before the print. */
    private class Service(val devices: SimulatedServiceDevices, val bridge: AutonomousBridge) {
        var time = 0L
        var duringPrint: () -> Unit = {}
        val loop = ServiceLoop(BootModePolicy(0).apply { requestOperation() }, { time }, object : ServiceLoopActions {
            override fun enterOperation() {}
            override fun capture(trigger: SelectionTrigger) = bridge.capture(trigger).captureStep
            override fun printNext(): Boolean { duringPrint(); return bridge.printNext().printAttemptFailed }
        })
        fun passes(count: Int, stepMs: Long = 1_000) = repeat(count) {
            time += stepMs; loop.tick(time, true, devices.cameraPresent(devices.configuration))
        }
    }

    @Test fun aPhotoSelectedDuringThePreviousPrintIsFetchedAfterItWithNoAbsenceInBetween() {
        val devices = SimulatedServiceDevices()
        devices.simulationAction("printer-on")
        devices.simulationAction("select-photo")
        open(devices).use { bridge ->
            val service = Service(devices, bridge)
            var selected = false
            service.duringPrint = { if (!selected && bridge.snapshot().queued == 1) { selected = true; devices.simulationAction("select-photo") } }
            service.passes(5)
            assertTrue(selected)
        }
        assertEquals(2, devices.safeCounts()["captured"])
        assertEquals(2, devices.safeCounts()["prints"])
        assertEquals(2, devices.safeCounts()["completed"])
    }

    @Test fun aSelectionIsVisibleOnTheNextScanAndStaysOfferedAfterItsTransfer() {
        val devices = SimulatedServiceDevices()
        devices.simulationAction("select-photo")
        assertTrue(devices.cameraPresent(devices.configuration))
        open(devices).use { bridge ->
            assertEquals(CaptureStep.NEW_PHOTO, bridge.capture(SelectionTrigger("first")).captureStep)
            assertTrue(devices.cameraPresent(devices.configuration))
            assertEquals(CaptureStep.DUPLICATE, bridge.capture(SelectionTrigger("first")).captureStep)
        }
        assertEquals(2, devices.safeCounts()["captured"])
    }

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
