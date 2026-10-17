package dev.pocketphotobridge.bridge

import com.google.gson.JsonParser
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.printer.*
import dev.pocketphotobridge.sony.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class PrintCoordinatorTest {
    private val token = "000102030405060708090a0b" // Public synthetic fixture key only.
    private fun photo() = javaClass.getResourceAsStream("/simulation/sony-selected.jpg")!!.use { it.readBytes() }
    private fun root(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("coordinator-test-")
        try { test(directory) } finally { directory.toFile().deleteRecursively() }
    }

    private class Camera(private val jpeg: ByteArray, val empty: Boolean = false, val interrupted: Boolean = false) : CameraWifiConnector {
        var connections = 0
        var downloads = 0
        var ends = 0
        override fun connect(trigger: SelectionTrigger): CameraLink {
            connections++
            val endpoint = CameraEndpoint("192.168.122.1")
            val transport = object : CameraTransport {
              override fun execute(request: CameraRequest): CameraResponse {
                val content = when {
                    request.uri.path == "/DmsDescPush.xml" -> """<root><device><serviceList>
                        <service><serviceType>urn:schemas-sony-com:service:XPushList:1</serviceType><controlURL>/push</controlURL><SCPDURL>/actions</SCPDURL></service>
                        <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/content</controlURL><SCPDURL>/content-actions</SCPDURL></service>
                        </serviceList></device></root>"""
                    request.uri.path == "/actions" -> """<scpd><actionList><action><name>X_TransferStart</name></action>
                        <action><name>X_TransferEnd</name><argumentList><argument><name>ErrCode</name></argument></argumentList></action></actionList></scpd>"""
                    request.action?.endsWith("#Browse") == true -> {
                        check(request.body!!.contains("<ObjectID>PushRoot</ObjectID>"))
                        val item = if (empty) "" else """<item id="selected"><dc:title>SELECTED.JPG</dc:title>
                            <res protocolInfo="http-get:*:image/jpeg:*" size="${jpeg.size}">http://192.168.122.1/selected.jpg</res></item>"""
                        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">$item</DIDL-Lite>"""
                        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        "<Envelope><Body><BrowseResponse><Result>$escaped</Result><NumberReturned>${if (empty) 0 else 1}</NumberReturned><TotalMatches>${if (empty) 0 else 1}</TotalMatches></BrowseResponse></Body></Envelope>"
                    }
                    request.uri.path == "/selected.jpg" -> {
                        downloads++
                        val bytes = if (interrupted) jpeg.copyOf(jpeg.size / 2) else jpeg
                        return CameraResponse(200, ByteArrayInputStream(bytes), jpeg.size.toLong())
                    }
                    else -> {
                        if (request.action?.endsWith("#X_TransferEnd") == true) ends++
                        "<Envelope><Body><OK/></Body></Envelope>"
                    }
                }.toByteArray()
                return CameraResponse(200, ByteArrayInputStream(content), content.size.toLong())
              }
            }
            return CameraLink(endpoint, transport)
        }
    }

    private inner class Printer(private val journal: FileBridgeJournal, var busy: Boolean = false,
        val drop: Boolean = false, val powerCut: Boolean = false) : PrinterConnector {
        var jobs = 0
        var connections = 0
        override fun connect(): PrinterTransport {
            connections++
            return object : PrinterTransport {
                private val cipher = PrinterCipher(token)
                private val replies = ArrayDeque<ByteArray>()
                override fun write(bytes: ByteArray) {
                    val frame = PrinterProtocol.decode(bytes)
                    if (frame.channel == 2) return
                    val request = JsonParser.parseString(cipher.crypt(frame.body).toString(Charsets.UTF_8)).asJsonObject
                    val id = request["id"].asLong
                    val method = request["method"].asString
                    if (method == "clean_data") return
                    if (method == "print_job") {
                        assertTrue("Reservation must precede printer submission", journal.unresolved)
                        assertEquals(1, request["params"].asJsonObject["copies"].asInt)
                        jobs++
                        if (powerCut) throw PowerCut()
                        if (drop) throw IOException("private printer identity")
                    }
                    val result = when (method) {
                        "mixed_status" -> if (busy) "{\"category\":\"processing\",\"sub_category\":\"printing\",\"error\":0}"
                            else "{\"category\":\"idle\",\"sub_category\":\"init\",\"error\":0}"
                        "print_job" -> "{\"job_id\":7}"
                        "confirm_job" -> "[\"OK\"]"
                        "job_info" -> "[{\"job_id\":7,\"job_state\":\"finished\"}]"
                        else -> error("Unsupported test operation")
                    }
                    val reply = "{\"id\":$id,\"result\":$result}".toByteArray()
                    replies += PrinterProtocol.encode(PrinterFrame(1, id, cipher.crypt(reply), interactive = 7))
                }
                override fun read(timeoutMs: Long) = replies.removeFirst()
                override fun close() = cipher.close()
            }
        }
    }

    private class PowerCut : Error()
    private fun coordinator(root: Path, journal: FileBridgeJournal, camera: CameraWifiConnector, printer: PrinterConnector,
        logs: MutableList<String> = mutableListOf()): PrintCoordinator {
        var clock = 0L
        return PrintCoordinator(camera, printer, JvmPrintImagePreparer(), journal, root.resolve("cache"),
            token = { token }, report = logs::add, now = { clock }, pause = { clock += it })
    }

    @Test fun receivesPreparesAndPrintsOneSelectedPhotoThroughRealSessions() = root { directory ->
        FileBridgeJournal(directory.resolve("state")).use { journal ->
            val camera = Camera(photo()); val printer = Printer(journal)
            val result = coordinator(directory, journal, camera, printer).process(SelectionTrigger("first"))
            assertEquals(BridgeOutcome.PRINTED, result.outcome)
            assertEquals(1, camera.downloads); assertEquals(1, camera.ends); assertEquals(1, printer.jobs)
            assertFalse(journal.unresolved)
            assertTrue(directory.resolve("cache").toFile().listFiles()!!.isEmpty())
        }
    }

    @Test fun repeatedGenerationIsSuppressedBeforeCameraOrPrinterTraffic() = root { directory ->
        FileBridgeJournal(directory.resolve("state")).use { journal ->
            val camera = Camera(photo()); val printer = Printer(journal)
            val coordinator = coordinator(directory, journal, camera, printer)
            coordinator.process(SelectionTrigger("first"))
            assertEquals(BridgeOutcome.DUPLICATE_SUPPRESSED, coordinator.process(SelectionTrigger("first")).outcome)
            assertEquals(1, camera.connections); assertEquals(1, printer.jobs)
        }
    }

    @Test fun newGenerationWithSameJpegIsSuppressedByContent() = root { directory ->
        FileBridgeJournal(directory.resolve("state")).use { journal ->
            val camera = Camera(photo()); val printer = Printer(journal)
            val coordinator = coordinator(directory, journal, camera, printer)
            coordinator.process(SelectionTrigger("first"))
            assertEquals(BridgeOutcome.DUPLICATE_SUPPRESSED, coordinator.process(SelectionTrigger("second")).outcome)
            assertEquals(2, camera.downloads); assertEquals(1, printer.jobs)
        }
    }

    @Test fun uncertainSubmissionBlocksEveryNewTriggerBeforeConnecting() = root { directory ->
        FileBridgeJournal(directory.resolve("state")).use { journal ->
            val camera = Camera(photo()); val printer = Printer(journal, drop = true)
            val coordinator = coordinator(directory, journal, camera, printer)
            assertEquals(BridgeOutcome.UNKNOWN_OUTCOME, coordinator.process(SelectionTrigger("first")).outcome)
            assertTrue(journal.unresolved)
            assertEquals(BridgeOutcome.UNKNOWN_OUTCOME, coordinator.process(SelectionTrigger("new")).outcome)
            assertEquals(1, camera.connections); assertEquals(1, printer.jobs)
        }
    }

    @Test fun hardFailureLeavesDurableGuardAndRestartCannotSubmit() = root { directory ->
        FileBridgeJournal(directory.resolve("state")).use { journal ->
            val printer = Printer(journal, powerCut = true)
            try { coordinator(directory, journal, Camera(photo()), printer).process(SelectionTrigger("first")); fail("Expected power cut") }
            catch (_: PowerCut) { assertEquals(1, printer.jobs) }
        }
        FileBridgeJournal(directory.resolve("state")).use { journal ->
            val camera = Camera(photo()); val printer = Printer(journal)
            assertEquals(BridgeOutcome.UNKNOWN_OUTCOME, coordinator(directory, journal, camera, printer).process(SelectionTrigger("first")).outcome)
            assertEquals(0, camera.connections); assertEquals(0, printer.jobs)
        }
    }

    @Test fun busyBeforeReservationPermitsAnotherGenuineGeneration() = root { directory ->
        FileBridgeJournal(directory.resolve("state")).use { journal ->
            val camera = Camera(photo()); val printer = Printer(journal, busy = true)
            val coordinator = coordinator(directory, journal, camera, printer)
            assertEquals("BUSY", coordinator.process(SelectionTrigger("first")).category)
            assertFalse(journal.unresolved); assertEquals(0, printer.jobs)
            printer.busy = false
            assertEquals(BridgeOutcome.PRINTED, coordinator.process(SelectionTrigger("fresh")).outcome)
            assertEquals(1, printer.jobs)
        }
    }

    @Test fun emptyInterruptedAndUndecodablePhotosNeverReachPrinter() = root { directory ->
        for ((index, camera) in listOf(Camera(photo(), empty = true), Camera(photo(), interrupted = true),
            Camera(byteArrayOf(-1, -40, 0, 0, -1, -39))).withIndex()) {
            FileBridgeJournal(directory.resolve("state-$index")).use { journal ->
                val printer = Printer(journal)
                val result = coordinator(directory, journal, camera, printer).process(SelectionTrigger("first"))
                assertTrue(result.outcome in setOf(BridgeOutcome.CAMERA_FAILED, BridgeOutcome.IMAGE_FAILED))
                assertEquals(0, camera.ends); assertEquals(0, printer.jobs); assertFalse(journal.unresolved)
            }
        }
    }

    @Test fun adapterErrorsAndSelectionIdentifiersNeverEnterDiagnostics() = root { directory ->
        FileBridgeJournal(directory.resolve("state")).use { journal ->
            val logs = mutableListOf<String>()
            val camera = CameraWifiConnector { throw IOException("private-wifi-password-and-address") }
            val result = coordinator(directory, journal, camera, Printer(journal), logs).process(SelectionTrigger("private-camera-name"))
            assertEquals(BridgeOutcome.CAMERA_FAILED, result.outcome)
            assertEquals("IO_ERROR", result.category)
            assertFalse(logs.joinToString("\n").contains("private"))
        }
    }

    @Test fun cancelledBeforeStartDoesNotContactDevices() = root { directory ->
        FileBridgeJournal(directory.resolve("state")).use { journal ->
            val camera = Camera(photo()); val printer = Printer(journal)
            try {
                Thread.currentThread().interrupt()
                val result = coordinator(directory, journal, camera, printer).process(SelectionTrigger("first"))
                assertEquals(BridgeOutcome.CANCELLED, result.outcome)
                assertEquals(0, camera.connections); assertEquals(0, printer.jobs)
            } finally { Thread.interrupted() }
        }
    }
}
