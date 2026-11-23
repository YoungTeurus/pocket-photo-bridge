package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.bridge.simulation.*
import dev.pocketphotobridge.sony.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SimulatedServiceDevicesTest {
    private fun open(devices: SimulatedServiceDevices, images: PrintImagePreparer = JvmPrintImagePreparer(),
        camera: CameraWifiConnector = CameraWifiConnector { devices.camera(devices.configuration, it) },
        printer: PrinterConnector = PrinterConnector { devices.printer(devices.configuration) }) =
        AutonomousBridge(Files.createTempDirectory("service-series-"), camera, printer, images, { SIMULATION_TOKEN },
            now = devices.clock::now, pause = devices.clock::pause)

    /** The service's own loop around a real bridge; [duringPrint] runs inside each print step before the print. */
    private class Service(val devices: SimulatedServiceDevices, val bridge: AutonomousBridge) {
        var time = 0L
        var duringPrint: () -> Unit = {}
        val steps = mutableListOf<CaptureStep>()
        val loop = ServiceLoop(BootModePolicy(0).apply { requestOperation() }, { time }, object : ServiceLoopActions {
            override fun enterOperation() {}
            override fun capture(trigger: SelectionTrigger) = bridge.capture(trigger).captureStep.also { steps += it }
            override fun printNext(): PrintStep { duringPrint(); return bridge.printNext().printStep }
        })
        fun passes(count: Int, stepMs: Long = 1_000) = repeat(count) {
            time += stepMs; loop.tick(time, true) { devices.cameraPresent(devices.configuration) }
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
        // A, B, then B once more after its own print, recognised as known.
        assertEquals(3, devices.safeCounts()["captured"])
        assertEquals(2, devices.safeCounts()["prints"])
        assertEquals(2, devices.safeCounts()["completed"])
    }

    @Test fun theSamePhotoOfferedAcrossThreeChecksPrintsOnceAndIsReportedAsADuplicateAfterwards() {
        val devices = SimulatedServiceDevices()
        devices.simulationAction("printer-on")
        devices.simulationAction("select-photo")
        open(devices).use { bridge ->
            val service = Service(devices, bridge)
            service.passes(3, stepMs = 200_000)
            assertEquals(listOf(CaptureStep.NEW_PHOTO, CaptureStep.DUPLICATE, CaptureStep.DUPLICATE), service.steps)
        }
        assertEquals(3, devices.safeCounts()["captured"])
        assertEquals(1, devices.safeCounts()["prints"])
        assertEquals(1, devices.safeCounts()["completed"])
    }

    @Test fun aStoredPhotoWhoseAcknowledgementTheCameraNeverReceivedIsAcknowledgedByTheNextCheck() {
        val devices = SimulatedServiceDevices()
        devices.simulationAction("printer-on")
        devices.simulationAction("select-photo")
        var loseAcknowledgement = true
        val camera = CameraWifiConnector { trigger ->
            val link = devices.camera(devices.configuration, trigger)
            if (!loseAcknowledgement) link else {
                loseAcknowledgement = false
                CameraLink(link.endpoint, object : CameraTransport {
                    override fun execute(request: CameraRequest): CameraResponse =
                        if (request.action?.endsWith("#X_TransferEnd") == true) throw java.io.IOException("lost")
                        else link.transport.execute(request)
                }, link::close)
            }
        }
        open(devices, camera = camera).use { bridge ->
            val service = Service(devices, bridge)
            service.passes(1)
            assertEquals(0, devices.safeCounts()["captured"])
            assertEquals(1, devices.safeCounts()["completed"])
            service.passes(1)
            assertEquals(1, devices.safeCounts()["captured"])
            devices.simulationAction("select-photo")
            service.passes(1, stepMs = ServiceLoop.MAX_RECHECK_MS)
            assertEquals(listOf(CaptureStep.NEW_PHOTO, CaptureStep.DUPLICATE, CaptureStep.NEW_PHOTO), service.steps)
        }
        assertEquals(2, devices.safeCounts()["captured"])
        assertEquals(2, devices.safeCounts()["completed"])
    }

    @Test fun aPhotoDroppedAfterARefusalIsNotFetchedAgainWhileTheCameraStaysButIsAcceptedWhenSentAgain() {
        val devices = SimulatedServiceDevices()
        devices.simulationAction("select-photo")
        var printer = SimulatedPrinter(PrinterFault.REFUSE_SUBMIT, refusalCode = -8100)
        val refusing = printer
        open(devices, printer = PrinterConnector { printer.connect() }).use { bridge ->
            val service = Service(devices, bridge)
            service.passes(1)
            assertEquals(1, bridge.snapshot().dropped)
            printer = SimulatedPrinter()
            service.passes(1, stepMs = ServiceLoop.FIRST_RECHECK_MS)
            assertEquals(listOf(CaptureStep.NEW_PHOTO, CaptureStep.DUPLICATE), service.steps)
            assertEquals(0, printer.submissions)
            // The camera network goes away; the owner then sends the same photo again.
            service.time += 1_000; service.loop.tick(service.time, true) { false }
            devices.simulationAction("duplicate-photo")
            service.passes(1)
            assertEquals(CaptureStep.NEW_PHOTO, service.steps.last())
        }
        assertEquals(1, refusing.refusals)
        assertEquals(1, printer.submissions); assertEquals(1, printer.completedJobs)
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
    @Test fun theSimulatedDevicesSupplyShortReCheckPausesAndTheLinuxDevicesTheRealOnes() {
        val simulated = SimulatedServiceDevices()
        assertEquals(2_000L, simulated.firstRecheckMs); assertEquals(10_000L, simulated.maxRecheckMs)
        val linux = dev.pocketphotobridge.bridge.service.LinuxServiceDevices(Files.createTempDirectory("linux-devices-")) { }
        assertEquals(60_000L, linux.firstRecheckMs); assertEquals(300_000L, linux.maxRecheckMs)
    }
    @Test fun hoursOfReChecksDoNotExhaustTheOwnersSimulationActions() {
        val devices = SimulatedServiceDevices()
        devices.simulationAction("select-photo")
        repeat(300) { devices.camera(devices.configuration, SelectionTrigger("check-$it")).close() }
        devices.simulationAction("printer-on")
        assertEquals(0, devices.safeCounts()["captured"])
    }
}
