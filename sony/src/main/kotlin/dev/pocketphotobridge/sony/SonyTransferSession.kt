package dev.pocketphotobridge.sony

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.net.URI

data class CameraRequest(val uri: URI, val action: String? = null, val body: String? = null)
class CameraResponse(val status: Int, val body: InputStream, val length: Long? = null,
                     private val release: () -> Unit = {}) : Closeable {
    override fun close() { try { body.close() } finally { release() } }
}
interface CameraTransport { fun execute(request: CameraRequest): CameraResponse }
data class ReceivedPhoto(val file: File, val bytes: Long)

class SonyTransferSession(private val transport: CameraTransport, private val endpoint: CameraEndpoint,
                          private val report: (Diagnostic) -> Unit = {},
                          private val validateJpeg: (File) -> Unit = {}) {
    private var startedAt = 0L

    fun diagnose() { startedAt = System.nanoTime(); discover() }

    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw ProtocolFailure(Failure.CANCELLED)
        if (System.nanoTime() - startedAt > 120_000_000_000L) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
    }

    private fun xml(request: CameraRequest, httpFailure: Failure = Failure.HTTP_ERROR): String {
        checkActive()
        return transport.execute(request).use { response ->
            if (response.status != 200) throw ProtocolFailure(httpFailure)
            if ((response.length ?: 0) > 2 * 1024 * 1024) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
            // Use APIs available on Android 10; InputStream.readNBytes requires newer Android.
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                checkActive()
                val count = response.body.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2 * 1024 * 1024) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun discover(): Pair<Service, Service> {
        val services = SonyXml.services(xml(CameraRequest(endpoint.resolve("/DmsDescPush.xml")), Failure.WRONG_MODE))
        report(Diagnostic(Stage.DESCRIPTOR))
        val push = services.singleOrNull { it.type == SonyXml.PUSH } ?: throw ProtocolFailure(Failure.UNSUPPORTED_SERVICE)
        val content = services.singleOrNull { it.type == SonyXml.CONTENT } ?: throw ProtocolFailure(Failure.UNSUPPORTED_SERVICE)
        // Validate all advertised endpoints before any state-changing request.
        endpoint.resolve(push.control); endpoint.resolve(content.control)
        val actions = SonyXml.actions(xml(CameraRequest(endpoint.resolve(push.description))))
        if (!actions.containsAll(listOf("X_TransferStart", "X_TransferEnd"))) throw ProtocolFailure(Failure.UNSUPPORTED_SERVICE)
        report(Diagnostic(Stage.ACTIONS))
        return push to content
    }

    private fun soap(service: Service, action: String, arguments: String = "", failure: Failure = Failure.HTTP_ERROR): String =
        xml(CameraRequest(endpoint.resolve(service.control), "${service.type}#$action",
            SonyXml.envelope(service.type, action, arguments)), failure)

    private fun selection(content: Service): Resource {
        val queue = ArrayDeque<String>().apply { add("PushRoot") }
        val seenContainers = mutableSetOf<String>()
        val seenItems = mutableSetOf<String>()
        val selected = mutableListOf<SelectedItem>()
        var entries = 0
        var pages = 0
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id.startsWith("PhotoRoot") || !seenContainers.add(id)) throw ProtocolFailure(Failure.INVALID_XML)
            if (seenContainers.size > 32) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
            var offset = 0
            var expectedTotal: Int? = null
            do {
                if (++pages > 64) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
                val arguments = "<ObjectID>${SonyXml.escape(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
                    "<Filter>*</Filter><StartingIndex>$offset</StartingIndex><RequestedCount>32</RequestedCount><SortCriteria></SortCriteria>"
                val page = SonyXml.browse(soap(content, "Browse", arguments, Failure.WRONG_MODE))
                if (expectedTotal != null && expectedTotal != page.total) throw ProtocolFailure(Failure.INVALID_XML)
                expectedTotal = page.total
                if (page.total > 128 || offset + page.returned > page.total) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
                entries += page.returned
                if (entries > 128) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
                queue.addAll(page.containers)
                page.items.forEach { if (!seenItems.add(it.id)) throw ProtocolFailure(Failure.INVALID_XML) }
                selected.addAll(page.items)
                offset += page.returned
                report(Diagnostic(Stage.PUSH_PAGE, page.returned.toLong()))
            } while (offset < expectedTotal)
        }
        report(Diagnostic(Stage.SELECTION, selected.size.toLong()))
        if (selected.size > 1) throw ProtocolFailure(Failure.AMBIGUOUS_SELECTION)
        val item = selected.singleOrNull() ?: throw ProtocolFailure(Failure.NO_JPEG)
        if (!item.title.endsWith(".jpg", true) && !item.title.endsWith(".jpeg", true)) throw ProtocolFailure(Failure.NO_JPEG)
        return item.resources.filter {
            val fields = it.protocol.split(':', limit = 4)
            fields.size == 4 && fields[0] == "http-get" && fields[2].equals("image/jpeg", true) &&
                !Regex("JPEG_(TN|SM|MED)(?:_|\\b)|JPEG_[A-Z0-9_]*ICO\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(fields[3]) && it.size > 0
        }.maxByOrNull { it.size } ?: throw ProtocolFailure(Failure.NO_JPEG)
    }

    fun receive(directory: File): ReceivedPhoto {
        startedAt = System.nanoTime()
        val (push, content) = discover()
        SonyXml.checkSoap(soap(push, "X_TransferStart"))
        report(Diagnostic(Stage.TRANSFER_START))
        val resource = selection(content)
        if (resource.size > 64 * 1024 * 1024) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
        val uri = endpoint.resolve(resource.url)
        directory.mkdirs()
        val file = File.createTempFile("sony-", ".jpg", directory)
        try {
            checkActive()
            val bytes = transport.execute(CameraRequest(uri)).use { response ->
                if (response.status != 200) throw ProtocolFailure(Failure.HTTP_ERROR)
                if ((response.length ?: 0) > 64 * 1024 * 1024) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
                var written = 0L
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        checkActive()
                        val count = response.body.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > 64 * 1024 * 1024) throw ProtocolFailure(Failure.LIMIT_EXCEEDED)
                        output.write(buffer, 0, count)
                    }
                }
                if (written != resource.size || (response.length != null && written != response.length))
                    throw ProtocolFailure(Failure.TRUNCATED_JPEG)
                written
            }
            RandomAccessFile(file, "r").use { input ->
                if (bytes < 4 || input.readUnsignedShort() != 0xffd8) throw ProtocolFailure(Failure.TRUNCATED_JPEG)
                input.seek(bytes - 2)
                if (input.readUnsignedShort() != 0xffd9) throw ProtocolFailure(Failure.TRUNCATED_JPEG)
            }
            validateJpeg(file)
            report(Diagnostic(Stage.JPEG_BYTES, bytes))
            checkActive()
            try {
                SonyXml.checkSoap(soap(push, "X_TransferEnd", "<ErrCode>0</ErrCode>"))
                report(Diagnostic(Stage.TRANSFER_END))
            } catch (e: Exception) {
                checkActive()
                report(Diagnostic(Stage.END_UNCONFIRMED))
            }
            return ReceivedPhoto(file, bytes)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
}
