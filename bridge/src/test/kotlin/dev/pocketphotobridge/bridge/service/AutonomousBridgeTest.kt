package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.bridge.simulation.*
import dev.pocketphotobridge.printer.*
import dev.pocketphotobridge.sony.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class AutonomousBridgeTest {
    private fun photo() = javaClass.getResourceAsStream("/simulation/sony-selected.jpg")!!.use { it.readBytes() }
    private fun root(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("autonomous-test-")
        try { test(directory.resolve("state")) } finally { directory.toFile().deleteRecursively() }
    }
    private fun bridge(state: Path, camera: CameraWifiConnector = SimulatedSony(photo()),
        printer: PrinterConnector = SimulatedPrinter(), images: PrintImagePreparer = JvmPrintImagePreparer(),
        report: (String) -> Unit = {}): AutonomousBridge {
        val clock = SimulationClock()
        return AutonomousBridge(state, camera, printer, images, { SIMULATION_TOKEN },
            report = report, now = clock::now, pause = clock::pause)
    }
    private class PowerCut : Error()
    private fun imageFiles(root: Path): List<Path> = Files.list(root.resolve("images")).use { it.toList() }

    @Test fun captureIsDurableBeforeCameraAcknowledgementAndPrintsAfterRestart() = root { state ->
        val sony = SimulatedSony(photo())
        var checked = false
        val camera = CameraWifiConnector { trigger ->
            val link = sony.connect(trigger)
            CameraLink(link.endpoint, object : CameraTransport {
                override fun execute(request: CameraRequest): CameraResponse {
                    if (request.action?.endsWith("#X_TransferEnd") == true) {
                        checked = true
                        assertTrue(Files.size(state.resolve("queue.state")) > 100)
                        assertEquals(2, imageFiles(state).size)
                        assertArrayEquals(photo(), Files.readAllBytes(imageFiles(state).single { it.fileName.toString().endsWith(".original.jpg") }))
                        throw IOException("private camera identity")
                    }
                    return link.transport.execute(request)
                }
            }, link::close)
        }
        bridge(state, camera).use {
            assertEquals(ServiceSnapshot(ServiceState.QUEUED, 1, "CAMERA_ACK_PENDING"), it.capture(SelectionTrigger("same")))
            assertTrue(checked)
        }
        val printer = SimulatedPrinter()
        bridge(state, printer = printer).use {
            assertEquals(1, it.snapshot().queued)
            assertEquals(ServiceState.PRINTED, it.printNext().state)
            assertEquals(0, it.snapshot().queued)
            assertEquals(1, printer.completedJobs)
        }
        assertTrue(imageFiles(state).isEmpty())
    }

    @Test fun powerRestartDiscardsWaitingPhotoAndAllowsCameraToSendItAgain() = root { state ->
        bridge(state, printer = SimulatedPrinter(PrinterFault.OFFLINE)).use {
            it.beginBoot("00000000-0000-4000-8000-000000000001", false)
            assertEquals(1, it.capture(SelectionTrigger("first")).queued)
            assertEquals(ServiceState.WAITING_PRINTER, it.printNext().state)
        }
        val printer = SimulatedPrinter()
        bridge(state, printer = printer).use {
            assertEquals(0, it.beginBoot("00000000-0000-4000-8000-000000000002", false).queued)
            assertEquals(ServiceState.WAITING_CAMERA, it.printNext().state)
            assertEquals(0, printer.submissions)
            assertEquals(ServiceSnapshot(ServiceState.QUEUED, 1), it.capture(SelectionTrigger("first")))
        }
    }

    @Test fun firstUpgradeAdoptsCurrentBootWithoutDiscardingLegacyQueue() = root { state ->
        bridge(state).use { it.capture(SelectionTrigger("before-upgrade")) }
        bridge(state).use {
            assertEquals(1, it.beginBoot("00000000-0000-4000-8000-000000000001", false).queued)
        }
        bridge(state).use {
            assertEquals(1, it.beginBoot("00000000-0000-4000-8000-000000000001", false).queued)
        }
        bridge(state).use {
            assertEquals(0, it.beginBoot("00000000-0000-4000-8000-000000000002", false).queued)
        }
    }

    @Test fun sameBootServiceRestartRetainsWaitingPhoto() = root { state ->
        bridge(state).use {
            it.beginBoot("00000000-0000-4000-8000-000000000001", false)
            it.capture(SelectionTrigger("first"))
        }
        bridge(state).use {
            assertEquals(1, it.beginBoot("00000000-0000-4000-8000-000000000001", false).queued)
        }
    }

    @Test fun retentionOptInSurvivesNewBootAndPreferenceChangesApplyOnNextBoot() = root { state ->
        bridge(state).use {
            it.beginBoot("00000000-0000-4000-8000-000000000001", true)
            it.capture(SelectionTrigger("first"))
        }
        bridge(state).use {
            assertEquals(1, it.beginBoot("00000000-0000-4000-8000-000000000002", true).queued)
            assertEquals(1, it.beginBoot("00000000-0000-4000-8000-000000000002", false).queued)
        }
        bridge(state).use {
            assertEquals(0, it.beginBoot("00000000-0000-4000-8000-000000000003", false).queued)
        }
    }

    @Test fun newBootRetainsUncertainJobButDiscardsOtherWaitingPhoto() = root { state ->
        var camera = SimulatedSony(photo())
        val next = javaClass.getResourceAsStream("/simulation/sony-selected-next.jpg")!!.use { it.readBytes() }
        bridge(state, CameraWifiConnector { camera.connect(it) }, SimulatedPrinter(PrinterFault.DROP_AFTER_SUBMIT)).use {
            it.beginBoot("00000000-0000-4000-8000-000000000001", false)
            it.capture(SelectionTrigger("first"))
            camera = SimulatedSony(next)
            assertEquals(2, it.capture(SelectionTrigger("second")).queued)
            assertEquals(ServiceState.NEEDS_ATTENTION, it.printNext().state)
        }
        val ready = SimulatedPrinter()
        bridge(state, printer = ready).use {
            assertEquals(ServiceState.NEEDS_ATTENTION, it.beginBoot("00000000-0000-4000-8000-000000000002", false).state)
            assertEquals(1, it.snapshot().queued)
            assertEquals(ServiceState.NEEDS_ATTENTION, it.printNext().state)
            assertEquals(0, ready.submissions)
            assertEquals(0, it.acknowledgeCheckedPrinter().queued)
            assertEquals("DUPLICATE_SUPPRESSED", it.capture(SelectionTrigger("first")).category)
        }
        bridge(state, SimulatedSony(next)).use {
            assertEquals(ServiceSnapshot(ServiceState.QUEUED, 1), it.capture(SelectionTrigger("second")))
        }
    }

    @Test fun newBootDoesNotForgetSuccessfullyPrintedPhoto() = root { state ->
        bridge(state).use {
            it.beginBoot("00000000-0000-4000-8000-000000000001", false)
            it.capture(SelectionTrigger("first"))
            assertEquals(ServiceState.PRINTED, it.printNext().state)
        }
        val ready = SimulatedPrinter()
        bridge(state, printer = ready).use {
            assertEquals(0, it.beginBoot("00000000-0000-4000-8000-000000000002", false).queued)
            assertEquals("DUPLICATE_SUPPRESSED", it.capture(SelectionTrigger("again")).category)
            it.printNext()
            assertEquals(0, ready.submissions)
        }
    }

    @Test fun corruptBootMarkerBlocksBeforeDiscardingWaitingPhoto() = root { state ->
        bridge(state).use {
            it.beginBoot("00000000-0000-4000-8000-000000000001", false)
            it.capture(SelectionTrigger("first"))
        }
        Files.writeString(state.resolve("boot.state"), "corrupt")
        val ready = SimulatedPrinter()
        bridge(state, printer = ready).use {
            assertEquals(ServiceState.STATE_ERROR, it.beginBoot("00000000-0000-4000-8000-000000000002", false).state)
            assertEquals(1, it.snapshot().queued)
            assertEquals(ServiceState.STATE_ERROR, it.printNext().state)
            assertEquals(0, ready.submissions)
        }
        assertEquals(2, imageFiles(state).size)
    }

    @Test fun failedCameraSelectionCanRetryTheSameTriggerAfterRestart() = root { state ->
        bridge(state, SimulatedSony(photo(), SonyFault.OFFLINE)).use {
            assertEquals(ServiceState.WAITING_CAMERA, it.capture(SelectionTrigger("same")).state)
            assertEquals(0, it.snapshot().queued)
        }
        bridge(state).use {
            assertEquals(ServiceSnapshot(ServiceState.QUEUED, 1), it.capture(SelectionTrigger("same")))
        }
    }

    @Test fun failedNewCaptureRetainsOlderQueueWithoutSignallingSuccessfulCapture() = root { state ->
        var camera = SimulatedSony(photo())
        val next = javaClass.getResourceAsStream("/simulation/sony-selected-next.jpg")!!.use { it.readBytes() }
        bridge(state, CameraWifiConnector { camera.connect(it) }).use {
            it.capture(SelectionTrigger("first"))
            camera = SimulatedSony(next, SonyFault.INTERRUPTED)
            assertEquals(ServiceSnapshot(ServiceState.WAITING_CAMERA, 1, "TRUNCATED_JPEG"), it.capture(SelectionTrigger("second")))
            camera = SimulatedSony(next)
            assertEquals(ServiceSnapshot(ServiceState.QUEUED, 2), it.capture(SelectionTrigger("second")))
        }
    }

    @Test fun printerOffAndBusyRetainQueuedCaptureForSafeRetry() = root { state ->
        bridge(state, printer = SimulatedPrinter(PrinterFault.OFFLINE)).use {
            it.capture(SelectionTrigger("selected"))
            assertEquals(ServiceSnapshot(ServiceState.WAITING_PRINTER, 1, "PRINTER_OFFLINE"), it.printNext())
        }
        val busy = SimulatedPrinter(PrinterFault.BUSY)
        bridge(state, printer = busy).use {
            assertEquals(ServiceSnapshot(ServiceState.WAITING_PRINTER, 1, "BUSY"), it.printNext())
            assertEquals(0, busy.submissions)
        }
        val ready = SimulatedPrinter()
        bridge(state, printer = ready).use {
            assertEquals(ServiceState.PRINTED, it.printNext().state)
            assertEquals(1, ready.submissions)
        }
    }

    @Test fun interruptedAndInvalidPhotosAreNeverQueuedOrAcknowledged() = root { state ->
        val interrupted = SimulatedSony(photo(), SonyFault.INTERRUPTED)
        bridge(state, interrupted).use {
            assertEquals(0, it.capture(SelectionTrigger("same")).queued)
            assertEquals(0, interrupted.completedTransfers)
            assertTrue(imageFiles(state).isEmpty())
        }
        val invalid = SimulatedSony(byteArrayOf(-1, -40, 0, 0, -1, -39))
        bridge(state, invalid).use {
            assertEquals("INVALID_IMAGE", it.capture(SelectionTrigger("same")).category)
            assertEquals(0, it.snapshot().queued)
            assertEquals(0, invalid.completedTransfers)
        }
        bridge(state).use { assertEquals(1, it.capture(SelectionTrigger("same")).queued) }
    }

    @Test fun unknownAcceptanceBlocksUntilCheckAndRetainsTombstoneAcrossRestart() = root { state ->
        val uncertain = SimulatedPrinter(PrinterFault.DROP_AFTER_SUBMIT)
        bridge(state, printer = uncertain).use {
            it.capture(SelectionTrigger("first"))
            assertEquals(ServiceState.NEEDS_ATTENTION, it.printNext().state)
            assertEquals(1, uncertain.submissions)
        }
        val ready = SimulatedPrinter()
        val sony = SimulatedSony(photo())
        bridge(state, sony, ready).use {
            assertEquals(ServiceState.NEEDS_ATTENTION, it.snapshot().state)
            assertEquals(ServiceState.NEEDS_ATTENTION, it.capture(SelectionTrigger("next")).state)
            assertEquals(ServiceState.NEEDS_ATTENTION, it.printNext().state)
            assertEquals(0, sony.downloads)
            assertEquals(0, ready.submissions)
            assertEquals(0, it.acknowledgeCheckedPrinter().queued)
        }
        bridge(state, printer = ready).use {
            assertEquals("DUPLICATE_SUPPRESSED", it.capture(SelectionTrigger("fresh")).category)
            assertEquals(0, it.printNext().queued)
            assertEquals(0, ready.submissions)
        }
    }

    @Test fun hardLossAfterPossibleAcceptanceBlocksOnRestart() = root { state ->
        bridge(state, printer = SimulatedPrinter(PrinterFault.POWER_CUT)).use {
            it.capture(SelectionTrigger("first"))
            try { it.printNext(); fail("Expected simulated power loss") } catch (_: SimulatedPowerLoss) { }
        }
        val ready = SimulatedPrinter()
        bridge(state, printer = ready).use {
            assertEquals(ServiceState.NEEDS_ATTENTION, it.printNext().state)
            assertEquals(0, ready.submissions)
        }
    }

    @Test fun resolvedJournalWithPendingQueueRecoversWithoutAnotherSubmission() = root { state ->
        val printer = SimulatedPrinter()
        bridge(state, printer = printer, report = { if (it == "PRINTER_PRINT_FINISHED") throw PowerCut() }).use {
            it.capture(SelectionTrigger("first"))
            try { it.printNext(); fail("Expected power loss after journal resolution") } catch (_: PowerCut) { }
            assertEquals(1, printer.completedJobs)
        }
        val restarted = SimulatedPrinter()
        bridge(state, printer = restarted).use {
            assertEquals(0, it.snapshot().queued)
            assertEquals(0, it.printNext().queued)
            assertEquals("DUPLICATE_SUPPRESSED", it.capture(SelectionTrigger("again")).category)
            assertEquals(0, restarted.submissions)
        }
    }

    @Test fun duplicateContentDoesNotAddQueuedCopiesAndSurvivesCompletedRestart() = root { state ->
        val printer = SimulatedPrinter()
        bridge(state, printer = printer).use {
            it.capture(SelectionTrigger("first"))
            assertEquals(1, it.capture(SelectionTrigger("different generation")).queued)
            assertEquals(ServiceState.PRINTED, it.printNext().state)
        }
        bridge(state, printer = printer).use {
            assertEquals("DUPLICATE_SUPPRESSED", it.capture(SelectionTrigger("another")).category)
            assertEquals(0, it.printNext().queued)
            assertEquals(1, printer.submissions)
        }
    }

    @Test fun corruptQueueMetadataFailsClosedBeforeDeviceTraffic() = root { state ->
        bridge(state).use { it.capture(SelectionTrigger("first")) }
        Files.writeString(state.resolve("queue.state"), "not a valid queue")
        try { bridge(state).close(); fail("Expected corrupt-state rejection") }
        catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.PERSISTENCE_ERROR, failure.code) }
    }

    @Test fun corruptOriginalOrPreparedImageFailsClosedBeforePrinting() {
        for (suffix in listOf(".original.jpg", ".print.jpg")) root { state ->
            bridge(state).use { it.capture(SelectionTrigger("first")) }
            val file = imageFiles(state).single { it.fileName.toString().endsWith(suffix) }
            val bytes = Files.readAllBytes(file); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
            Files.write(file, bytes)
            try { bridge(state).close(); fail("Expected image-integrity rejection") }
            catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.PERSISTENCE_ERROR, failure.code) }
        }
    }

    @Test fun missingPriorQueueOrJournalCannotBeTreatedAsFreshState() {
        for (missing in listOf("queue.state", "print-journal/journal.state")) root { state ->
            bridge(state).use { it.capture(SelectionTrigger("first")) }
            Files.delete(state.resolve(missing))
            try { bridge(state).close(); fail("Expected missing-state rejection") }
            catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.PERSISTENCE_ERROR, failure.code) }
        }
    }

    @Test fun liveCorruptionReturnsStateErrorAndDoesNotContactPrinter() = root { state ->
        val printer = SimulatedPrinter()
        bridge(state, printer = printer).use {
            it.capture(SelectionTrigger("first"))
            Files.delete(imageFiles(state).first())
            assertEquals(ServiceState.STATE_ERROR, it.printNext().state)
            assertEquals(ServiceState.STATE_ERROR, it.snapshot().state)
            assertEquals(0, printer.submissions)
        }
    }

    @Test fun liveJournalCorruptionFailsClosedBeforeDeviceTraffic() = root { state ->
        val printer = SimulatedPrinter()
        bridge(state, printer = printer).use {
            it.capture(SelectionTrigger("first"))
            Files.writeString(state.resolve("print-journal/journal.state"), "corrupted journal")
            assertEquals(ServiceState.STATE_ERROR, it.printNext().state)
            assertEquals(0, printer.submissions)
        }
    }

    @Test fun queueCapacityStopsCaptureBeforeAcknowledgement() = root { state ->
        val original = photo()
        val prepared = javaClass.getResourceAsStream("/simulation/portrait-standard.jpg")!!.use { it.readBytes() }
        val preparation = object : PrintImagePreparer {
            private val real = JvmPrintImagePreparer()
            override fun validateSource(source: File) = real.validateSource(source)
            override fun prepare(source: File, crop: Boolean) = prepared.copyOf()
        }
        var camera = SimulatedSony(original)
        bridge(state, CameraWifiConnector { camera.connect(it) }, images = preparation).use {
            repeat(16) { index ->
                camera = SimulatedSony(original + byteArrayOf(index.toByte(), -1, -39))
                assertEquals(index + 1, it.capture(SelectionTrigger("$index")).queued)
            }
            camera = SimulatedSony(original + byteArrayOf(17, -1, -39))
            assertEquals(ServiceSnapshot(ServiceState.QUEUED, 16, "QUEUE_FULL"), it.capture(SelectionTrigger("overflow")))
            assertEquals(0, camera.downloads)
            assertEquals(0, camera.completedTransfers)
        }
    }

    @Test fun oversizePreparedImageIsNotAcknowledgedOrQueued() = root { state ->
        val sony = SimulatedSony(photo())
        val real = JvmPrintImagePreparer()
        val preparation = object : PrintImagePreparer {
            override fun validateSource(source: File) = real.validateSource(source)
            override fun prepare(source: File, crop: Boolean) = ByteArray(PrinterSession.MAX_JPEG_BYTES + 1)
        }
        bridge(state, sony, images = preparation).use {
            assertEquals("INVALID_IMAGE", it.capture(SelectionTrigger("first")).category)
            assertEquals(0, it.snapshot().queued)
            assertEquals(0, sony.completedTransfers)
        }
    }

    @Test fun undecodablePreparedBytesAreRejectedBeforeCameraAcknowledgement() = root { state ->
        val sony = SimulatedSony(photo())
        val real = JvmPrintImagePreparer()
        val preparation = object : PrintImagePreparer {
            override fun validateSource(source: File) = real.validateSource(source)
            override fun prepare(source: File, crop: Boolean) = ByteArray(16).also {
                it[0] = -1; it[1] = -40; it[14] = -1; it[15] = -39
            }
        }
        bridge(state, sony, images = preparation).use {
            assertEquals("INVALID_IMAGE", it.capture(SelectionTrigger("first")).category)
            assertEquals(0, it.snapshot().queued)
            assertEquals(0, sony.completedTransfers)
        }
    }

    @Test fun preparationCancellationCanRetrySameTriggerWithoutRestart() = root { state ->
        val prepared = javaClass.getResourceAsStream("/simulation/portrait-standard.jpg")!!.use { it.readBytes() }
        val real = JvmPrintImagePreparer()
        var interrupt = true
        val preparation = object : PrintImagePreparer {
            override fun validateSource(source: File) = real.validateSource(source)
            override fun prepare(source: File, crop: Boolean): ByteArray {
                if (interrupt) Thread.currentThread().interrupt()
                return prepared
            }
        }
        bridge(state, images = preparation).use {
            try { assertEquals("CANCELLED", it.capture(SelectionTrigger("same")).category) }
            finally { Thread.interrupted() }
            interrupt = false
            assertEquals(1, it.capture(SelectionTrigger("same")).queued)
        }
    }

    @Test fun preparedImageWithWrongPaperDimensionsIsNotAcknowledged() = root { state ->
        val sony = SimulatedSony(photo())
        val real = JvmPrintImagePreparer()
        val preparation = object : PrintImagePreparer {
            override fun validateSource(source: File) = real.validateSource(source)
            override fun prepare(source: File, crop: Boolean) = photo()
        }
        bridge(state, sony, images = preparation).use {
            assertEquals("INVALID_IMAGE", it.capture(SelectionTrigger("first")).category)
            assertEquals(0, it.snapshot().queued)
            assertEquals(0, sony.completedTransfers)
        }
    }

    @Test fun restartAfterHardFailureBeforeReservationStillPrintsQueuedPhoto() = root { state ->
        val device = SimulatedPrinter()
        val printer = PrinterConnector {
            val transport = device.connect()
            object : PrinterTransport {
                private val cipher = PrinterCipher(SIMULATION_TOKEN)
                override fun write(bytes: ByteArray) {
                    val frame = PrinterProtocol.decode(bytes)
                    if (frame.channel == 1 && cipher.crypt(frame.body).toString(Charsets.UTF_8).contains("clean_data"))
                        throw PowerCut()
                    transport.write(bytes)
                }
                override fun read(timeoutMs: Long) = transport.read(timeoutMs)
                override fun close() { try { transport.close() } finally { cipher.close() } }
            }
        }
        bridge(state, printer = printer).use {
            it.capture(SelectionTrigger("first"))
            try { it.printNext(); fail("Expected hard failure before reservation") } catch (_: PowerCut) { }
            assertEquals(0, device.submissions)
        }
        val ready = SimulatedPrinter()
        bridge(state, printer = ready).use {
            assertEquals(ServiceState.PRINTED, it.printNext().state)
            assertEquals(1, ready.completedJobs)
        }
    }

    @Test fun checkAcknowledgementRemovesOnlySubmittedPhotoAndPreservesOtherQueuedCapture() = root { state ->
        var camera = SimulatedSony(photo())
        val second = javaClass.getResourceAsStream("/simulation/sony-selected-next.jpg")!!.use { it.readBytes() }
        bridge(state, CameraWifiConnector { camera.connect(it) }, SimulatedPrinter(PrinterFault.DROP_AFTER_SUBMIT)).use {
            it.capture(SelectionTrigger("first"))
            camera = SimulatedSony(second)
            assertEquals(2, it.capture(SelectionTrigger("second")).queued)
            assertEquals(ServiceState.NEEDS_ATTENTION, it.printNext().state)
        }
        // Simulate death after durable acknowledgement but before queue cleanup.
        FileBridgeJournal(state.resolve("print-journal")).use { it.acknowledgeCheckedPrinter() }
        val ready = SimulatedPrinter()
        bridge(state, printer = ready).use {
            assertEquals(1, it.snapshot().queued)
            assertEquals(ServiceState.PRINTED, it.printNext().state)
            assertEquals(1, ready.completedJobs)
            assertEquals("DUPLICATE_SUPPRESSED", it.capture(SelectionTrigger("again")).category)
        }
    }

    @Test fun symlinkedImageOrQueueStateIsRejectedWithoutTouchingTarget() {
        if (!Path.of("/").fileSystem.supportedFileAttributeViews().contains("posix")) return
        for (replaceImage in listOf(true, false)) root { state ->
            bridge(state).use { it.capture(SelectionTrigger("first")) }
            val file = if (replaceImage) imageFiles(state).first() else state.resolve("queue.state")
            val target = state.parent.resolve("unrelated")
            val bytes = Files.readAllBytes(file)
            Files.write(target, bytes)
            Files.delete(file)
            Files.createSymbolicLink(file, target)
            try { bridge(state).close(); fail("Expected symlink rejection") }
            catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.PERSISTENCE_ERROR, failure.code) }
            assertArrayEquals(bytes, Files.readAllBytes(target))
        }
    }

    @Test fun validChecksumDoesNotAllowOversizeQueueRecord() = root { state ->
        bridge(state).close()
        val digest = "0".repeat(64)
        val payload = "PPB-QUEUE 1\n$digest:$digest:67108865:16\n"
        Files.writeString(state.resolve("queue.state"), payload + "checksum=${DurableCaptureQueue.hash(payload.toByteArray())}\n")
        try { bridge(state).close(); fail("Expected bound rejection") }
        catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.PERSISTENCE_ERROR, failure.code) }
    }

    @Test fun stateIsPrivateAndCannotHaveTwoOwners() = root { state ->
        bridge(state).use {
            try { bridge(state).close(); fail("Expected exclusive owner") }
            catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.INSTANCE_RUNNING, failure.code) }
            it.capture(SelectionTrigger("first"))
            if (state.fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(state))
                imageFiles(state).forEach { file ->
                    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
                }
            }
        }
    }

    @Test fun diagnosticsUseOnlyFixedCategoriesAndCollapsePrintingPolls() = root { state ->
        val logs = mutableListOf<String>()
        val camera = CameraWifiConnector { throw IOException("private camera password and address") }
        bridge(state, camera, report = logs::add).use {
            assertEquals("IO_ERROR", it.capture(SelectionTrigger("private selection id")).category)
        }
        bridge(state, printer = SimulatedPrinter(PrinterFault.NEVER_FINISH), report = logs::add).use {
            it.capture(SelectionTrigger("private selection id"))
            assertEquals(ServiceState.NEEDS_ATTENTION, it.printNext().state)
        }
        assertEquals(1, logs.count { it == "PRINTER_PRINTING" })
        assertTrue(logs.all { Regex("[A-Z_]+(?: queued=[0-9]+)?(?: category=[A-Z_]+)?").matches(it) })
        assertFalse(logs.joinToString("\n").contains("private"))
    }
}
