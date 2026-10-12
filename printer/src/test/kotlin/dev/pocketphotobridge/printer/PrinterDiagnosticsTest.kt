package dev.pocketphotobridge.printer

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PrinterDiagnosticsTest {
    private val token = "000102030405060708090a0b" // Synthetic fixture, never a printer credential.
    private class Transport(private val reply: (String, Long) -> ByteArray) : PrinterTransport {
        val methods = mutableListOf<String>()
        private val incoming = ArrayDeque<ByteArray>()
        private val cipher = PrinterCipher("000102030405060708090a0b")
        override fun write(bytes: ByteArray) {
            val frame = PrinterProtocol.decode(bytes)
            val request = JsonParser.parseString(cipher.crypt(frame.body).toString(Charsets.UTF_8)).asJsonObject
            val method = request["method"].asString
            methods += method
            incoming += reply(method, request["id"].asLong)
        }
        override fun read(timeoutMs: Long) = incoming.removeFirstOrNull() ?: throw PrinterFailure(PrinterFailureCode.TIMEOUT)
        override fun close() { cipher.close() }
    }
    private fun packet(id: Long, result: String): ByteArray = PrinterCipher(token).use { cipher ->
        PrinterProtocol.encode(PrinterFrame(1, id, cipher.crypt("{\"id\":$id,\"result\":$result}".toByteArray()), interactive = 7))
    }
    private fun withHeader(packet: ByteArray, version: Int, interactive: Int): ByteArray = packet.copyOf().apply {
        this[1] = version.toByte(); this[4] = interactive.toByte()
        this[lastIndex - 1] = (((0 until size - 2).sumOf { this[it].toInt() and 255 } - 126) and 255).toByte()
    }
    private fun statusReply(id: Long) = packet(id, "{\"category\":\"idle\",\"sub_category\":\"init\",\"error\":0,\"battery\":80}")
    @Test fun diagnosesVersionOneInteractiveSixRepliesWithoutChangingOutgoingVersion() {
        val methods = mutableListOf<String>()
        val events = mutableListOf<PrinterDiagnostic>()
        val incoming = ArrayDeque<ByteArray>()
        PrinterCipher(token).use { cipher ->
            val transport = object : PrinterTransport {
                override fun write(bytes: ByteArray) {
                    assertEquals("Inbound version must not silently change request encoding", 100, bytes[1].toInt() and 255)
                    val request = JsonParser.parseString(cipher.crypt(PrinterProtocol.decode(bytes).body).toString(Charsets.UTF_8)).asJsonObject
                    val id = request["id"].asLong; val method = request["method"].asString
                    methods += method
                    incoming += withHeader(if (method == "mixed_status") statusReply(id) else packet(id, "[{}]"), 1, 6)
                }
                override fun read(timeoutMs: Long) = incoming.removeFirstOrNull() ?: throw PrinterFailure(PrinterFailureCode.TIMEOUT)
                override fun close() { }
            }
            PrinterSession(transport, token, events::add).use {
                assertEquals(PrinterStatus("init", 80), it.diagnose())
            }
        }
        assertEquals(listOf("mixed_status", "get_prop"), methods)
        assertTrue(events.any { it.stage == PrinterStage.STATUS_READY })
    }
    @Test fun interactiveSixReplyIsCorrelatedEvenWithLegacyVersion() {
        val transport = Transport { method, id ->
            withHeader(if (method == "mixed_status") statusReply(id) else packet(id, "[{}]"), 100, 6)
        }
        PrinterSession(transport, token).use { assertEquals(80, it.diagnose().battery) }
    }
    @Test fun requestEchoAndMethodMessagesCannotSatisfyAResponseEvenWithMatchingId() {
        val methods = mutableListOf<String>()
        val events = mutableListOf<PrinterDiagnostic>()
        val incoming = ArrayDeque<ByteArray>()
        PrinterCipher(token).use { cipher ->
            val transport = object : PrinterTransport {
                override fun write(bytes: ByteArray) {
                    val request = JsonParser.parseString(cipher.crypt(PrinterProtocol.decode(bytes).body).toString(Charsets.UTF_8)).asJsonObject
                    val id = request["id"].asLong; val method = request["method"].asString
                    methods += method
                    incoming += bytes.copyOf() // A request echo has a matching id but no result.
                    val falseResponse = "{\"id\":$id,\"method\":\"private-event-name\",\"result\":{\"category\":\"processing\",\"sub_category\":\"printing\"}}"
                    incoming += withHeader(PrinterProtocol.encode(PrinterFrame(1, id, cipher.crypt(falseResponse.toByteArray()))), 1, 6)
                    incoming += withHeader(if (method == "mixed_status") statusReply(id) else packet(id, "[{}]"), 1, 6)
                }
                override fun read(timeoutMs: Long) = incoming.removeFirstOrNull() ?: throw PrinterFailure(PrinterFailureCode.TIMEOUT)
                override fun close() { }
            }
            PrinterSession(transport, token, events::add).use { assertEquals("init", it.diagnose().phase) }
        }
        assertEquals(listOf("mixed_status", "get_prop"), methods)
        val lines = events.map { it.safeText() }
        assertTrue(lines.contains("PRINTER_REPLY_IGNORED reason=RPC_METHOD_MESSAGE"))
        assertFalse(lines.joinToString().contains("private-event-name"))
    }
    private fun trace(transport: Transport, expected: PrinterFailureCode = PrinterFailureCode.PROTOCOL_ERROR): List<String> {
        val events = mutableListOf<PrinterDiagnostic>()
        PrinterSession(transport, token, events::add).use { session ->
            assertEquals(expected, assertThrows(PrinterFailure::class.java) { session.diagnose() }.code)
        }
        return events.map { it.safeText() }
    }
    @Test fun distinguishesMissingStatusCategoryWithoutExportingReplyContents() {
        val transport = Transport { _, id -> packet(id, "{\"sub_category\":\"init\",\"sn\":\"private-serial\"}") }
        val lines = trace(transport)
        assertEquals("PRINTER_QUERY_STATUS", lines.first())
        assertTrue(lines.contains("PRINTER_REJECTED reason=STATUS_CATEGORY_MISSING"))
        assertFalse(lines.joinToString().contains("private-serial"))
        assertEquals(listOf("mixed_status"), transport.methods)
    }
    @Test fun distinguishesNumericStatusPhaseWithoutGuessingItsMeaning() {
        val transport = Transport { _, id -> packet(id, "{\"category\":\"idle\",\"sub_category\":0}") }
        val lines = trace(transport)
        assertTrue(lines.contains("PRINTER_REJECTED reason=STATUS_PHASE_TYPE"))
        assertEquals(listOf("mixed_status"), transport.methods)
    }
    @Test fun identifiesSecondRequestFailureAfterStatusParsedSuccessfully() {
        val transport = Transport { method, id ->
            if (method == "mixed_status") packet(id, "{\"category\":\"idle\",\"sub_category\":\"init\"}")
            else PrinterCipher(token).use { cipher -> PrinterProtocol.encode(PrinterFrame(1, id,
                cipher.crypt("{\"id\":$id,\"mac\":\"private-device-id\"}".toByteArray()), interactive = 7)) }
        }
        val lines = trace(transport)
        assertTrue(lines.contains("PRINTER_STATUS_PARSED"))
        assertTrue(lines.contains("PRINTER_QUERY_DEVICE_INFO"))
        assertTrue(lines.contains("PRINTER_REJECTED reason=RPC_RESULT_MISSING"))
        assertFalse(lines.joinToString().contains("private-device-id"))
        assertEquals(listOf("mixed_status", "get_prop"), transport.methods)
    }
    @Test fun checksumFailureReportsFrameStructureBeforeDecryption() {
        val transport = Transport { _, id -> packet(id, "{}").apply { this[lastIndex - 1] = (this[lastIndex - 1].toInt() xor 1).toByte() } }
        val lines = trace(transport)
        assertTrue(lines.any { it.startsWith("PRINTER_RX_BYTES count=") })
        assertTrue(lines.any { it.startsWith("PRINTER_RX_FRAME version=100 reserved=0 channel=1 interactive=7 encoding=3 encryption=4") })
        assertTrue(lines.contains("PRINTER_REJECTED reason=FRAME_CHECKSUM"))
        assertFalse(lines.any { it.startsWith("PRINTER_REPLY_MATCHED") })
    }
    @Test fun unsupportedCipherIsReportedWithoutTryingAnotherProfile() {
        val transport = Transport { _, id -> packet(id, "{}").apply {
            this[19] = ((this[19].toInt() and 255 and 0xe3) or (5 shl 2)).toByte()
            this[lastIndex - 1] = (((0 until size - 2).sumOf { this[it].toInt() and 255 } - 126) and 255).toByte()
        } }
        val lines = trace(transport)
        assertTrue(lines.any { it.contains("encryption=5") })
        assertTrue(lines.contains("PRINTER_REJECTED reason=FRAME_ENCRYPTION"))
        assertEquals(listOf("mixed_status"), transport.methods)
    }
    @Test fun unknownStatusValuesAndIdentifiersStayOutOfDiagnostics() {
        val transport = Transport { _, id -> packet(id, "{\"category\":\"secret-or-unknown-state\",\"sub_category\":\"init\"}") }
        val lines = trace(transport, PrinterFailureCode.UNSUPPORTED_STATUS)
        assertTrue(lines.contains("PRINTER_REJECTED reason=STATUS_CATEGORY_UNKNOWN"))
        assertFalse(lines.joinToString().contains("secret-or-unknown-state"))
        assertFalse(lines.any { it.contains("sequence=") || it.contains(token) })
    }
    @Test fun prefixFailureIsSpecificAndDoesNotDumpTheStream() {
        val transport = Transport { _, _ -> "private-unframed-greeting".toByteArray() }
        val lines = trace(transport)
        assertTrue(lines.contains("PRINTER_REJECTED reason=STREAM_HEADER"))
        assertFalse(lines.joinToString().contains("private-unframed-greeting"))
    }
}
