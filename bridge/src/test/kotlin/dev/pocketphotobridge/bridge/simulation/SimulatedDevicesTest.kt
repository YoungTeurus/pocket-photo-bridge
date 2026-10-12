package dev.pocketphotobridge.bridge.simulation

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.printer.*
import dev.pocketphotobridge.sony.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.imageio.ImageIO

class SimulatedDevicesTest {
    private fun jpeg(name: String = "portrait-standard.jpg") =
        requireNotNull(javaClass.getResourceAsStream("/simulation/$name")).use { it.readBytes() }

    private fun withDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("simulated-sony").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private class Guard : PrintJobGuard {
        override var unresolved = false
        var reservations = 0
        var resolutions = 0
        override fun reserve(): String {
            check(!unresolved)
            unresolved = true
            reservations++
            return "synthetic-reservation"
        }
        override fun resolve(reservation: String) {
            assertEquals("synthetic-reservation", reservation)
            check(unresolved)
            unresolved = false
            resolutions++
        }
        override fun acknowledgeCheckedPrinter() { unresolved = false }
    }

    private fun print(printer: SimulatedPrinter, bytes: ByteArray = jpeg(), guard: Guard = Guard(),
        clock: SimulationClock = SimulationClock(), events: MutableList<PrinterDiagnostic> = mutableListOf()) {
        printer.connect().use { transport ->
            PrinterSession(transport, SIMULATION_TOKEN, events::add, clock::now, clock::pause).use {
                it.print(bytes, guard)
            }
        }
    }

    @Test fun realSonySessionReceivesOriginalAndValidatesBeforeTransferEnd() = withDirectory { directory ->
        val bytes = jpeg()
        val camera = SimulatedSony(bytes)
        var validationCalls = 0
        camera.connect(SelectionTrigger("synthetic-generation")).use { link ->
            val photo = SonyTransferSession(link.transport, link.endpoint, validateJpeg = { source ->
                assertEquals(0, camera.completedTransfers)
                val decoded = ImageIO.read(source)
                assertEquals(1040, decoded.width)
                assertEquals(1560, decoded.height)
                validationCalls++
            }).receive(directory)
            assertArrayEquals(bytes, photo.file.readBytes())
            assertEquals(bytes.size.toLong(), photo.bytes)
        }
        assertEquals(1, validationCalls)
        assertEquals(1, camera.transferStarts)
        assertEquals(1, camera.completedTransfers)
        assertEquals(1, camera.downloads)
    }

