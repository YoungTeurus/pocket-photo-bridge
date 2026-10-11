package dev.pocketphotobridge.sony

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files

class SonyTransferSessionTest {
    private val endpoint = CameraEndpoint("192.168.122.1")
    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, -1, -39)

    private fun item(id: String = "selected", resources: String = """
        <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" size="100">http://192.168.122.1:64321/thumb.jpg</res>
        <res protocolInfo="http-get:*:image/jpeg:*" size="${jpeg.size}">http://192.168.122.1:64321/original.jpg</res>
    """) = """<item id="$id"><dc:title>DSC00042.JPG</dc:title>$resources</item>"""

    private fun page(items: String, returned: Int = 1, total: Int = returned): String {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">$items</DIDL-Lite>"""
        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><BrowseResponse><Result>$escaped</Result><NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches></BrowseResponse></s:Body></s:Envelope>"""
    }

    private inner class Camera(
        private val pages: List<String> = listOf(page(item())),
        private val payload: ByteArray = jpeg,
        private val advertisedLength: Long = payload.size.toLong(),
        private val rejectPush: Boolean = false,
        private val endFails: Boolean = false,
        private val actions: String = "<action><name>X_TransferStart</name></action><action><name>X_TransferEnd</name></action>"
    ) : CameraTransport {
        val requests = mutableListOf<CameraRequest>()
        var pageIndex = 0
        override fun execute(request: CameraRequest): CameraResponse {
            requests += request
            val bytes: ByteArray
            var status = 200
            when {
                request.uri.path == "/DmsDescPush.xml" -> bytes = """
                    <root><device><serviceList>
                    <service><serviceType>urn:schemas-sony-com:service:XPushList:1</serviceType><controlURL>/push</controlURL><SCPDURL>/actions.xml</SCPDURL></service>
                    <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/content</controlURL><SCPDURL>/content.xml</SCPDURL></service>
                    </serviceList></device></root>
                """.toByteArray()
                request.uri.path == "/actions.xml" -> bytes = "<scpd><actionList>$actions</actionList></scpd>".toByteArray()
                request.action?.endsWith("#Browse") == true -> {
                    if (rejectPush) { status = 500; bytes = "<Fault/>".toByteArray() }
                    else bytes = pages[pageIndex++].toByteArray()
                }
                request.uri.path == "/original.jpg" -> return CameraResponse(200, ByteArrayInputStream(payload), advertisedLength)
                request.action?.endsWith("#X_TransferEnd") == true && endFails -> { status = 500; bytes = "<Fault/>".toByteArray() }
                else -> bytes = "<Envelope><Body><OK/></Body></Envelope>".toByteArray()
            }
            return CameraResponse(status, ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }

    private fun withDirectory(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("sony-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun fails(expected: Failure, block: () -> Unit) {
        try { block(); fail("Expected $expected") } catch (e: ProtocolFailure) { assertEquals(expected, e.failure) }
    }

    @Test fun receivesOnlySelectedOriginalJpegAndEndsAfterDownload() = withDirectory { dir ->
        val camera = Camera()
        val received = SonyTransferSession(camera, endpoint).receive(dir)
        assertArrayEquals(jpeg, received.file.readBytes())
        assertEquals(jpeg.size.toLong(), received.bytes)
        val browse = camera.requests.single { it.action?.endsWith("#Browse") == true }
        assertTrue(browse.body!!.contains("<ObjectID>PushRoot</ObjectID>"))
        assertTrue(camera.requests.none { it.body?.contains("PhotoRoot") == true })
        assertEquals("/original.jpg", camera.requests[camera.requests.lastIndex - 1].uri.path)
        assertTrue(camera.requests.last().action!!.endsWith("#X_TransferEnd"))
    }

    @Test fun wrongModeNeverFallsBackToPhotoLibrary() = withDirectory { dir ->
        val camera = Camera(rejectPush = true)
        fails(Failure.WRONG_MODE) { SonyTransferSession(camera, endpoint).receive(dir) }
        assertTrue(camera.requests.none { it.body?.contains("PhotoRoot") == true })
        assertTrue(camera.requests.none { it.action?.endsWith("#X_TransferEnd") == true })
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun checksAllPagesBeforeAcceptingSingleSelection() = withDirectory { dir ->
        val camera = Camera(pages = listOf(page(item(), 1, 2), page(item("second"), 1, 2)))
        fails(Failure.AMBIGUOUS_SELECTION) { SonyTransferSession(camera, endpoint).receive(dir) }
        assertEquals(2, camera.pageIndex)
        assertTrue(camera.requests.none { it.uri.path.endsWith(".jpg") })
    }

    @Test fun followsContainersOnlyWithinPushSelection() = withDirectory { dir ->
        val camera = Camera(pages = listOf(page("<container id=\"selected-day\"/>", 1), page(item())))
        SonyTransferSession(camera, endpoint).receive(dir)
        assertTrue(camera.requests.any { it.body?.contains("<ObjectID>selected-day</ObjectID>") == true })
    }

    @Test fun rejectsPagingWithoutProgress() = withDirectory { dir ->
        val camera = Camera(pages = listOf(page("", 0, 2)))
        fails(Failure.INVALID_XML) { SonyTransferSession(camera, endpoint).receive(dir) }
    }

    @Test fun rejectsThumbnailOnlySelection() = withDirectory { dir ->
        val camera = Camera(pages = listOf(page(item(resources = """<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" size="8">http://192.168.122.1:64321/thumb.jpg</res>"""))))
        fails(Failure.NO_JPEG) { SonyTransferSession(camera, endpoint).receive(dir) }
    }

    @Test fun truncatedDownloadIsRemovedWithoutSuccessfulEnd() = withDirectory { dir ->
        val camera = Camera(payload = jpeg.dropLast(2).toByteArray(), advertisedLength = jpeg.size.toLong())
        fails(Failure.TRUNCATED_JPEG) { SonyTransferSession(camera, endpoint).receive(dir) }
        assertTrue(dir.listFiles()!!.isEmpty())
        assertTrue(camera.requests.none { it.action?.endsWith("#X_TransferEnd") == true })
    }

    @Test fun completedJpegSurvivesUnconfirmedEnd() = withDirectory { dir ->
        val camera = Camera(endFails = true)
        val events = mutableListOf<Diagnostic>()
        val received = SonyTransferSession(camera, endpoint, events::add).receive(dir)
        assertTrue(received.file.exists())
        assertTrue(events.any { it.stage == Stage.END_UNCONFIRMED })
    }

    @Test fun diagnosticsDoesNotStartOrBrowseTransfer() {
        val camera = Camera()
        SonyTransferSession(camera, endpoint).diagnose()
        assertTrue(camera.requests.all { it.action == null })
    }

    @Test fun unadvertisedActionsStopBeforeTransfer() = withDirectory { dir ->
        val camera = Camera(actions = "<action><name>Other</name></action>")
        fails(Failure.UNSUPPORTED_SERVICE) { SonyTransferSession(camera, endpoint).receive(dir) }
        assertTrue(camera.requests.all { it.action == null })
    }

    @Test fun rejectsOffCameraAndCredentialBearingUrls() {
        listOf("https://192.168.122.1/image", "http://example.com/x", "http://192.168.122.2/x",
            "http://user:password@192.168.122.1/x", "file:///sdcard/x", "//example.com/x", "http://192.168.122.1/x#fragment").forEach {
            fails(Failure.UNSAFE_URL) { endpoint.resolve(it) }
        }
    }

    @Test fun acceptsAdvertisedCameraResourcePort() {
        assertEquals(64444, endpoint.resolve("http://192.168.122.1:64444/image.jpg").port)
        assertEquals("/push", endpoint.resolve("/push").path)
    }

    @Test fun rejectsDtdAndMalformedXmlWithoutLeakingRawResponse() = withDirectory { dir ->
        listOf("<!DOCTYPE Result [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><Result>&secret;</Result>", "<broken>").forEach { xml ->
            val camera = Camera(pages = listOf(xml))
            fails(Failure.INVALID_XML) { SonyTransferSession(camera, endpoint).receive(dir) }
        }
    }

    @Test fun failedDecodeRemovesPhotoBeforeSuccessfulTransferEnd() = withDirectory { dir ->
        val camera = Camera()
        try {
            SonyTransferSession(camera, endpoint, validateJpeg = { throw IOException("INVALID_JPEG") }).receive(dir)
            fail("Decoder rejection must stop transfer completion")
        } catch (e: IOException) { assertEquals("INVALID_JPEG", e.message) }
        assertTrue(dir.listFiles()!!.isEmpty())
        assertTrue(camera.requests.none { it.action?.endsWith("#X_TransferEnd") == true })
    }

    @Test fun cancelledNegotiationNeverDownloadsOrEnds() = withDirectory { dir ->
        val camera = Camera()
        try {
            fails(Failure.CANCELLED) {
                SonyTransferSession(camera, endpoint, report = {
                    if (it.stage == Stage.TRANSFER_START) Thread.currentThread().interrupt()
                }).receive(dir)
            }
            assertTrue(camera.requests.none { it.uri.path.endsWith(".jpg") || it.action?.endsWith("#X_TransferEnd") == true })
        } finally { Thread.interrupted() }
    }

    @Test fun emptySelectionNeverDownloadsAnything() = withDirectory { dir ->
        val camera = Camera(pages = listOf(page("", 0, 0)))
        fails(Failure.NO_JPEG) { SonyTransferSession(camera, endpoint).receive(dir) }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun rejectsOffCameraResourceBeforeDownload() = withDirectory { dir ->
        val camera = Camera(pages = listOf(page(item(resources = """<res protocolInfo="http-get:*:image/jpeg:*" size="8">http://example.com/personal.jpg</res>"""))))
        fails(Failure.UNSAFE_URL) { SonyTransferSession(camera, endpoint).receive(dir) }
        assertTrue(camera.requests.all { it.uri.host == "192.168.122.1" })
    }

    @Test fun rejectsPublicAndMalformedCameraEndpoints() {
        listOf("example.com", "8.8.8.8", "192.168.1.999", "192.168.1.1/secret", "127.0.0.1").forEach {
            fails(Failure.UNSAFE_URL) { CameraEndpoint(it) }
        }
    }
}