    @Test fun emptyPushSelectionNeverDownloadsOrEnds() = withDirectory { directory ->
        val camera = SimulatedSony(jpeg(), SonyFault.EMPTY_SELECTION)
        camera.connect(SelectionTrigger("empty-generation")).use { link ->
            val failure = assertThrows(ProtocolFailure::class.java) {
                SonyTransferSession(link.transport, link.endpoint).receive(directory)
            }
            assertEquals(Failure.NO_JPEG, failure.failure)
        }
        assertEquals(1, camera.transferStarts)
        assertEquals(0, camera.downloads)
        assertEquals(0, camera.completedTransfers)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun interruptedBodyIsRemovedWithoutSuccessfulTransferEnd() = withDirectory { directory ->
        val camera = SimulatedSony(jpeg(), SonyFault.INTERRUPTED)
        camera.connect(SelectionTrigger("interrupted-generation")).use { link ->
            val failure = assertThrows(ProtocolFailure::class.java) {
                SonyTransferSession(link.transport, link.endpoint).receive(directory)
            }
            assertEquals(Failure.TRUNCATED_JPEG, failure.failure)
        }
        assertEquals(1, camera.downloads)
        assertEquals(0, camera.completedTransfers)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun cameraConnectionFaultsExposeOnlyFixedCategories() {
        val cases = mapOf(SonyFault.MISSING_SSID to BridgeFailureCode.SSID_MISSING,
            SonyFault.OFFLINE to BridgeFailureCode.CAMERA_OFFLINE,
            SonyFault.TIMEOUT to BridgeFailureCode.CAMERA_TIMEOUT)
        cases.forEach { (fault, expected) ->
            val camera = SimulatedSony(jpeg(), fault)
            val failure = assertThrows(BridgeFailure::class.java) { camera.connect(SelectionTrigger("private-trigger")) }
            assertEquals(expected, failure.code)
            assertEquals(expected.name, failure.message)
            assertEquals(0, camera.transferStarts)
        }
    }

    @Test fun realPrinterSessionUploadsExactJpegAndResolvesOneCopyJob() {
        val printer = SimulatedPrinter()
        val bytes = jpeg()
        val guard = Guard()
        val clock = SimulationClock()
        val events = mutableListOf<PrinterDiagnostic>()
        print(printer, bytes, guard, clock, events)
        assertEquals(1, printer.submissions)
        assertEquals(1, printer.completedJobs)
        assertEquals((bytes.size + 923) / 924, printer.uploadedFrames)
        assertArrayEquals(bytes, printer.uploadedJpeg)
        val decoded = ImageIO.read(ByteArrayInputStream(printer.uploadedJpeg!!))
        assertEquals(1040, decoded.width)
        assertEquals(1560, decoded.height)
        assertEquals(1, guard.reservations)
        assertEquals(1, guard.resolutions)
        assertFalse(guard.unresolved)
        assertEquals(listOf("mixed_status", "clean_data", "print_job", "confirm_job", "job_info"), printer.methods)
        assertTrue(clock.now() > 0)
        val received = events.filter { it.stage == PrinterStage.RX_FRAME }
        assertTrue(received.isNotEmpty())
        assertTrue(received.all { it.frame?.let { frame -> frame.version == 1 && frame.interactive == 6 } == true })
        assertTrue(events.any { it.stage == PrinterStage.REPLY_IGNORED && it.reason == PrinterFailureReason.RPC_METHOD_MESSAGE })
        assertTrue(events.count { it.stage == PrinterStage.RX_BYTES } > received.size)
    }

    @Test fun realPrinterDiagnosisDoesNotCreateOrClearJobs() {
        val printer = SimulatedPrinter()
        val clock = SimulationClock()
        printer.connect().use { transport ->
            PrinterSession(transport, SIMULATION_TOKEN, now = clock::now, pause = clock::pause).use {
                val status = it.diagnose()
                assertEquals("init", status.phase)
                assertEquals(80, status.battery)
            }
        }
        assertEquals(listOf("mixed_status", "get_prop"), printer.methods)
        assertEquals(0, printer.submissions)
    }

    @Test fun busyPrinterStopsRealSessionBeforeReservation() {
        val printer = SimulatedPrinter(PrinterFault.BUSY)
        val guard = Guard()
        val failure = assertThrows(PrinterFailure::class.java) { print(printer, guard = guard) }
        assertEquals(PrinterFailureCode.BUSY, failure.code)
        assertFalse(guard.unresolved)
        assertEquals(0, guard.reservations)
        assertEquals(0, printer.submissions)
    }

    @Test fun offlinePrinterDoesNotOpenTransport() {
        val printer = SimulatedPrinter(PrinterFault.OFFLINE)
        val failure = assertThrows(BridgeFailure::class.java) { printer.connect() }
        assertEquals(BridgeFailureCode.PRINTER_OFFLINE, failure.code)
        assertEquals(BridgeFailureCode.PRINTER_OFFLINE.name, failure.message)
        assertEquals(0, printer.submissions)
    }

    @Test fun droppedAcceptedJobLeavesGuardUnresolvedWithoutUploadOrRetry() {
        val printer = SimulatedPrinter(PrinterFault.DROP_AFTER_SUBMIT)
        val guard = Guard()
        val events = mutableListOf<PrinterDiagnostic>()
        val failure = assertThrows(PrinterFailure::class.java) { print(printer, guard = guard, events = events) }
        assertEquals(PrinterFailureCode.DISCONNECTED, failure.code)
        assertEquals(1, printer.submissions)
        assertEquals(0, printer.uploadedFrames)
        assertEquals(0, printer.completedJobs)
        assertTrue(guard.unresolved)
        assertTrue(events.any { it.stage == PrinterStage.OUTCOME_UNKNOWN })
    }

    @Test fun neverFinishedJobTimesOutOnVirtualClockWithGuardUnresolved() {
        val printer = SimulatedPrinter(PrinterFault.NEVER_FINISH)
        val guard = Guard()
        val clock = SimulationClock()
        val failure = assertThrows(PrinterFailure::class.java) { print(printer, guard = guard, clock = clock) }
        assertEquals(PrinterFailureCode.TIMEOUT, failure.code)
        assertTrue(clock.now() >= 240_000)
        assertEquals(1, printer.submissions)
        assertEquals(0, printer.completedJobs)
        assertTrue(guard.unresolved)
        assertTrue(printer.methods.count { it == "job_info" } > 1)
    }

    @Test fun powerCutOccursAfterGuardAndAcceptanceBeforeAnyUpload() {
        val guard = Guard()
        lateinit var printer: SimulatedPrinter
        var crashCalls = 0
        printer = SimulatedPrinter(PrinterFault.POWER_CUT) {
            assertTrue(guard.unresolved)
            assertEquals(1, printer.submissions)
            assertEquals(0, printer.uploadedFrames)
            crashCalls++
            throw SimulatedPowerLoss()
        }
        val failure = assertThrows(SimulatedPowerLoss::class.java) { print(printer, guard = guard) }
        assertEquals("SIMULATED_POWER_LOSS", failure.message)
        assertEquals(1, crashCalls)
        assertTrue(guard.unresolved)
        assertEquals(0, guard.resolutions)
    }

    @Test fun optimizedHuffmanJpegIsRejectedByDeviceBeforeConfirmation() {
        val printer = SimulatedPrinter()
        val guard = Guard()
        val failure = assertThrows(PrinterFailure::class.java) {
            print(printer, jpeg("portrait-optimized.jpg"), guard)
        }
        assertEquals(PrinterFailureCode.PROTOCOL_ERROR, failure.code)
        assertEquals(1, printer.submissions)
        assertEquals(0, printer.completedJobs)
        assertTrue(guard.unresolved)
    }

    @Test fun reconnectCannotClearAnAcceptedJobWhoseReplyWasLost() {
        val printer = SimulatedPrinter(PrinterFault.DROP_AFTER_SUBMIT)
        assertThrows(PrinterFailure::class.java) { print(printer) }
        val freshGuard = Guard()
        val failure = assertThrows(PrinterFailure::class.java) { print(printer, guard = freshGuard) }
        assertEquals(PrinterFailureCode.BUSY, failure.code)
        assertEquals(0, freshGuard.reservations)
        assertEquals(1, printer.submissions)
    }

    @Test fun rejectedSourceDecodeNeverEndsSonyTransfer() = withDirectory { directory ->
        val camera = SimulatedSony(jpeg())
        camera.connect(SelectionTrigger("rejected-generation")).use { link ->
            val failure = assertThrows(IOException::class.java) {
                SonyTransferSession(link.transport, link.endpoint, validateJpeg = {
                    throw IOException("SYNTHETIC_INVALID_IMAGE")
                }).receive(directory)
            }
            assertEquals("SYNTHETIC_INVALID_IMAGE", failure.message)
        }
        assertEquals(1, camera.downloads)
        assertEquals(0, camera.completedTransfers)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun closingCameraLinkClosesOutstandingResponseAndConnection() {
        val link = SimulatedSony(jpeg()).connect(SelectionTrigger("closed-generation"))
        val request = CameraRequest(link.endpoint.resolve("/DmsDescPush.xml"))
        val response = link.transport.execute(request)
        link.close()
        link.close()
        assertThrows(IOException::class.java) { response.body.read() }
        assertThrows(IOException::class.java) { link.transport.execute(request) }
        response.close()
    }

    @Test fun cameraRejectsPhotoLibraryAndThumbnailRequests() {
        val camera = SimulatedSony(jpeg())
        camera.connect(SelectionTrigger("unexpected-request")).use { link ->
            for (request in listOf(
                CameraRequest(link.endpoint.resolve("/content"), "urn:schemas-upnp-org:service:ContentDirectory:1#Browse",
                    "<ObjectID>PhotoRoot</ObjectID>"),
                CameraRequest(link.endpoint.resolve("/thumb.jpg")))) {
                val failure = assertThrows(ProtocolFailure::class.java) { link.transport.execute(request) }
                assertEquals(Failure.WRONG_MODE, failure.failure)
            }
        }
        assertEquals(0, camera.downloads)
    }

    @Test fun completedJobPollsDoNotIncreaseCountAndNextCleanJobCompletesOnce() {
        val printer = SimulatedPrinter()
        print(printer)
        printer.connect().use { transport ->
            PrinterCipher(SIMULATION_TOKEN).use { cipher ->
                rpc(transport, cipher, 1, "job_info", "[7]")
                rpc(transport, cipher, 2, "job_info", "[7]")
            }
        }
        assertEquals(1, printer.completedJobs)
        print(printer)
        assertEquals(2, printer.completedJobs)
        assertEquals(2, printer.submissions)
        assertEquals(2 * ((jpeg().size + 923) / 924), printer.uploadedFrames)
    }

    @Test fun duplicateReservationCannotCreateAnotherJobWithoutClear() {
        val printer = SimulatedPrinter()
        printer.connect().use { transport ->
            PrinterCipher(SIMULATION_TOKEN).use { cipher ->
                rpc(transport, cipher, 1, "clean_data", "{\"delay_times\":0}")
                rpc(transport, cipher, 2, "print_job", validJobParams())
                val failure = assertThrows(PrinterFailure::class.java) {
                    rpc(transport, cipher, 3, "print_job", validJobParams())
                }
                assertEquals(PrinterFailureCode.PROTOCOL_ERROR, failure.code)
                assertThrows(IOException::class.java) { transport.read(1) }
            }
        }
        assertEquals(1, printer.submissions)
    }

    @Test fun wireRejectsWrongCopiesChannelJobTypeAndFileSizeBeforeAcceptance() {
        val invalidParams = listOf(
            validJobParams().replace("\"copies\":1", "\"copies\":2"),
            validJobParams().replace("\"channel\":64", "\"channel\":63"),
            validJobParams().replace("\"job_type\":0", "\"job_type\":1"),
            validJobParams(size = 0), validJobParams(size = PrinterSession.MAX_JPEG_BYTES + 1))
        invalidParams.forEach { params ->
            val printer = SimulatedPrinter()
            printer.connect().use { transport ->
                PrinterCipher(SIMULATION_TOKEN).use { cipher ->
                    rpc(transport, cipher, 1, "clean_data", "{\"delay_times\":0}")
                    val failure = assertThrows(PrinterFailure::class.java) { rpc(transport, cipher, 2, "print_job", params) }
                    assertEquals(PrinterFailureCode.PROTOCOL_ERROR, failure.code)
                }
            }
            assertEquals(0, printer.submissions)
        }
    }

    @Test fun wireRejectsWrongJobAndChunkPackageFieldsBeforeAppendingBytes() {
        val bytes = jpeg()
        val total = (bytes.size + 923) / 924
        val chunk = bytes.copyOfRange(0, 924)
        val invalidFrames = listOf(
            fileFrame(chunk, jobId = 8, total = total),
            fileFrame(chunk, total = total + 1),
            fileFrame(chunk, total = total, part = 2),
            fileFrame(bytes.copyOfRange(0, 925), total = total),
            fileFrame(bytes.copyOfRange(0, 923), total = total),
            fileFrame(chunk, total = total).copy(encoding = 1),
            fileFrame(chunk, total = total).copy(channel = 255))
        invalidFrames.forEach { frame ->
            val printer = SimulatedPrinter()
            printer.connect().use { transport ->
                PrinterCipher(SIMULATION_TOKEN).use { cipher ->
                    rpc(transport, cipher, 1, "clean_data", "{\"delay_times\":0}")
                    rpc(transport, cipher, 2, "print_job", validJobParams())
                    val failure = assertThrows(PrinterFailure::class.java) {
                        transport.write(PrinterProtocol.encode(frame.copy(body = cipher.crypt(frame.body))))
                    }
                    assertEquals(PrinterFailureCode.PROTOCOL_ERROR, failure.code)
                }
            }
            assertEquals(0, printer.uploadedFrames)
            assertNull(printer.uploadedJpeg)
        }
    }

    @Test fun confirmRequiresAllAdvertisedBytesAndCorrectJob() {
        for (params in listOf("[7]", "[8]")) {
            val printer = SimulatedPrinter()
            printer.connect().use { transport ->
                PrinterCipher(SIMULATION_TOKEN).use { cipher ->
                    rpc(transport, cipher, 1, "clean_data", "{\"delay_times\":0}")
                    rpc(transport, cipher, 2, "print_job", validJobParams())
                    val failure = assertThrows(PrinterFailure::class.java) { rpc(transport, cipher, 3, "confirm_job", params) }
                    assertEquals(PrinterFailureCode.PROTOCOL_ERROR, failure.code)
                }
            }
            assertEquals(0, printer.completedJobs)
        }
    }

    @Test fun independentDecoderRejectsMalformedScanAfterCompleteUpload() {
        val bytes = jpeg()
        var offset = 2
        while (bytes[offset + 1] != 0xda.toByte()) {
            offset += 2 + ((bytes[offset + 2].toInt() and 255) shl 8) + (bytes[offset + 3].toInt() and 255)
        }
        bytes[offset + 4] = 4 // Four scan components contradict the three-component baseline frame.
        val printer = SimulatedPrinter()
        val guard = Guard()
        val failure = assertThrows(PrinterFailure::class.java) { print(printer, bytes, guard) }
        assertEquals(PrinterFailureCode.PROTOCOL_ERROR, failure.code)
        assertArrayEquals(bytes, printer.uploadedJpeg)
        assertTrue(guard.unresolved)
        assertEquals(0, printer.completedJobs)
    }

    @Test fun closedPrinterConnectionRejectsReadsAndWrites() {
        val transport = SimulatedPrinter().connect()
        transport.close()
        transport.close()
        assertThrows(IOException::class.java) { transport.read(1) }
        assertThrows(IOException::class.java) { transport.write(byteArrayOf()) }
    }

    @Test fun virtualClockAdvancesWithoutSleepingAndRejectsNegativePause() {
        val clock = SimulationClock()
        clock.pause(240_000)
        assertEquals(240_000L, clock.milliseconds)
        assertEquals(240_000L, clock.now())
        assertThrows(IllegalArgumentException::class.java) { clock.pause(-1) }
        assertEquals(240_000L, clock.now())
    }

    private fun validJobParams(size: Int = jpeg().size) =
        "{\"copies\":1,\"channel\":64,\"job_type\":0,\"file_size\":$size}"

    private fun rpc(transport: PrinterTransport, cipher: PrinterCipher, id: Long, method: String, params: String) {
        val json = "{\"id\":$id,\"method\":\"$method\",\"params\":$params}"
        transport.write(PrinterProtocol.encode(PrinterFrame(1, id, cipher.crypt(json.toByteArray()))))
    }

    private fun fileFrame(chunk: ByteArray, jobId: Int = 7, total: Int, part: Int = 1): PrinterFrame {
        val body = ByteBuffer.allocate(chunk.size + 4).order(ByteOrder.LITTLE_ENDIAN).putInt(jobId).put(chunk).array()
        return PrinterFrame(2, 3, body, encoding = 2, total = total, part = part)
    }
}
